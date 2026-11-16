package com.wuyao.growth.video.plan;

import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** 秒数与活动持续天数分开；明确写出的成片时长优先于工作台的默认值。 */
final class VideoPlanConstraints {
    private static final Pattern SECONDS = Pattern.compile("(?i)(?<![\\d.])(\\d+(?:\\.\\d+)?)\\s*(?:秒(?:钟)?|s(?:ec(?:onds?)?)?)(?![a-z])");
    private static final Pattern TOTAL_SECONDS = Pattern.compile("(?i)(?:总时长|成片时长|全长|时长|总共|共|生成|制作)[^\\d\\n]{0,12}(\\d+(?:\\.\\d+)?)\\s*(?:秒(?:钟)?|s(?:ec(?:onds?)?)?)(?![a-z])");
    private VideoPlanConstraints() {}

    static Integer explicitDuration(String idea) {
        if (idea == null) return null;
        var match = TOTAL_SECONDS.matcher(idea);
        if (!match.find()) { match = SECONDS.matcher(idea); if (!match.find()) return null; }
        double value = Double.parseDouble(match.group(1));
        if (value != Math.rint(value) || value < 5 || value > 30)
            throw BizException.of(ErrorCode.BAD_REQUEST, "视频方案时长须为 5 到 30 秒的整数，请调整成片时长");
        return (int) value;
    }

    static Map<String, Object> resolve(VideoPlanDtos.Create request) {
        var settings = new LinkedHashMap<String, Object>();
        Integer seconds = explicitDuration(request.idea());
        if (seconds == null) seconds = explicitDuration(request.sourceBrief());
        if (seconds == null) seconds = request.durationSeconds();
        int max = request.maxDurationSeconds() == null ? 30 : request.maxDurationSeconds();
        if (seconds != null && (seconds < 5 || seconds > max))
            throw BizException.of(ErrorCode.BAD_REQUEST, "你要求 " + seconds + " 秒，当前模型最长支持 " + max + " 秒；请先切换支持该时长的模型，不会自动缩短方案");
        if (seconds != null) settings.put("durationSeconds", seconds);
        if (request.maxDurationSeconds() != null) settings.put("maxDurationSeconds", max);
        if (request.ratio() != null && !"auto".equals(request.ratio())) settings.put("ratio", request.ratio());
        if (request.sourceBrief() != null && !request.sourceBrief().isBlank()) settings.put("sourceBrief", request.sourceBrief().strip());
        if (request.clarificationAnswer() != null && !request.clarificationAnswer().isBlank()) settings.put("clarificationAnswer", request.clarificationAnswer().strip());
        return settings;
    }
}
