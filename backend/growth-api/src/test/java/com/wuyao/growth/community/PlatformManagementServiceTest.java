package com.wuyao.growth.community;

import com.wuyao.growth.common.security.AuthPrincipal;
import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import com.wuyao.growth.iam.service.PlatformAccountDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PlatformManagementServiceTest {
    private final AuthPrincipal operator = new AuthPrincipal(1L, 1L, "13800000001", 0);
    private final InformationPublicationRepository publications = mock(InformationPublicationRepository.class);
    private final PlatformManagementAuditRepository audits = mock(PlatformManagementAuditRepository.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final PlatformProperties config = new PlatformProperties();
    private final PlatformManagementService service = new PlatformManagementService(config,
            mock(PlatformAccountDirectory.class), publications, mock(MerchantApplicationRepository.class),
            mock(RealNameApplicationRepository.class), audits, mock(RealNameCrypto.class), transactions,
            mock(org.springframework.context.ApplicationEventPublisher.class));
    private InformationPublication value;

    @BeforeEach
    void setup() {
        config.setOperatorUserIds(List.of(1L));
        when(transactions.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        value = new InformationPublication();
        value.setId(7L);
        value.setTenantId(2L);
        value.setUpdatedAt(Instant.parse("2026-10-18T00:00:00Z"));
        when(publications.findByIdAndTenantId(7L, 2L)).thenReturn(Optional.of(value));
    }

    private void review(String status) {
        service.reviewInformation(operator, 2L, 7L,
                new PlatformDtos.ReviewRequest(status, "已核对本次提交的详细内容", value.getUpdatedAt()));
    }

    @Test
    void onlyOperatorsCanReviewAndAuthorizationIsCheckedBeforeLoadingContent() {
        var merchant = new AuthPrincipal(5L, 2L, "13800000005", 0);
        assertThatThrownBy(() -> service.reviewInformation(merchant, 2L, 7L,
                new PlatformDtos.ReviewRequest("PUBLISHED", "商家尝试自行审核信息", value.getUpdatedAt())))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        verifyNoInteractions(publications, transactions, audits);
    }

    @Test
    void approvalSetsThePublicationTimeAndPersistsTheDecisionInTheTargetTenantsAudit() {
        Instant before = Instant.now();
        review("PUBLISHED");
        assertThat(value.getStatus()).isEqualTo("PUBLISHED");
        assertThat(value.getPublishedAt()).isBetween(before, Instant.now());
        assertThat(value.getReviewNote()).isEqualTo("已核对本次提交的详细内容");
        assertThat(value.isPlatformBlocked()).isFalse();
        verify(audits).saveAndFlush(argThat(event -> event.getTenantId().equals(2L)
                && event.getActorUserId().equals(1L) && event.getAction().equals("PUBLISHED")));
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    void rejectionKeepsInformationPrivateAndRecordsTheReasonForThePublisher() {
        review("REJECTED");
        assertThat(value.getStatus()).isEqualTo("REJECTED");
        assertThat(value.getPublishedAt()).isNull();
        assertThat(value.getReviewNote()).isEqualTo("已核对本次提交的详细内容");
        assertThat(value.isPlatformBlocked()).isFalse();
        assertThatThrownBy(() -> review("PUBLISHED")).isInstanceOfSatisfying(BizException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    void reviewingAnOlderVersionCannotPublishRecentlyEditedContent() {
        Instant seen = value.getUpdatedAt();
        value.setUpdatedAt(seen.plusSeconds(1));
        assertThatThrownBy(() -> service.reviewInformation(operator, 2L, 7L,
                new PlatformDtos.ReviewRequest("PUBLISHED", "已核对旧版本的详细内容", seen)))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFLICT));
        assertThat(value.getStatus()).isEqualTo("PENDING_REVIEW");
        assertThat(value.getPublishedAt()).isNull();
        verifyNoInteractions(audits);
    }

    @ParameterizedTest
    @ValueSource(strings = {"REJECTED", "OFFLINE"})
    void rejectedOrWithdrawnUnreviewedContentCannotBeRestoredDirectly(String status) {
        value.setStatus(status);
        assertThatThrownBy(() -> review("PUBLISHED")).isInstanceOfSatisfying(BizException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFLICT));
        assertThat(value.getStatus()).isEqualTo(status);
        verifyNoInteractions(audits);
    }

    @Test
    void expiredContentCannotBeApprovedButCanBeRejected() {
        value.setValidUntil(LocalDate.now().minusDays(1));
        assertThatThrownBy(() -> review("PUBLISHED")).isInstanceOfSatisfying(BizException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFLICT));
        review("REJECTED");
        assertThat(value.getPublishedAt()).isNull();
    }

    @Test
    void previouslyReviewedContentCanBeTakenOfflineAndRestoredByThePlatform() {
        review("PUBLISHED");
        review("OFFLINE");
        assertThat(value.getStatus()).isEqualTo("OFFLINE");
        assertThat(value.isPlatformBlocked()).isTrue();
        review("PUBLISHED");
        assertThat(value.getStatus()).isEqualTo("PUBLISHED");
        assertThat(value.isPlatformBlocked()).isFalse();
        verify(audits, times(3)).saveAndFlush(any());
    }
}
