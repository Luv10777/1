package com.wuyao.growth.community;

import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CommunityServiceTest {
    private final InformationPublicationRepository publications = mock(InformationPublicationRepository.class);
    private final CommunityService service = new CommunityService(mock(MerchantApplicationRepository.class), publications);

    @BeforeEach
    void setTenant() { TenantContext.set(2L); }

    @AfterEach
    void clearTenant() { TenantContext.clear(); }

    private CommunityDtos.InformationRequest content() {
        return new CommunityDtos.InformationRequest("notice", "已更新的公告", "发布商家", "线上服务", null,
                "已保存的详细内容", null, null, null, true);
    }

    @Test
    void managementListUsesTheAuthenticatedTenantEvenThoughPublishedRowsArePubliclyReadable() {
        var mine = new InformationPublication();
        mine.setId(7L);
        mine.setTenantId(2L);
        when(publications.findAllByTenantIdOrderByCreatedAtDescIdDesc(2L)).thenReturn(List.of(mine));
        assertThat(service.publications()).extracting(CommunityDtos.InformationView::id).containsExactly(7L);
        verify(publications).findAllByTenantIdOrderByCreatedAtDescIdDesc(2L);
        verify(publications, never()).findAll();
        TenantContext.clear();
        assertThatThrownBy(service::publications).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anotherMerchantsPublicArticleCannotBeUpdatedOrTakenOffline() {
        when(publications.findByIdAndTenantId(9L, 2L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.update(9L, content())).isInstanceOfSatisfying(BizException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> service.takeOffline(9L)).isInstanceOfSatisfying(BizException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        verify(publications, never()).findById(any());
        verify(publications, never()).saveAndFlush(any());
    }

    @Test
    void aMerchantCanUpdateAndTakeOfflineItsOwnArticle() {
        var mine = new InformationPublication();
        mine.setId(7L);
        mine.setTenantId(2L);
        when(publications.findByIdAndTenantId(7L, 2L)).thenReturn(Optional.of(mine));
        when(publications.saveAndFlush(mine)).thenReturn(mine);
        var result = service.update(7L, content());
        assertThat(result.title()).isEqualTo("已更新的公告");
        assertThat(result.content()).isEqualTo("已保存的详细内容");
        assertThat(result.status()).isEqualTo("PUBLISHED");
        service.takeOffline(7L);
        assertThat(mine.getStatus()).isEqualTo("OFFLINE");
        assertThat(mine.getTenantId()).isEqualTo(2L);
    }
}
