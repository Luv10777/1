package com.wuyao.growth.asset;

import com.wuyao.growth.common.web.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class PublicAssetController {
    private final AssetService assetService;

    @GetMapping("/api/public/assets/{id}/download-url")
    public ApiResponse<AssetDtos.DownloadTicket> publicProductImageUrl(@PathVariable Long id) {
        return ApiResponse.ok(assetService.presignPublicProductImage(id));
    }
}
