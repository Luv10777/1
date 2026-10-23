package com.wuyao.growth.community;

import com.wuyao.growth.billing.BillingDtos;
import com.wuyao.growth.billing.BillingService;
import com.wuyao.growth.common.security.AuthPrincipal;
import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import com.wuyao.growth.iam.service.PlatformAccountDirectory;
import com.wuyao.growth.iam.service.PlatformAccountDirectory.PlatformUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PlatformUserServiceTest {
    private final AuthPrincipal operator = new AuthPrincipal(1L, 1L, "13800000001", 0);
    private final AuthPrincipal merchant = new AuthPrincipal(5L, 2L, "13800000005", 0);
    private final PlatformManagementService management = mock(PlatformManagementService.class);
    private final PlatformProperties config = new PlatformProperties();
    private final PlatformAccountDirectory directory = mock(PlatformAccountDirectory.class);
    private final PlatformManagementAuditRepository audits = mock(PlatformManagementAuditRepository.class);
    private final BillingService billing = mock(BillingService.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final PlatformUserService service = new PlatformUserService(management, config, directory, audits, billing, transactions);
    private final Instant now = Instant.now();

    private PlatformUser user(long id, long tenantId, String name, String status, Instant lastLogin) {
        return new PlatformUser(id, tenantId, name, "1380000" + (1000 + id), null, "OWNER", status, lastLogin, now.minus(Duration.ofDays(id)), null);
    }

    @BeforeEach
    void setup() {
        config.setOperatorUserIds(List.of(1L));
        when(transactions.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        doThrow(BizException.of(ErrorCode.FORBIDDEN, "没有平台运营权限")).when(management).requireOperator(merchant);
        when(directory.scopes()).thenReturn(List.of(new PlatformAccountDirectory.Scope(2L, "冰雪时光"), new PlatformAccountDirectory.Scope(3L, "一方小馆")));
        when(directory.users()).thenReturn(List.of(
                user(5, 2, "林知夏", "ACTIVE", now.minus(Duration.ofDays(1))),
                user(6, 2, "店员小周", "SUSPENDED", null),
                user(7, 3, "陈老板", "ACTIVE", now.minus(Duration.ofDays(30))),
                user(8, 3, "已移除店员", "REMOVED", null)));
        when(billing.summary(false, false)).thenAnswer(call -> new BillingDtos.Summary("烟火", 10, 3, 1, 35,
                TenantContext.require() * 100, 0, 0, 0, true, now.plus(Duration.ofDays(30)), null, false, false, null, null));
    }

    @Test
    void merchantsCannotListUsersAndNothingIsReadFirst() {
        assertThatThrownBy(() -> service.users(merchant, 0, 20, null, null, null))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        verifyNoInteractions(directory, billing);
    }

    @Test
    void listHidesRemovedAccountsByDefaultAndReadsMembershipOncePerTenantOnThePage() {
        var page = service.users(operator, 0, 20, null, null, null);
        assertThat(page.items()).extracting(PlatformDtos.UserView::id).containsExactly(5L, 6L, 7L);
        assertThat(page.total()).isEqualTo(3);
        assertThat(page.items().get(0).tenantName()).isEqualTo("冰雪时光");
        assertThat(page.items().get(0).phoneMasked()).doesNotContain("10005").contains("****");
        assertThat(page.items().get(0).creditsAvailable()).isEqualTo(200);
        assertThat(page.items().get(2).creditsAvailable()).isEqualTo(300);
        verify(billing, times(2)).summary(false, false);
        assertThat(TenantContext.get()).isNull();

        assertThat(service.users(operator, 0, 20, "REMOVED", null, null).items()).extracting(PlatformDtos.UserView::id).containsExactly(8L);
        assertThat(service.users(operator, 0, 20, null, null, "一方小馆").items()).extracting(PlatformDtos.UserView::id).containsExactly(7L);
        assertThatThrownBy(() -> service.users(operator, 0, 20, "DELETED", null, null)).isInstanceOf(BizException.class);
    }

    @Test
    void summaryCountsCurrentAccountsOnly() {
        var summary = service.summary(operator);
        assertThat(summary.users()).isEqualTo(3);
        assertThat(summary.activeLast7Days()).isEqualTo(1);
        // 用户 N 注册于 N 天前：5、6 号在 7 天内，7 号刚满 7 天不算新增。
        assertThat(summary.newLast7Days()).isEqualTo(2);
        assertThat(summary.suspended()).isEqualTo(1);
    }

    @Test
    void suspensionRunsInTheUsersTenantAndRecordsTheReasonInTheSameScope() {
        var seen = new AtomicReference<Long>();
        when(directory.user(5L)).thenReturn(user(5, 2, "林知夏", "ACTIVE", null));
        when(directory.suspend(5L)).thenAnswer(call -> { seen.set(TenantContext.require()); return user(5, 2, "林知夏", "SUSPENDED", null); });
        var result = service.suspension(operator, 5L, new PlatformDtos.UserSuspensionRequest(true, "多次发布违规信息，暂停使用"));
        assertThat(result.status()).isEqualTo("SUSPENDED");
        assertThat(seen.get()).isEqualTo(2L);
        verify(audits).saveAndFlush(argThat(event -> event.getTenantId().equals(2L) && event.getTargetType().equals("USER")
                && event.getTargetId().equals(5L) && event.getAction().equals("SUSPEND_USER") && event.getActorUserId().equals(1L)));
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    void operatorsCannotSuspendThemselvesOrOtherOperatorsAndMustGiveAReason() {
        config.setOperatorUserIds(List.of(1L, 4L));
        assertThatThrownBy(() -> service.suspension(operator, 1L, new PlatformDtos.UserSuspensionRequest(true, "测试停用自己的账号")))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFLICT));
        assertThatThrownBy(() -> service.suspension(operator, 4L, new PlatformDtos.UserSuspensionRequest(true, "测试停用运营账号")))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFLICT));
        assertThatThrownBy(() -> service.suspension(operator, 5L, new PlatformDtos.UserSuspensionRequest(true, "违规")))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST));
        verify(directory, never()).suspend(any());
        verifyNoInteractions(audits);
    }
}
