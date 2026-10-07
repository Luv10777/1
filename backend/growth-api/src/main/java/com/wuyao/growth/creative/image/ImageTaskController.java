package com.wuyao.growth.creative.image;

import com.wuyao.growth.common.security.AuthPrincipal;
import com.wuyao.growth.common.web.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import java.util.Set;

/**
 * Small local-task facade for clients that do not need the full creation
 * workflow response. The local task id is the durable image creation id.
 */
@RestController
@RequiredArgsConstructor
public class ImageTaskController {
    private final ImageCreationService service;

    @PostMapping("/api/generate")
    public ApiResponse<GenerateResponse> generate(@Valid @RequestBody ImageDtos.Create request,
                                                  @AuthenticationPrincipal AuthPrincipal me) {
        var view = service.create(request, me.userId());
        return ApiResponse.ok(response(view));
    }

    @GetMapping("/api/tasks/{localTaskId}")
    public ApiResponse<GenerateResponse> status(@PathVariable Long localTaskId) {
        return ApiResponse.ok(response(service.get(localTaskId)));
    }

    private GenerateResponse response(ImageDtos.View view) {
        String url = view.items().stream()
            .map(ImageDtos.ItemView::url)
            .filter(value -> value != null && !value.isBlank())
            .findFirst().orElse(null);
        String status = "SUCCEEDED".equals(view.status()) ? "SUCCESS"
            : Set.of("FAILED", "CANCELLED", "UPSTREAM_UNKNOWN").contains(view.status())
                ? "FAILED" : "PROCESSING";
        return new GenerateResponse(String.valueOf(view.id()), status, url, view.error());
    }

    public record GenerateResponse(String localTaskId, String status, String imageUrl, String error) { }
}
