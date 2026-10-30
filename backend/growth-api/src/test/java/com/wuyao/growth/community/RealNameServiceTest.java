package com.wuyao.growth.community;

import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.iam.service.PlatformAccountDirectory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class RealNameServiceTest {
    private static final String NUMBER = "11010519491231002X";
    private final RealNameApplicationRepository applications = mock(RealNameApplicationRepository.class);
    private final PlatformManagementAuditRepository audits = mock(PlatformManagementAuditRepository.class);
    private final IdentityVerifier verifier = mock(IdentityVerifier.class);
    private final RealNameService service = new RealNameService(applications, mock(PlatformAccountDirectory.class),
            mock(RealNameCrypto.class), audits, verifier);

    @BeforeEach
    void setUp() {
        TenantContext.set(7L);
        when(applications.findByTenantIdAndUserId(7L, 3L)).thenReturn(Optional.empty());
        when(applications.saveAndFlush(any())).thenAnswer(call -> {
            RealNameApplication saved = call.getArgument(0);
            saved.setId(11L);
            return saved;
        });
    }

    @AfterEach
    void tearDown() { TenantContext.clear(); }

    @Test
    void matchIsVerifiedImmediatelyAndAudited() {
        when(verifier.verify("张三", NUMBER)).thenReturn(new IdentityVerifier.Result(IdentityVerifier.Outcome.MATCH, "REQ-1"));
        var view = submit();
        assertThat(view.status()).isEqualTo("VERIFIED");
        assertThat(view.reviewedAt()).isNotNull();
        assertThat(auditedActions()).containsExactly("SUBMITTED", "AUTO_VERIFIED");
    }

    @Test
    void mismatchIsRejectedSoTheUserCanCorrectIt() {
        when(verifier.verify("张三", NUMBER)).thenReturn(new IdentityVerifier.Result(IdentityVerifier.Outcome.MISMATCH, "REQ-2"));
        var view = submit();
        assertThat(view.status()).isEqualTo("REJECTED");
        assertThat(view.reviewNote()).contains("不一致");
        assertThat(auditedActions()).containsExactly("SUBMITTED", "AUTO_REJECTED");
    }

    @Test
    void unavailableWaitsForManualReview() {
        when(verifier.verify("张三", NUMBER)).thenReturn(new IdentityVerifier.Result(IdentityVerifier.Outcome.UNAVAILABLE, null));
        var view = submit();
        assertThat(view.status()).isEqualTo("PENDING_REVIEW");
        assertThat(view.reviewedAt()).isNull();
        assertThat(auditedActions()).containsExactly("SUBMITTED");
    }

    @Test
    void repeatedMismatchesStopCallingTheProvider() {
        when(audits.countByTenantIdAndActorUserIdAndTargetTypeAndActionAndCreatedAtAfter(
                eq(7L), eq(3L), eq("IDENTITY"), eq("AUTO_REJECTED"), any()))
                .thenReturn((long) RealNameService.MAX_DAILY_AUTO_REJECTIONS);
        assertThat(submit().status()).isEqualTo("PENDING_REVIEW");
        verify(verifier, never()).verify(anyString(), anyString());
    }

    private RealNameDtos.ApplicationView submit() {
        return service.submit(3L, new RealNameDtos.ApplicationRequest("张三", NUMBER, true));
    }

    private java.util.List<String> auditedActions() {
        var events = ArgumentCaptor.forClass(PlatformManagementAudit.class);
        verify(audits, atLeastOnce()).saveAndFlush(events.capture());
        return events.getAllValues().stream().map(PlatformManagementAudit::getAction).toList();
    }
}
