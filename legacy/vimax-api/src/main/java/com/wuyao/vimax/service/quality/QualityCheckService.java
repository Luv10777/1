package com.wuyao.vimax.service.quality;

import com.wuyao.vimax.entity.GenerationTask;
import com.wuyao.vimax.repository.GenerationTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 质量检查服务
 *
 * Phase 6: 技术质量检查、质量评分、问题诊断
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class QualityCheckService {

    private final GenerationTaskRepository taskRepository;

    /**
     * 执行质量检查
     */
    public QualityCheckResult performQualityCheck(Long taskId) {
        log.info("执行质量检查: taskId={}", taskId);

        GenerationTask task = taskRepository.findById(taskId)
            .orElseThrow(() -> new IllegalArgumentException("任务不存在"));

        QualityCheckResult result = new QualityCheckResult();
        result.setTaskId(taskId);
        result.setTaskType(task.getTaskType());

        switch (task.getTaskType()) {
            case "TEXT" -> checkTextQuality(task, result);
            case "IMAGE" -> checkImageQuality(task, result);
            case "VIDEO" -> checkVideoQuality(task, result);
        }

        log.info("质量检查完成: taskId={}, score={}, passed={}",
            taskId, result.getQualityScore(), result.isPassed());

        return result;
    }

    /**
     * 文本质量检查
     */
    private void checkTextQuality(GenerationTask task, QualityCheckResult result) {
        String text = task.getResultRef();
        int score = 100;

        // 检查文本长度
        if (text == null || text.length() < 10) {
            score -= 50;
            result.addIssue("TEXT_TOO_SHORT", "文本内容过短");
        } else if (text.length() > 2000) {
            score -= 20;
            result.addIssue("TEXT_TOO_LONG", "文本内容过长");
        }

        // TODO: 更多检查
        // - 敏感词检查
        // - 格式规范检查
        // - 逻辑完整性检查

        result.setQualityScore(score);
        result.setPassed(score >= 60);
    }

    /**
     * 图片质量检查
     */
    private void checkImageQuality(GenerationTask task, QualityCheckResult result) {
        String imageUrl = task.getResultRef();
        int score = 100;

        // 检查URL有效性
        if (imageUrl == null || !imageUrl.startsWith("http")) {
            score -= 100;
            result.addIssue("INVALID_URL", "无效的图片URL");
        }

        // TODO: 更多检查
        // - 图片尺寸检查
        // - 图片格式检查
        // - NSFW检查
        // - 水印检测

        result.setQualityScore(score);
        result.setPassed(score >= 60);
    }

    /**
     * 视频质量检查
     */
    private void checkVideoQuality(GenerationTask task, QualityCheckResult result) {
        String videoUrl = task.getResultRef();
        int score = 100;

        // 检查URL有效性
        if (videoUrl == null || !videoUrl.startsWith("http")) {
            score -= 100;
            result.addIssue("INVALID_URL", "无效的视频URL");
        }

        // TODO: 更多检查
        // - 视频时长检查
        // - 视频分辨率检查
        // - 视频格式检查
        // - 帧率检查
        // - 音频检查

        result.setQualityScore(score);
        result.setPassed(score >= 60);
    }

    /**
     * 质量检查结果
     */
    public static class QualityCheckResult {
        private Long taskId;
        private String taskType;
        private int qualityScore;
        private boolean passed;
        private Map<String, String> issues = new HashMap<>();

        public void addIssue(String code, String description) {
            issues.put(code, description);
        }

        // Getters and Setters
        public Long getTaskId() { return taskId; }
        public void setTaskId(Long taskId) { this.taskId = taskId; }
        public String getTaskType() { return taskType; }
        public void setTaskType(String taskType) { this.taskType = taskType; }
        public int getQualityScore() { return qualityScore; }
        public void setQualityScore(int qualityScore) { this.qualityScore = qualityScore; }
        public boolean isPassed() { return passed; }
        public void setPassed(boolean passed) { this.passed = passed; }
        public Map<String, String> getIssues() { return issues; }
        public void setIssues(Map<String, String> issues) { this.issues = issues; }
    }
}
