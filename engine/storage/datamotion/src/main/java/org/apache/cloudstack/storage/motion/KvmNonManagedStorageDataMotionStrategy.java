/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.cloudstack.storage.motion;

import java.io.File;
import java.util.Map;
import java.util.Set;

import javax.inject.Inject;

import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.ObjectInDataStoreStateMachine;
import org.apache.cloudstack.engine.subsystem.api.storage.StrategyPriority;
import org.apache.cloudstack.engine.subsystem.api.storage.TemplateDataFactory;
import org.apache.cloudstack.engine.subsystem.api.storage.TemplateInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo;
import org.apache.cloudstack.storage.command.CopyCommand;
import org.apache.cloudstack.storage.datastore.DataStoreManagerImpl;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.cloudstack.storage.to.TemplateObjectTO;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.StringUtils;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.MigrateCommand;
import com.cloud.agent.api.MigrateCommand.MigrateDiskInfo;
import com.cloud.exception.AgentUnavailableException;
import com.cloud.exception.OperationTimedoutException;
import com.cloud.host.Host;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.storage.DataStoreRole;
import com.cloud.storage.ScopeType;
import com.cloud.storage.Storage;
import com.cloud.storage.Storage.StoragePoolType;
import com.cloud.storage.StorageManager;
import com.cloud.storage.StoragePool;
import com.cloud.storage.VMTemplateStoragePoolVO;
import com.cloud.storage.VMTemplateStorageResourceAssoc;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VMTemplatePoolDao;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VirtualMachineManager;

/**
 * Extends {@link StorageSystemDataMotionStrategy}, allowing KVM hosts to migrate VMs with the ROOT volume on a non managed local storage pool.
 * As {@link StorageSystemDataMotionStrategy} is considering KVM, this implementation also migrates only from/to KVM hosts.
 */
public class KvmNonManagedStorageDataMotionStrategy extends StorageSystemDataMotionStrategy {

    @Inject
    private TemplateDataFactory templateDataFactory;
    @Inject
    private VMTemplatePoolDao vmTemplatePoolDao;
    @Inject
    private DataStoreManagerImpl dataStoreManagerImpl;
    @Inject
    private VirtualMachineManager virtualMachineManager;


    /**
     * Uses the canHandle from the Super class {@link StorageSystemDataMotionStrategy}. If the storage pool is of file and the internalCanHandle from {@link StorageSystemDataMotionStrategy} CANT_HANDLE, returns the StrategyPriority.HYPERVISOR strategy priority. otherwise returns CANT_HANDLE.
     * Note that the super implementation (override) is called by {@link #canHandle(Map, Host, Host)} which ensures that {@link #internalCanHandle(Map)} will be executed only if the source host is KVM.
     */
    @Override
    protected StrategyPriority internalCanHandle(Map<VolumeInfo, DataStore> volumeMap, Host srcHost, Host destHost) {
        if (super.internalCanHandle(volumeMap, srcHost, destHost) != StrategyPriority.CANT_HANDLE) {
            return StrategyPriority.CANT_HANDLE;
        }
        if (canHandleKVMNonManagedLiveNFSStorageMigration(volumeMap, srcHost, destHost) != StrategyPriority.CANT_HANDLE) {
            return StrategyPriority.HYPERVISOR;
        }

        Set<VolumeInfo> volumeInfoSet = volumeMap.keySet();
        for (VolumeInfo volumeInfo : volumeInfoSet) {
            StoragePoolVO storagePoolVO = _storagePoolDao.findById(volumeInfo.getPoolId());
            if (!supportStoragePoolType(storagePoolVO.getPoolType())) {
                return StrategyPriority.CANT_HANDLE;
            }
        }
        return StrategyPriority.HYPERVISOR;
    }

    /**
     * Allow KVM live storage migration for non managed storage when:
     * - Source host and destination host are different, and are on the same cluster
     * - Source and destination storage are NFS
     * - Destination storage is cluster-wide
     */
    protected StrategyPriority canHandleKVMNonManagedLiveNFSStorageMigration(Map<VolumeInfo, DataStore> volumeMap,
                                                                             Host srcHost, Host destHost) {
        if (srcHost.getId() != destHost.getId() &&
                srcHost.getClusterId().equals(destHost.getClusterId()) &&
                isSourceNfsPrimaryStorage(volumeMap) &&
                isDestinationNfsPrimaryStorageClusterWide(volumeMap)) {
            return StrategyPriority.HYPERVISOR;
        }
        return StrategyPriority.CANT_HANDLE;
    }

    /**
     * True if volumes source storage are NFS
     */
    protected boolean isSourceNfsPrimaryStorage(Map<VolumeInfo, DataStore> volumeMap) {
        if (MapUtils.isNotEmpty(volumeMap)) {
            for (VolumeInfo volumeInfo : volumeMap.keySet()) {
                StoragePoolVO storagePoolVO = _storagePoolDao.findById(volumeInfo.getPoolId());
                return storagePoolVO != null &&
                        storagePoolVO.getPoolType() == Storage.StoragePoolType.NetworkFilesystem;
            }
        }
        return false;
    }

