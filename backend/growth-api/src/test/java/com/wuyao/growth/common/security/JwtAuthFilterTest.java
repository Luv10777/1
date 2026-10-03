package com.wuyao.growth.common.security;

import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.iam.entity.User;
import com.wuyao.growth.iam.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import java.time.Duration;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class JwtAuthFilterTest {
    final JwtService jwt = new JwtService("test-only-random-signing-secret-0123456789abcdef", Duration.ofHours(1), Duration.ofDays(30));
    final UserRepository users = mock(UserRepository.class);
    final JwtAuthFilter filter = new JwtAuthFilter(jwt, users);

    @Test
    void activeUserGetsAuthenticatedAndContextIsClearedEvenOnFailure() {
        User user = user();
        when(users.findById(1L)).thenReturn(Optional.of(user));
        var request = request(jwt.issueAccessToken(1L, 2L, null, 3));
        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal())
                    .isEqualTo(new AuthPrincipal(1L, 2L, null, 3));
            assertThat(TenantContext.require()).isEqualTo(2L);
            throw new java.io.IOException("chain failed");
        })).isInstanceOf(java.io.IOException.class);
        assertThat(TenantContext.get()).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void disabledMissingMismatchedTenantAndRevokedVersionCannotAuthenticate() throws Exception {
        User disabled = user(); disabled.setStatus("DISABLED");
        User differentTenant = user(); differentTenant.setTenantId(9L);
        User loggedOut = user(); loggedOut.setTokenVersion(4);
        for (var candidate : java.util.List.of(Optional.<User>empty(), Optional.of(disabled),
                Optional.of(differentTenant), Optional.of(loggedOut))) {
            when(users.findById(1L)).thenReturn(candidate);
            filter.doFilter(request(jwt.issueAccessToken(1L, 2L, null, 3)), new MockHttpServletResponse(), (req, res) -> {
                assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
                assertThat(TenantContext.get()).isNull();
            });
        }
    }

    @Test
    void rejectsRefreshTokensAndFailsClosedWhenDatabaseIsUnavailable() throws Exception {
        filter.doFilter(request(jwt.issueRefreshToken(1L, 2L, null, 3)), new MockHttpServletResponse(), (req, res) ->
                assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull());
        verifyNoInteractions(users);
        when(users.findById(1L)).thenThrow(new IllegalStateException("database unavailable"));
        filter.doFilter(request(jwt.issueAccessToken(1L, 2L, null, 3)), new MockHttpServletResponse(), (req, res) ->
                assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull());
    }

    private User user() {
        User user = new User(); user.setId(1L); user.setTenantId(2L); user.setTokenVersion(3); return user;
    }
    private MockHttpServletRequest request(String token) {
        var request = new MockHttpServletRequest(); request.addHeader("Authorization", "Bearer " + token); return request;
    }
}
