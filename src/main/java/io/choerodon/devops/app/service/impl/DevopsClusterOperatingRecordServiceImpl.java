package io.choerodon.devops.app.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

import io.choerodon.devops.app.service.DevopsClusterOperatingRecordService;
import io.choerodon.devops.infra.constant.ClusterCheckConstant;
import io.choerodon.devops.infra.dto.DevopsClusterOperationRecordDTO;
import io.choerodon.devops.infra.mapper.DevopsClusterOperationRecordMapper;

/**
 * 〈功能简述〉
 * 〈〉
 *
 * @author wanghao
 * @since 2020/10/27 17:50
 */
@Service
public class DevopsClusterOperatingRecordServiceImpl implements DevopsClusterOperatingRecordService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DevopsClusterOperatingRecordServiceImpl.class);
    @Autowired
    private DevopsClusterOperationRecordMapper devopsClusterOperationRecordMapper;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveOperatingRecord(Long clusterId, Long nodeId, String operatingType, String status, String errorMsg) {
        try {
            DevopsClusterOperationRecordDTO devopsClusterOperationRecordDTO = new DevopsClusterOperationRecordDTO();
            devopsClusterOperationRecordDTO.setClusterId(clusterId);
            devopsClusterOperationRecordDTO.setNodeId(nodeId);
            devopsClusterOperationRecordDTO.setType(operatingType);
            devopsClusterOperationRecordDTO.setStatus(status);
            devopsClusterOperationRecordDTO.setErrorMsg(errorMsg);
            devopsClusterOperationRecordMapper.insert(devopsClusterOperationRecordDTO);
        } catch (Exception e) {
            LOGGER.info("save cluster operating record failed.", e);
        }
    }

    @Override
    public DevopsClusterOperationRecordDTO queryLatestRecordByNodeId(Long nodeId) {
        Assert.notNull(nodeId, ClusterCheckConstant.ERROR_NODE_ID_IS_NULL);

        return devopsClusterOperationRecordMapper.queryLatestRecordByNodeId(nodeId);
    }
}
