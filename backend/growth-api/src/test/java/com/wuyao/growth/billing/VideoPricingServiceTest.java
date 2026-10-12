package com.wuyao.growth.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuyao.growth.asset.AssetService;
import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.video.*;
import org.junit.jupiter.api.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class VideoPricingServiceTest {
    private final VideoProviderGateway provider=mock(VideoProviderGateway.class);
    private final AssetService assets=mock(AssetService.class);
    private final VideoPricingProperties config=new VideoPricingProperties();
    private final VideoPricingService service=new VideoPricingService(config,provider,assets,new ObjectMapper().findAndRegisterModules());
    private final VideoDtos.Create request=new VideoDtos.Create("pricing-test-001","画面",List.of(),null,"SEEDANCE_2_0","16:9",5,"720p");
    private VideoPricingProperties.Rate rate;
    @BeforeEach void setup() {
        TenantContext.set(7L);
        when(provider.baseUrl()).thenReturn("https://test.invalid");
        when(provider.providerModel(anyString())).thenAnswer(i->"actual-"+i.getArgument(0));
        rate=new VideoPricingProperties.Rate();
        rate.setProviderBaseUrl("https://test.invalid");rate.setProviderModel("actual-SEEDANCE_2_0");
        rate.setResolution("720p");rate.setVideoInput("t2v");rate.setUsdPerMillionTokens(new BigDecimal("2"));
        rate.setCnyPerUsd(new BigDecimal("7"));rate.setSellingMultiplier(BigDecimal.ONE);
        rate.setMaxOutputPixels(1280*720);rate.setFramesPerSecond(24);rate.setTokenAreaDivisor(1024);
        rate.setSourceUrl("https://test.invalid/test-evidence");rate.setVerifiedAt(Instant.now().minusSeconds(60));rate.setValidUntil(Instant.now().plusSeconds(3600));
    }
    @AfterEach void clear(){TenantContext.clear();}
    @Test void missingOrUnverifiedRatesNeverInventPrices() {
        assertThat(service.quote(request).available()).isFalse();
        config.setRates(List.of(rate));rate.setFramesPerSecond(0);
        assertThat(service.quote(request).available()).isFalse();
        rate.setFramesPerSecond(24);rate.setValidUntil(Instant.now().minusSeconds(1));
        assertThat(service.quote(request).available()).isFalse();
    }
    @Test void matchesActualProviderModelResolutionAndInputMode() {
        config.setRates(List.of(rate));
        assertThat(service.quote(request).available()).isTrue();
        rate.setProviderModel("different-model");assertThat(service.quote(request).available()).isFalse();
        rate.setProviderModel("actual-SEEDANCE_2_0");rate.setResolution("1080p");assertThat(service.quote(request).available()).isFalse();
        rate.setResolution("720p");rate.setVideoInput("v2v");assertThat(service.quote(request).available()).isFalse();
        rate.setVideoInput("t2v");config.setRates(List.of(rate,rate));assertThat(service.quote(request).available()).isFalse();
    }
    @Test void pixelDurationTokensAreRoundedToWholeYanhuoAndBoundToApproval() {
        config.setRates(List.of(rate));var quote=service.quote(request);
        assertThat(quote.estimatedTokens()).isEqualTo(108000);assertThat(quote.credits()).isEqualTo(16);
        assertThat(service.requireApproved(request,quote.quoteId())).isEqualTo(quote);
        assertThatThrownBy(()->service.requireApproved(request,null)).isInstanceOf(BizException.class);
        rate.setUsdPerMillionTokens(new BigDecimal("3"));
        assertThatThrownBy(()->service.requireApproved(request,quote.quoteId())).isInstanceOf(BizException.class);
    }
    @Test void usageTokensOverrideProbeAndNeverChargeAboveAcceptedQuote() {
        config.setRates(List.of(rate));var quote=service.quote(request);
        assertThat(service.actualCredits(quote,54000L,1920,1080,10000)).isEqualTo(8);
        assertThat(service.actualCredits(quote,999999999L,1920,1080,10000)).isEqualTo(16);
        assertThat(service.actualCredits(quote,null,640,480,5000)).isEqualTo(6);
        assertThatThrownBy(()->service.actualCredits(quote,null,0,0,0)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void referenceVideoUsesPersistedDurationAndVerifiedFormula() {
        rate.setVideoInput("v2v");rate.setReferenceVideoBilled(true);config.setRates(List.of(rate));
        when(assets.mediaReference(11L,"VIDEO")).thenReturn(new AssetService.MediaReference(11L,"key","video/mp4",4500));
        var q=service.quote(new VideoDtos.Create("pricing-test-002","画面",List.of(),11L,"SEEDANCE_2_0","16:9",5,"720p"));
        assertThat(q.referenceSeconds()).isEqualTo(4);assertThat(q.estimatedTokens()).isEqualTo(194400);
    }
}
