package com.wuyao.vimax.service.workflow;

import com.wuyao.vimax.entity.WorkflowRun;
import com.wuyao.vimax.entity.VideoProject;
import com.wuyao.vimax.repository.WorkflowRunRepository;
import com.wuyao.vimax.repository.VideoProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 工作流引擎服务
 *
 * TODO: 需要完整实现工作流编排逻辑
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkflowEngineService {

    private final VideoProjectRepository videoProjectRepository;
    private final WorkflowRunRepository workflowRunRepository;

    /**
     * 启动工作流
     */
    @Transactional
    public WorkflowRun startWorkflow(Long videoProjectId) {
        log.info("启动工作流：videoProjectId={}", videoProjectId);

        // 查询视频项目
        VideoProject project = videoProjectRepository.findById(videoProjectId)
                .orElseThrow(() -> new IllegalArgumentException("视频项目不存在: " + videoProjectId));

        // 检查项目状态
        if (!"DRAFT".equals(project.getStatus())) {
            throw new IllegalStateException("项目状态不允许启动工作流: " + project.getStatus());
        }

        // 创建工作流运行记录
        WorkflowRun run = new WorkflowRun();
        run.setProjectId(videoProjectId);
        run.setRunCode(UUID.randomUUID().toString().replace("-", ""));
        run.setWorkflowType("IDEA2VIDEO");
        run.setState("RUNNING");
        run.setStartedAt(LocalDateTime.now());

        WorkflowRun saved = workflowRunRepository.save(run);

        // 更新项目状态
        project.setStatus("VALIDATING");
        videoProjectRepository.save(project);

        log.info("工作流已启动：runCode={}", saved.getRunCode());

        return saved;
    }

    /**
     * 查询工作流运行状态
     */
    public WorkflowRun getWorkflowRun(Long runId) {
        return workflowRunRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("工作流运行不存在: " + runId));
    }
}
