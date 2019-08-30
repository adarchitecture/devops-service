package io.choerodon.devops.app.service.impl;

import java.io.File;
import java.util.List;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.alibaba.fastjson.JSONObject;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import io.choerodon.devops.api.vo.UserAttrVO;
import io.choerodon.devops.app.eventhandler.payload.ApplicationEventPayload;
import io.choerodon.devops.app.eventhandler.payload.DevOpsAppServicePayload;
import io.choerodon.devops.app.service.AppServiceService;
import io.choerodon.devops.app.service.ApplicationService;
import io.choerodon.devops.app.service.GitlabGroupService;
import io.choerodon.devops.app.service.UserAttrService;
import io.choerodon.devops.infra.dto.AppServiceDTO;
import io.choerodon.devops.infra.dto.AppServiceVersionDTO;
import io.choerodon.devops.infra.dto.DevopsProjectDTO;
import io.choerodon.devops.infra.dto.gitlab.GitlabProjectDTO;
import io.choerodon.devops.infra.feign.operator.GitlabServiceClientOperator;
import io.choerodon.devops.infra.mapper.AppServiceMapper;
import io.choerodon.devops.infra.mapper.AppServiceVersionMapper;
import io.choerodon.devops.infra.mapper.DevopsConfigMapper;
import io.choerodon.devops.infra.mapper.DevopsProjectMapper;
import io.choerodon.devops.infra.util.*;

/**
 * @author zmf
 */
@Service
public class ApplicationServiceImpl implements ApplicationService {
    private final Logger logger = LoggerFactory.getLogger(ApplicationServiceImpl.class);

    @Autowired
    private GitlabGroupService gitlabGroupService;
    @Autowired
    private AppServiceService appServiceService;
    @Autowired
    private GitUtil gitUtil;
    @Autowired
    private UserAttrService userAttrService;
    @Autowired
    private GitlabServiceClientOperator gitlabServiceClientOperator;
    @Autowired
    private DevopsConfigMapper devopsConfigMapper;
    @Autowired
    private DevopsProjectMapper devopsProjectMapper;
    @Autowired
    private AppServiceMapper appServiceMapper;
    @Autowired
    private AppServiceVersionMapper appServiceVersionMapper;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @Override
    public void handleApplicationCreation(ApplicationEventPayload payload) {
        logger.info("Handle creation of application, msg: {}", payload);

        // 创建gitlab应用组
        gitlabGroupService.createApplicationGroup(payload);

        // sourceId不为空说明选择了应用作为模板
        if (payload.getSourceId() != null) {
            if (payload.getServiceVersionIds() == null) {
                // 以组织下的应用作为模板
                copyServicesFromProjectApp(payload.getSourceId(), payload.getId(), payload.getProjectId());
            } else {
                // 以平台上下载的应用作为模板
                copyServicesFromSiteApp(payload.getSourceId(), payload.getId(), payload.getProjectId(), payload.getServiceVersionIds());
            }
        }
    }

    /**
     * 将原有组织层应用的服务拷贝到新的服务下
     *
     * @param originAppId 原服务ID（模板应用ID）
     * @param newAppId    新建应用ID
     * @param projectId   项目id
     */
    private void copyServicesFromProjectApp(Long originAppId, Long newAppId, Long projectId) {
        // 查询原应用下的所有服务
        AppServiceDTO search = new AppServiceDTO();
        search.setAppId(originAppId);
        List<AppServiceDTO> originalAppServices = appServiceMapper.select(search);

        // 复制所有服务
        originalAppServices.forEach(service -> {
            final String workingDir = gitUtil.getWorkingDirectory("application-service-copy-" + GenerateUUID.generateUUID());
            try {
                copyAppService(newAppId, service, projectId, workingDir, null);
            } catch (Exception e) {
                FileUtil.deleteDirectory(new File(workingDir));
                logger.warn("Failed to create application service from original application service with code '{}' and id {}", service.getCode(), service.getId());
                logger.warn("The exception is: ", e);
            }
        });
    }

