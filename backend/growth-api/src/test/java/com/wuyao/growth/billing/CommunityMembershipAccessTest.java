package com.wuyao.growth.billing;

import com.wuyao.growth.commerce.CommerceController;
import com.wuyao.growth.commerce.CommerceCheckoutService;
import com.wuyao.growth.commerce.CommerceService;
import com.wuyao.growth.common.config.SecurityConfig;
import com.wuyao.growth.common.config.WebConfig;
import com.wuyao.growth.common.security.AuthPrincipal;
import com.wuyao.growth.common.security.JwtAuthFilter;
import com.wuyao.growth.common.security.JwtService;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import com.wuyao.growth.community.CommunityController;
import com.wuyao.growth.community.CommunityService;
import com.wuyao.growth.iam.entity.User;
import com.wuyao.growth.iam.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {CommunityController.class, CommerceController.class})
@Import({WebConfig.class, SecurityConfig.class, PlatformMembershipInterceptor.class, JwtAuthFilter.class})
class CommunityMembershipAccessTest {
    private static final String INFORMATION = """
            {"category":"notice","title":"营业通知","merchantName":"测试商家","region":"嘉兴",
             "content":"欢迎到店","declarationAccepted":true}
            """;
    private static final String ORDER = """
            {"items":[{"productId":2,"quantity":1}],"buyerName":"张三",
             "buyerPhone":"13800000001","shippingAddress":"嘉兴市测试地址"}
            """;

    @Autowired MockMvc mvc;
    @MockitoBean BillingService billing;
    @MockitoBean CommunityService community;
    @MockitoBean CommerceService commerce;
    @MockitoBean CommerceCheckoutService checkout;
    @MockitoBean JwtService jwt;
    @MockitoBean UserRepository users;
    @MockitoBean com.wuyao.growth.commerce.PublicOrderRateLimiter orderLimits;
    @MockitoBean com.wuyao.growth.common.security.ClientIpResolver clientIps;

    @BeforeEach
    void nonmember() {
        doThrow(BizException.of(ErrorCode.MEMBERSHIP_REQUIRED, "您还未开通会员"))
                .when(billing).requirePlatformMembership();
        when(jwt.parse("nonmember-token", "access"))
                .thenReturn(new AuthPrincipal(1L, 7L, "13800000001", 0));
        var user = new User();
        user.setId(1L);
        user.setTenantId(7L);
        when(users.findById(1L)).thenReturn(Optional.of(user));
    }

    @Test
    void nonmembersCanPublishEditAndTakeTheirInformationOffline() throws Exception {
        mvc.perform(post("/api/information-publications").header("Authorization", "Bearer nonmember-token")
                        .contentType("application/json").content(INFORMATION))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
        mvc.perform(put("/api/information-publications/8").header("Authorization", "Bearer nonmember-token")
                        .contentType("application/json").content(INFORMATION))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
        mvc.perform(delete("/api/information-publications/8").header("Authorization", "Bearer nonmember-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));

        verify(community).publish(any(), eq(1L));
        verify(community).update(eq(8L), any());
        verify(community).takeOffline(8L);
        verifyNoInteractions(billing);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void guestsAndNonmembersCanPlaceOrders(boolean signedIn) throws Exception {
        var request = post("/api/public/orders").contentType("application/json").content(ORDER);
        if (signedIn) request.header("Authorization", "Bearer nonmember-token");

        mvc.perform(request).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));

        verify(commerce).place(any());
        verifyNoInteractions(billing);
    }

    @Test
    void publishingAndManagingInformationStillRequireLogin() throws Exception {
        for (var request : java.util.List.of(post("/api/information-publications"),
                put("/api/information-publications/8"), delete("/api/information-publications/8"))) {
            mvc.perform(request.contentType("application/json").content(INFORMATION))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(1401));
        }
        verifyNoInteractions(community, billing);
    }

    @Test
    void informationAndOrdersStillValidateSubmittedFields() throws Exception {
        mvc.perform(post("/api/information-publications").header("Authorization", "Bearer nonmember-token")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(1400));
        mvc.perform(post("/api/public/orders").contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(1400));
        verifyNoInteractions(community, commerce, billing);
    }

    @Test
    void businessWritesOutsideLiveStreamingDoNotRequireMembership() throws Exception {
        // 直播接口要会员由 FoundationIntegrationTest 覆盖；这里只加载了社区和商城两个控制器。
        mvc.perform(post("/api/merchant-alliance/applications").header("Authorization", "Bearer nonmember-token")
                        .contentType("application/json").content("{}"))
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not(9001)));
        verify(billing, never()).requirePlatformMembership();
    }
}
