package com.wuyao.growth.alliance;

import com.wuyao.growth.common.security.AuthPrincipal;
import com.wuyao.growth.common.web.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 门店店员核销联盟券。门店权限由后端按登录者校验。 */
@RestController
@RequestMapping("/api/stores/{storeId}/alliance-redemptions")
@RequiredArgsConstructor
public class AllianceRedemptionController {
    private final AllianceRedemptionService redemptions;

    @PostMapping("/preview")
    public ApiResponse<AllianceDtos.RedemptionPreview> preview(@PathVariable Long storeId,
                                                              @Valid @RequestBody AllianceDtos.RedeemRequest request,
                                                              @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(redemptions.preview(storeId, request.code(), me.userId()));
    }

    @PostMapping
    public ApiResponse<AllianceDtos.RedemptionView> redeem(@PathVariable Long storeId,
                                                          @Valid @RequestBody AllianceDtos.RedeemRequest request,
                                                          @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(redemptions.redeem(storeId, request.code(), me.userId()));
    }

    @GetMapping
    public ApiResponse<List<AllianceDtos.RedemptionView>> records(@PathVariable Long storeId,
                                                                 @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(redemptions.records(storeId, me.userId()));
    }
}