    /**
     * 将原有平台应用的服务拷贝到新的服务下
     *
     * @param originAppId       原服务ID（模板应用ID）
     * @param newAppId          新建应用ID
     * @param projectId         项目id
     * @param serviceVersionIds 当前作为模板的应用版本的服务的版本ID
     */
    private void copyServicesFromSiteApp(Long originAppId, Long newAppId, Long projectId, Set<Long> serviceVersionIds) {
        // 查询对应应用服务版本id的所有应用服务
        serviceVersionIds.forEach(id -> {
            AppServiceVersionDTO version = appServiceVersionMapper.selectByPrimaryKey(id);
            if (version == null) {
                logger.info("No service version with id {} exists. Creation from it canceled. The original application id is {}", id, originAppId);
                return;
            }
            AppServiceDTO service = appServiceMapper.selectByPrimaryKey(version.getAppServiceId());
            if (service == null) {
                logger.info("The corresponding app service can't be found of version id {}. The original application id is {}", id, originAppId);
                return;
            }

            final String workingDir = gitUtil.getWorkingDirectory("application-service-copy-" + GenerateUUID.generateUUID());
            logger.info("Working dir is: {}", workingDir);
            try {
                copyAppService(newAppId, service, projectId, workingDir, version.getCommit());
            } catch (Exception e) {
                FileUtil.deleteDirectory(new File(workingDir));
                logger.warn("Failed to create application service from original application service with code '{}' and id {}", service.getCode(), service.getId());
                logger.warn("The exception is: {}", e);
            }
        });
    }

    /**
     * 拷贝一个服务到新的应用下，会发送saga消息
     *
     * @param newAppId           新的应用id
     * @param originalAppService 原有的应用下某个服务的信息
     * @param projectId          项目ID
     * @param workingDir         本地仓库的目录
     * @param resetCommitSha     在拉取原有代码到本地后，是否切换到指定的commit
     */
    private void copyAppService(
            @Nonnull final Long newAppId,
            @Nonnull final AppServiceDTO originalAppService,
            @Nonnull final Long projectId,
            @Nonnull final String workingDir,
            @Nullable final String resetCommitSha) {
        // 不为失败的或者处理中的原应用服务创建新应用服务
        if (Boolean.TRUE.equals(originalAppService.getFailed()) || !Boolean.TRUE.equals(originalAppService.getSynchro())) {
            return;
        }

        final AppServiceDTO newAppService = createDatabaseRecord(newAppId, originalAppService);
        logger.info("New app service is {}", JSONObject.toJSONString(newAppService));
        final UserAttrVO userAttrVO = userAttrService.queryByUserId(TypeUtil.objToLong(GitUserNameUtil.getUserId()));
        logger.info("User attribute vo is {}", JSONObject.toJSONString(userAttrVO));
        DevopsProjectDTO devopsProjectDTO = devopsProjectMapper.selectByPrimaryKey(newAppId);
        logger.info("Project dto is {}", JSONObject.toJSONString(devopsProjectDTO));

        //创建saga payload
        final DevOpsAppServicePayload payload = createPayload(newAppService, TypeUtil.objToInteger(userAttrVO.getGitlabUserId()), TypeUtil.objToInteger(devopsProjectDTO.getDevopsAppGroupId()), projectId);
        logger.info("Payload is {}", JSONObject.toJSONString(payload));

        // 创建服务对应的代码仓库
        try {
            appServiceService.operationApplication(payload);
        } catch (Exception e) {
            AppServiceDTO appServiceDTO = appServiceService.baseQuery(payload.getAppServiceId());
            appServiceDTO.setFailed(true);
            appServiceService.baseUpdate(appServiceDTO);
            logger.warn("Failed to create application service with id {}", appServiceDTO.getId());
            logger.warn("The exception is: {}", e);
            return;
        }

        // 创建拉取原有代码仓库代码的token
        final String originalAccessToken = gitlabServiceClientOperator.createProjectToken(originalAppService.getGitlabProjectId(), TypeUtil.objToInteger(userAttrVO.getGitlabUserId()));
        logger.info("OriginalAccessToken is {}", originalAccessToken);

        // 创建token失败
        if (originalAccessToken == null) {
            logger.warn("Failed to create access token for gitlab repository of the original repository for the new application service with id: {}", newAppService.getId());
            return;
        }

        // 将原先服务的MASTER分支最新代码克隆到本地
        final File workingDirFile = new File(workingDir);
        logger.info("File is: {}", workingDirFile.getAbsolutePath());

        GitlabProjectDTO originalGitlabProjectDTO = gitlabServiceClientOperator.queryProjectById(originalAppService.getGitlabProjectId());
        logger.info("The gitlab project dto is: {}", JSONObject.toJSONString(originalGitlabProjectDTO));

        Git localOriginalRepo = gitUtil.cloneRepository(workingDir, originalGitlabProjectDTO.getHttpUrlToRepo(), originalAccessToken, "master");
        logger.info("After cloning");

        // 切换到指定commit
        if (!StringUtils.isEmpty(resetCommitSha)) {
            try {
                localOriginalRepo.reset().setMode(ResetCommand.ResetType.HARD).setRef(resetCommitSha).call();
            } catch (GitAPIException e) {
                logger.warn("Failed to checkout to the certain commit of service with id {}, the commit sha is {}", originalAppService.getId(), resetCommitSha);
                logger.warn("The exception is: ", e);
                FileUtil.deleteDirectory(workingDirFile);
            }
        }


        // 删除所有commits纪录
        logger.info("Delete commits");
        File gitDir = new File(workingDir, ".git");
        if (gitDir.exists() && gitDir.isDirectory()) {
            FileUtil.deleteDirectory(gitDir);
        }

        // 本地初始化提交
        logger.info("Initialize local commit");
        Git git;
        try {
            git = Git.init().setGitDir(workingDirFile).call();
            git.add().addFilepattern(".").call();
            git.commit().setMessage("[ADD] initial commit").call();
        } catch (GitAPIException e) {
            logger.warn("Failed to operate local git repository for application service with id: {}", newAppService.getId());
            logger.warn("The exception is: ", e);
            FileUtil.deleteDirectory(workingDirFile);
            return;
        }

        final AppServiceDTO appServiceDTO = appServiceService.baseQuery(payload.getAppServiceId());
        logger.info("Query app service: {}", JSONObject.toJSONString(appServiceDTO));
        final GitlabProjectDTO newGitProject = gitlabServiceClientOperator.queryProjectById(originalAppService.getGitlabProjectId());
        logger.info("New gitlab project: {}", JSONObject.toJSONString(newGitProject));

        // 创建推送代码token
        final String newAccessToken = gitlabServiceClientOperator.createProjectToken(appServiceDTO.getGitlabProjectId(), TypeUtil.objToInteger(userAttrVO.getGitlabUserId()));
        logger.info("New Access Token: ", newAccessToken);

        // 创建token失败
        if (newAccessToken == null) {
            logger.warn("Failed to create access token for gitlab repository of the new application service with id: {}", appServiceDTO.getId());
            FileUtil.deleteDirectory(workingDirFile);
            return;
        }

        logger.info("To push");
        try {
            git.push().setRemote(newGitProject.getHttpUrlToRepo())
                    .setPushAll()
                    .setCredentialsProvider(new UsernamePasswordCredentialsProvider("", newAccessToken))
                    .call();
        } catch (GitAPIException e) {
            logger.warn("Failed to push local git repository to the remote repository of application service with id: {}", newAppService.getId());
            logger.warn("The exception is: ", e);
            FileUtil.deleteDirectory(workingDirFile);
            return;
        }

        // 清理本地目录
        logger.info("delete local directory: {}", workingDirFile.getAbsolutePath());
        FileUtil.deleteDirectory(workingDirFile);

        // 发送消息通知
        logger.info("Send saga");
        appServiceService.sendCreateAppServiceInfo(newAppService, projectId);
    }

