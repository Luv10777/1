package com.wuyao.vimax.service;

import com.wuyao.vimax.entity.VideoProject;
import com.wuyao.vimax.repository.VideoProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 视频项目服务
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VideoProjectService {

    private final VideoProjectRepository videoProjectRepository;

    /**
     * 创建视频项目
     */
    @Transactional
    public VideoProject createProject(Long merchantId, String projectName,
                                     String brief, Long userId) {
        log.info("创建视频项目: merchantId={}, name={}", merchantId, projectName);

        VideoProject project = new VideoProject();
        project.setMerchantId(merchantId);
        project.setProjectCode(generateProjectCode());
        project.setName(projectName);
        project.setBrief(brief);
        project.setStatus("DRAFT");
        project.setCreatedBy(userId);

        VideoProject saved = videoProjectRepository.save(project);
        log.info("视频项目已创建: projectId={}, code={}", saved.getId(), saved.getProjectCode());

        return saved;
    }

    /**
     * 查询项目列表
     */
    public List<VideoProject> listProjects(Long merchantId) {
        return videoProjectRepository.findByMerchantIdOrderByCreatedAtDesc(merchantId);
    }

    /**
     * 生成项目代码
     */
    private String generateProjectCode() {
        return "VP" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
    }
}
