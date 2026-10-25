package com.wuyao.growth.billing;

import com.wuyao.growth.common.web.PageResult;
import java.time.Instant;
import java.util.List;

public final class TaskUsageDtos {
    private TaskUsageDtos() {}
    public record Filter(Instant from, Instant until, String module, String model, String status, int page, int size) {}
    public record Log(String id, Long taskId, String module, String workflow, String title, String model,
                      String status, Instant createdAt, int quantity, int succeeded, int failed, int cancelled,
                      int pending, String unit, Long chargedCredits, long heldCredits, long releasedCredits,
                      String resolution, Integer durationSeconds, String error, Long elapsedMillis, Long previewElapsedMillis) {}
    public record Summary(long tasks, long chargedCredits, long heldCredits, long releasedCredits,
                          long succeeded, long failed) {}
    public record Logs(PageResult<Log> records, Summary summary) {}
    public record Item(Long id, int ordinal, String title, String model, String status, Long chargedCredits,
                       boolean reused, String error) {}
    public record Event(Long id, String type, long credits, String description, Instant createdAt) {}
    public record Detail(Log task, List<Item> items, List<Event> events) {}
}
