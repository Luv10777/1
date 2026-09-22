package com.wuyao.growth.asset;

import com.wuyao.growth.common.security.AuthPrincipal;
import com.wuyao.growth.common.web.ApiResponse;
import com.wuyao.growth.common.web.PageResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * 示例控制器。注意三点，其他模块照做：
 *   1. 方法签名里没有 tenantId / merchantId —— 租户只从 token 来
 *   2. 返回一律 ApiResponse / PageResult
 *   3. 不写 try-catch，异常交给 GlobalExceptionHandler
 */
@RestController
@RequestMapping("/api/assets")
@RequiredArgsConstructor
public class AssetController {

    private final AssetService assetService;

    @PostMapping("/upload-url")
    public ApiResponse<AssetDtos.UploadTicket> presign(@Valid @RequestBody AssetDtos.PresignRequest req,
                                                       @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(assetService.presignUpload(req, me.userId()));
    }

    @PostMapping("/{id}/confirm")
    public ApiResponse<AssetDtos.AssetView> confirm(@PathVariable Long id,
                                                    @Valid @RequestBody AssetDtos.ConfirmRequest req,
                                                    @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(assetService.confirmUpload(id, req, me.userId()));
    }

    @GetMapping
    public ApiResponse<PageResult<AssetDtos.AssetView>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(assetService.list(page, size));
    }
}
