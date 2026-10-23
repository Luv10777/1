package com.wuyao.growth.iam.service;

import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import com.wuyao.growth.iam.entity.User;
import com.wuyao.growth.iam.repository.RefreshTokenRepository;
import com.wuyao.growth.iam.repository.TenantRepository;
import com.wuyao.growth.iam.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class PlatformAccountDirectoryTest {
    private final UserRepository users = mock(UserRepository.class);
    private final RefreshTokenRepository refreshTokens = mock(RefreshTokenRepository.class);
    private final PlatformAccountDirectory directory = new PlatformAccountDirectory(mock(TenantRepository.class), users, refreshTokens);
    private final AccountService accounts = new AccountService(users, refreshTokens, mock(TenantRepository.class), mock(AccountAvatarService.class));

    @AfterEach
    void clearTenant() { TenantContext.clear(); }

    private User user(String status, String role) {
        var user = new User();
        user.setId(9L);
        user.setTenantId(2L);
        user.setStatus(status);
        user.setRole(role);
        user.setTokenVersion(3);
        when(users.findForUpdate(9L)).thenReturn(Optional.of(user));
        return user;
    }

    private static void expectConflict(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BizException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    void suspensionSignsTheUserOutEverywhereAndRestoreReturnsToTheActiveState() {
        var user = user("ACTIVE", "OWNER");
        var suspended = directory.suspend(9L);
        assertThat(suspended.status()).isEqualTo("SUSPENDED");
        assertThat(suspended.suspendedAt()).isNotNull();
        assertThat(user.getStatusBeforeSuspension()).isEqualTo("ACTIVE");
        assertThat(user.getTokenVersion()).isEqualTo(4);
        verify(refreshTokens).revokeAllForUser(9L);
        expectConflict(() -> directory.suspend(9L));

        var restored = directory.restore(9L);
        assertThat(restored.status()).isEqualTo("ACTIVE");
        assertThat(user.getStatusBeforeSuspension()).isNull();
        assertThat(user.getSuspendedAt()).isNull();
        expectConflict(() -> directory.restore(9L));
    }

    @Test
    void staffDisabledByTheMerchantStayDisabledAfterThePlatformLiftsTheSuspension() {
        user("DISABLED", "STAFF");
        directory.suspend(9L);
        assertThat(directory.restore(9L).status()).isEqualTo("DISABLED");
    }

    @Test
    void removedAccountsCannotBeSuspended() {
        user("REMOVED", "STAFF");
        expectConflict(() -> directory.suspend(9L));
        verifyNoInteractions(refreshTokens);
    }

    @Test
    void theMerchantCannotEnableOrRemoveStaffSuspendedByThePlatform() {
        var user = user("SUSPENDED", "STAFF");
        TenantContext.set(2L);
        expectConflict(() -> accounts.setStaffEnabled(9L, true));
        expectConflict(() -> accounts.removeStaff(9L));
        assertThat(user.getStatus()).isEqualTo("SUSPENDED");
        assertThat(user.getPhone()).isNull();
        verifyNoInteractions(refreshTokens);
    }
}