    /**
     * True if destination storage is cluster-wide NFS
     */
    protected boolean isDestinationNfsPrimaryStorageClusterWide(Map<VolumeInfo, DataStore> volumeMap) {
        if (MapUtils.isNotEmpty(volumeMap)) {
            for (DataStore dataStore : volumeMap.values()) {
                StoragePoolVO storagePoolVO = _storagePoolDao.findById(dataStore.getId());
                return storagePoolVO != null &&
                        storagePoolVO.getPoolType() == Storage.StoragePoolType.NetworkFilesystem &&
                        storagePoolVO.getScope() == ScopeType.CLUSTER;
            }
        }
        return false;
    }

    /**
     * Configures a {@link MigrateDiskInfo} object configured for migrating a File System volume and calls rootImageProvisioning.
     */
    @Override
    protected MigrateCommand.MigrateDiskInfo configureMigrateDiskInfo(VolumeInfo srcVolumeInfo, String destPath, String backingPath) {
            return new MigrateCommand.MigrateDiskInfo(srcVolumeInfo.getPath(), MigrateCommand.MigrateDiskInfo.DiskType.FILE, MigrateCommand.MigrateDiskInfo.DriverType.QCOW2,
                    MigrateCommand.MigrateDiskInfo.Source.FILE, destPath, backingPath);
    }

    /**
     * Generates the volume path by appending the Volume UUID to the Libvirt destiny images path.</br>
     * Example: /var/lib/libvirt/images/f3d49ecc-870c-475a-89fa-fd0124420a9b
     */
    @Override
    protected String generateDestPath(Host destHost, StoragePoolVO destStoragePool, VolumeInfo destVolumeInfo) {
        return new File(destStoragePool.getPath(), destVolumeInfo.getUuid()).getAbsolutePath();
    }

    @Override
    protected String generateBackingPath(StoragePoolVO destStoragePool, VolumeInfo destVolumeInfo) {
        String templateInstallPath = getVolumeBackingFile(destVolumeInfo);
        if (templateInstallPath == null) {
            return null;
        }
        return new File(destStoragePool.getPath(), templateInstallPath).getAbsolutePath();
    }

    /**
     * Returns the template UUID with the given id. If the template ID is null, it returns null.
     */
    protected String getTemplateUuid(Long templateId) {
        if (templateId == null) {
            return null;
        }
        TemplateInfo templateImage = templateDataFactory.getTemplate(templateId, DataStoreRole.Image);
        return templateImage.getUuid();
    }

    /**
     * Sets the volume path as the volume UUID.
     */
    @Override
    protected void setVolumePath(VolumeVO volume) {
        volume.setPath(volume.getUuid());
    }

    /**
     * Return true if the volume should be migrated. Currently only supports migrating volumes on storage pool of the type StoragePoolType.Filesystem.
     * This ensures that volumes on shared storage are not migrated and those on local storage pools are migrated.
     */
    @Override
    protected boolean shouldMigrateVolume(StoragePoolVO sourceStoragePool, Host destHost, StoragePoolVO destStoragePool) {
        return supportStoragePoolType(sourceStoragePool.getPoolType());
    }

