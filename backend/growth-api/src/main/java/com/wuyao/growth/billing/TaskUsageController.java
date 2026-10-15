package com.wuyao.growth.billing;

import com.wuyao.growth.common.web.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;

@RestController
@RequestMapping("/api/task-center/logs")
@RequiredArgsConstructor
public class TaskUsageController {
    private final TaskUsageService service;
    @GetMapping
    public ApiResponse<TaskUsageDtos.Logs> logs(@RequestParam(required=false) Instant from,
            @RequestParam(required=false) Instant until, @RequestParam(required=false) String module,
            @RequestParam(required=false) String model, @RequestParam(required=false) String status,
            @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size) {
        return ApiResponse.ok(service.logs(new TaskUsageDtos.Filter(from,until,module,model,status,page,size)));
    }
    @GetMapping("/{id}")
    public ApiResponse<TaskUsageDtos.Detail> detail(@PathVariable String id) { return ApiResponse.ok(service.detail(id)); }
}
