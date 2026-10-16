package com.wuyao.growth.voice;

import com.wuyao.growth.common.task.TaskService;
import com.wuyao.growth.iam.service.AccountClosureGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 声音样本是人声，商户关闭时一起删掉。注销事务里只把样本撤下、排好删除任务；
 * 删文件和远端音色是外部操作，交给任务在事务外执行，失败会重试。
 */
@Component
@RequiredArgsConstructor
public class VoiceAccountClosure implements AccountClosureGuard {
    private final VoiceSampleService samples;
    private final VoiceSampleRepository repository;
    private final TaskService tasks;

    @Override
    public List<String> losses(Closure closure) {
        if (!closure.wholeTenant()) return List.of();
        long count = repository.countByTenantIdAndStatusNot(closure.tenantId(), "DELETED");
        return count == 0 ? List.of() : List.of(count + " 个声音样本和克隆出的音色会被删除");
    }

    @Override
    public void closed(Closure closure) {
        if (!closure.wholeTenant()) return;
        for (Long sampleId : samples.withdrawAllForClosure(closure.tenantId())) {
            tasks.submit(VoiceSamplePurgeHandler.TYPE, "DEFAULT", Map.of("sampleId", sampleId),
                    "voice-sample-purge:" + sampleId, null);
        }
    }
}