    /**
     * 根据应用服务信息创建payload
     *
     * @param newAppService 应用服务信息
     * @param gitlabUserId  gitlab用户id
     * @param groupId       组id
     * @param projectId     项目ID
     * @return payload
     */
    private DevOpsAppServicePayload createPayload(AppServiceDTO newAppService, Integer gitlabUserId, Integer groupId, Long projectId) {
        DevOpsAppServicePayload devOpsAppServicePayload = new DevOpsAppServicePayload();
        devOpsAppServicePayload.setPath(newAppService.getCode());
        devOpsAppServicePayload.setUserId(gitlabUserId);
        devOpsAppServicePayload.setGroupId(groupId);
        devOpsAppServicePayload.setSkipCheckPermission(newAppService.getSkipCheckPermission());
        devOpsAppServicePayload.setAppServiceId(newAppService.getId());
        devOpsAppServicePayload.setIamProjectId(projectId);
        return devOpsAppServicePayload;
    }

    /**
     * 在数据库中新增一条记录
     *
     * @param newAppId           应用id
     * @param originalAppService 原来的服务id
     * @return 新纪录的id
     */
    private AppServiceDTO createDatabaseRecord(Long newAppId, AppServiceDTO originalAppService) {
        // 在新的APP中生成一条对应的服务记录
        AppServiceDTO appServiceDTO = new AppServiceDTO();
        appServiceDTO.setCode(originalAppService.getCode());
        appServiceDTO.setAppId(newAppId);

        // 便于saga重试
        AppServiceDTO record;
        if ((record = appServiceMapper.selectOne(appServiceDTO)) != null) {
            return record;
        }

        appServiceDTO.setName(originalAppService.getName());
        appServiceDTO.setDescription(originalAppService.getDescription());
        appServiceDTO.setType(originalAppService.getType());
        appServiceDTO.setActive(true);
        appServiceDTO.setSynchro(false);
        appServiceDTO.setSkipCheckPermission(true);
        appServiceDTO.setHarborConfigId(devopsConfigMapper.queryDefaultConfig("harbor").getId());
        appServiceDTO.setChartConfigId(devopsConfigMapper.queryDefaultConfig("chart").getId());

        return appServiceService.baseCreate(appServiceDTO);
    }
}
