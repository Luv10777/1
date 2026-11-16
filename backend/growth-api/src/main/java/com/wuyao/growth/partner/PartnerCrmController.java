package com.wuyao.growth.partner;

import com.wuyao.growth.common.security.AuthPrincipal;
import com.wuyao.growth.common.web.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController @RequestMapping("/api/partner") @RequiredArgsConstructor
public class PartnerCrmController {
    private final PartnerCrmService service;
    @GetMapping("/tenants/{tenantId}/crm") public ApiResponse<PartnerCrmDtos.Crm> crm(@AuthenticationPrincipal AuthPrincipal me,@PathVariable Long tenantId,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) { return ApiResponse.ok(service.crm(me.userId(),tenantId,page,size)); }
    @PutMapping("/tenants/{tenantId}/profile") public ApiResponse<PartnerCrmDtos.Profile> profile(@AuthenticationPrincipal AuthPrincipal me,@PathVariable Long tenantId,
            @Valid @RequestBody PartnerCrmDtos.SaveProfile input) { return ApiResponse.ok(service.saveProfile(me.userId(),tenantId,input)); }
    @PostMapping("/tenants/{tenantId}/follow-ups") public ApiResponse<PartnerCrmDtos.Crm> followUp(@AuthenticationPrincipal AuthPrincipal me,@PathVariable Long tenantId,
            @Valid @RequestBody PartnerCrmDtos.NewFollowUp input) { return ApiResponse.ok(service.followUp(me.userId(),tenantId,input)); }
    @GetMapping("/follow-ups/due") public ApiResponse<List<PartnerCrmDtos.Due>> due(@AuthenticationPrincipal AuthPrincipal me) { return ApiResponse.ok(service.due(me.userId())); }
}
