package com.wuyao.growth.alliance;

import com.wuyao.growth.common.web.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 联盟券规则对还没加入的商户也公开：规则页不登录也能看。 */
@RestController
@RequiredArgsConstructor
public class AlliancePublicController {
    private final AllianceProperties config;

    @GetMapping("/api/public/alliance/rules")
    public ApiResponse<AllianceDtos.Rules> rules() {
        return ApiResponse.ok(AllianceDtos.Rules.of(config));
    }
}