    /**
     * If the template is not on the target primary storage then it copies the template.
     */
    @Override
    protected void copyTemplateToTargetFilesystemStorageIfNeeded(VolumeInfo srcVolumeInfo, StoragePool srcStoragePool, DataStore destDataStore, StoragePool destStoragePool,
            Host destHost) {
        if (srcVolumeInfo.getVolumeType() != Volume.Type.ROOT || srcVolumeInfo.getTemplateId() == null) {
            return;
        }

        TemplateInfo directDownloadTemplateInfo = templateDataFactory.getReadyBypassedTemplateOnPrimaryStore(srcVolumeInfo.getTemplateId(), destDataStore.getId(), destHost.getId());
        if (directDownloadTemplateInfo != null) {
            logger.debug("Template {} was of direct download type and successfully staged to primary store {}", directDownloadTemplateInfo.getImage(), directDownloadTemplateInfo.getDataStore());
            return;
        }

        VMTemplateStoragePoolVO sourceVolumeTemplateStoragePoolVO = vmTemplatePoolDao.findByPoolTemplate(destStoragePool.getId(), srcVolumeInfo.getTemplateId(), null);
        if (sourceVolumeTemplateStoragePoolVO == null && (isStoragePoolTypeInList(destStoragePool.getPoolType(), StoragePoolType.NetworkFilesystem, StoragePoolType.Filesystem, StoragePoolType.SharedMountPoint))) {
            DataStore sourceTemplateDataStore = dataStoreManagerImpl.getRandomImageStore(srcVolumeInfo.getDataCenterId());
            if (sourceTemplateDataStore != null) {
                TemplateInfo sourceTemplateInfo = templateDataFactory.getTemplate(srcVolumeInfo.getTemplateId(), sourceTemplateDataStore);
                TemplateObjectTO sourceTemplate = new TemplateObjectTO(sourceTemplateInfo);

                logger.debug("Could not find template [id={}, uuid={}, name={}] on the storage pool [{}]; copying the template to the target storage pool.",
                        srcVolumeInfo.getTemplateId(), sourceTemplateInfo.getUuid(), sourceTemplateInfo.getName(), destDataStore);

                TemplateInfo destTemplateInfo = templateDataFactory.getTemplate(srcVolumeInfo.getTemplateId(), destDataStore);
                final TemplateObjectTO destTemplate = new TemplateObjectTO(destTemplateInfo);
                Answer copyCommandAnswer = sendCopyCommand(destHost, sourceTemplate, destTemplate, destDataStore);

                if (copyCommandAnswer != null && copyCommandAnswer.getResult()) {
                    updateTemplateReferenceIfSuccessfulCopy(srcVolumeInfo.getTemplateId(), destTemplateInfo.getUuid(), destDataStore.getId(), destTemplate.getSize());
                }
                return;
            }
        }
        logger.debug("Skipping 'copy template to target filesystem storage before migration' due to the template [{}] already exist on the storage pool [{}].",
                srcVolumeInfo.getTemplateId(), destStoragePool);
    }

    /**
     *  Update the template reference on table "template_spool_ref" (VMTemplateStoragePoolVO).
     */
    protected void updateTemplateReferenceIfSuccessfulCopy(long templateId, String destTemplateInfoUuid, long destDataStoreId, long templateSize) {
        VMTemplateStoragePoolVO destVolumeTemplateStoragePoolVO = new VMTemplateStoragePoolVO(destDataStoreId, templateId, null);
        destVolumeTemplateStoragePoolVO.setDownloadPercent(100);
        destVolumeTemplateStoragePoolVO.setDownloadState(VMTemplateStorageResourceAssoc.Status.DOWNLOADED);
        destVolumeTemplateStoragePoolVO.setState(ObjectInDataStoreStateMachine.State.Ready);
        destVolumeTemplateStoragePoolVO.setTemplateSize(templateSize);
        destVolumeTemplateStoragePoolVO.setLocalDownloadPath(destTemplateInfoUuid);
        destVolumeTemplateStoragePoolVO.setInstallPath(destTemplateInfoUuid);
        vmTemplatePoolDao.persist(destVolumeTemplateStoragePoolVO);
    }

    /**
     * Sends the CopyCommand to migrate the template to the dest host.
     */
    protected Answer sendCopyCommand(Host destHost, TemplateObjectTO sourceTemplate, TemplateObjectTO destTemplate, DataStore destDataStore) {
        boolean executeInSequence = virtualMachineManager.getExecuteInSequence(HypervisorType.KVM);
        CopyCommand copyCommand = new CopyCommand(sourceTemplate, destTemplate, StorageManager.PRIMARY_STORAGE_DOWNLOAD_WAIT.value(), executeInSequence);
        try {
            Answer copyCommandAnswer = agentManager.send(destHost.getId(), copyCommand);
            logInCaseOfTemplateCopyFailure(copyCommandAnswer, sourceTemplate, destDataStore);
            return copyCommandAnswer;
        } catch (AgentUnavailableException | OperationTimedoutException e) {
            throw new CloudRuntimeException(generateFailToCopyTemplateMessage(sourceTemplate, destDataStore), e);
        }
    }

    private String generateFailToCopyTemplateMessage(TemplateObjectTO sourceTemplate, DataStore destDataStore) {
        return String.format("Failed to copy template [%s] to the primary storage pool [%s].", sourceTemplate, destDataStore);
    }

    /**
     * Logs in debug mode the copy command failure if the CopyCommand Answer has result as false.
     */
    protected void logInCaseOfTemplateCopyFailure(Answer copyCommandAnswer, TemplateObjectTO sourceTemplate, DataStore destDataStore) {
        if (copyCommandAnswer != null && !copyCommandAnswer.getResult()) {
            String failureDetails = StringUtils.EMPTY;
            if (copyCommandAnswer.getDetails() != null) {
                failureDetails = " Details: " + copyCommandAnswer.getDetails();
            }
            logger.error(generateFailToCopyTemplateMessage(sourceTemplate, destDataStore) + failureDetails);
        }
    }

    protected Boolean supportStoragePoolType(StoragePoolType storagePoolType) {
        return super.supportStoragePoolType(storagePoolType, StoragePoolType.Filesystem);
    }
}
