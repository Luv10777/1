package com.wuyao.growth.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuyao.growth.asset.AssetService;
import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.common.web.*;
import com.wuyao.growth.video.VideoDtos;
import com.wuyao.growth.video.VideoProviderGateway;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

@Service @RequiredArgsConstructor
public class VideoPricingService {
    private final VideoPricingProperties prices;
    private final VideoProviderGateway provider;
    private final AssetService assets;
    private final ObjectMapper json;
    public record Quote(boolean available, long credits, String message, String providerBaseUrl, String providerModel,
                        String resolution, String videoInput, long estimatedTokens, int referenceSeconds,
                        BigDecimal usdPerMillionTokens, BigDecimal cnyPerUsd, BigDecimal sellingMultiplier,
                        long maxOutputPixels, int framesPerSecond, int tokenAreaDivisor,
                        String sourceUrl, Instant verifiedAt, Instant validUntil, String quoteId) {}
    public Quote quote(VideoDtos.Create request) {
        String model=provider.providerModel(request.model());
        String mode=request.videoReferences().isEmpty()?"t2v":"v2v";
        var now=Instant.now();
        var matches=prices.getRates().stream().filter(r->Objects.equals(r.getProviderBaseUrl(),provider.baseUrl())
            &&Objects.equals(r.getProviderModel(),model)&&request.resolution().equalsIgnoreCase(r.getResolution())
            &&Objects.equals(r.getVideoInput(),mode)).toList();
        if(matches.size()!=1||!valid(matches.getFirst(),now))
            return new Quote(false,0,"此模型与分辨率的计价尚未核验，暂不可提交生成。",provider.baseUrl(),model,request.resolution(),mode,0,0,null,null,null,0,0,0,null,null,null,null);
        var rate=matches.getFirst();
        // Match the reference duration sent to the provider, never a client-provided media duration.
        int reference=!Boolean.TRUE.equals(rate.getReferenceVideoBilled())?0:request.videoReferences().stream()
            .mapToInt(id->Math.max(1,assets.mediaReference(id,"VIDEO").durationMs()/1000)).sum();
        long tokens=tokens(rate.getMaxOutputPixels(),BigDecimal.valueOf(request.durationSeconds()+reference),rate.getFramesPerSecond(),rate.getTokenAreaDivisor());
        long credits=cost(tokens,rate.getUsdPerMillionTokens(),rate.getCnyPerUsd(),rate.getSellingMultiplier());
        var q=new Quote(true,credits,"按已核验的最大画幅预占，交付后按实际用量结算，多余烟火退回。",
            provider.baseUrl(),model,request.resolution(),mode,tokens,reference,rate.getUsdPerMillionTokens(),rate.getCnyPerUsd(),rate.getSellingMultiplier(),
            rate.getMaxOutputPixels(),rate.getFramesPerSecond(),rate.getTokenAreaDivisor(),rate.getSourceUrl(),rate.getVerifiedAt(),rate.getValidUntil(),null);
        return new Quote(q.available(),q.credits(),q.message(),q.providerBaseUrl(),q.providerModel(),q.resolution(),q.videoInput(),q.estimatedTokens(),q.referenceSeconds(),
            q.usdPerMillionTokens(),q.cnyPerUsd(),q.sellingMultiplier(),q.maxOutputPixels(),q.framesPerSecond(),q.tokenAreaDivisor(),q.sourceUrl(),q.verifiedAt(),q.validUntil(),fingerprint(request,q));
    }
    public Quote requireApproved(VideoDtos.Create request,String approvedId) {
        var q=quote(request);
        if(!q.available()) throw BizException.of(ErrorCode.VIDEO_PRICE_UNAVAILABLE,q.message());
        if(approvedId==null||!MessageDigest.isEqual(q.quoteId().getBytes(StandardCharsets.UTF_8),approvedId.getBytes(StandardCharsets.UTF_8)))
            throw BizException.of(ErrorCode.BILLING_QUOTE_EXPIRED,"视频报价已变化或尚未确认，请重新预估费用后提交");
        return q;
    }
    public void validateDispatch(VideoDtos.Create request,Quote q) {
        if(q==null||!q.available()||!q.validUntil().isAfter(Instant.now())||!Objects.equals(q.providerBaseUrl(),provider.baseUrl())
            ||!Objects.equals(q.providerModel(),provider.providerModel(request.model())))
            throw BizException.of(ErrorCode.BILLING_QUOTE_EXPIRED,"视频计价或供应商配置已变化，预占烟火将退回，请重新创建视频");
    }
    public long actualCredits(Quote q,Long billedQuota,Long reportedTokens,int width,int height,int durationMs) {
        if(billedQuota==null||billedQuota<=0||prices.getRelayQuotaPerUnit()<=0||!positive(prices.getRelayCnyPerUnit()))
            return actualCredits(q,reportedTokens,width,height,durationMs);
        // The relay's own charge replaces the token estimate; the accepted quote stays the ceiling.
        long credits=BigDecimal.valueOf(billedQuota).multiply(prices.getRelayCnyPerUnit()).multiply(q.sellingMultiplier()).multiply(BigDecimal.valueOf(10))
            .divide(BigDecimal.valueOf(prices.getRelayQuotaPerUnit()),0,RoundingMode.UP).longValueExact();
        return Math.min(q.credits(),credits);
    }
    public long actualCredits(Quote q,Long reportedTokens,int width,int height,int durationMs) {
        long actual=reportedTokens!=null&&reportedTokens>0?reportedTokens:tokens(Math.multiplyExact((long)width,height),
            BigDecimal.valueOf(durationMs,3).add(BigDecimal.valueOf(q.referenceSeconds())),q.framesPerSecond(),q.tokenAreaDivisor());
        // The platform absorbs overruns; a completed output never creates an unapproved extra charge.
        return Math.min(q.credits(),cost(actual,q.usdPerMillionTokens(),q.cnyPerUsd(),q.sellingMultiplier()));
    }
    private boolean valid(VideoPricingProperties.Rate r,Instant now) {
        return positive(r.getUsdPerMillionTokens())&&positive(r.getCnyPerUsd())&&positive(r.getSellingMultiplier())
            &&(!"v2v".equals(r.getVideoInput())||r.getReferenceVideoBilled()!=null)
            &&r.getMaxOutputPixels()>0&&r.getMaxOutputPixels()<=67108864&&r.getFramesPerSecond()>0&&r.getFramesPerSecond()<=120&&r.getTokenAreaDivisor()>0
            &&r.getSourceUrl()!=null&&r.getSourceUrl().startsWith("https://")&&r.getVerifiedAt()!=null&&!r.getVerifiedAt().isAfter(now)
            &&r.getValidUntil()!=null&&r.getValidUntil().isAfter(now);
    }
    private boolean positive(BigDecimal n) { return n!=null&&n.signum()>0; }
    private String fingerprint(VideoDtos.Create r,Quote q) {
        try {
            var values=new java.util.ArrayList<Object>(List.of(TenantContext.require(),r.requestKey(),r.prompt(),r.referenceImageAssetIds(),
                Objects.toString(r.referenceVideoAssetId(),""),r.model(),r.ratio(),r.durationSeconds(),r.resolution().toLowerCase(Locale.ROOT),q));
            // Preserve existing approvals for requests that predate multiple video/audio references.
            if(!r.referenceVideoAssetIds().isEmpty()||!r.referenceAudioAssetIds().isEmpty()) {
                values.add(r.referenceVideoAssetIds());values.add(r.referenceAudioAssetIds());
            }
            byte[] input=json.writeValueAsBytes(values);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
        } catch(Exception e) { throw new IllegalStateException("视频报价无法生成",e); }
    }
    static long tokens(long pixels,BigDecimal seconds,int fps,int divisor) {
        if(pixels<=0||seconds.signum()<=0||fps<=0||divisor<=0) throw new IllegalArgumentException("视频计费尺寸和时长必须有效");
        return BigDecimal.valueOf(pixels).multiply(BigDecimal.valueOf(fps))
            .multiply(seconds).divide(BigDecimal.valueOf(divisor),0,RoundingMode.UP).longValueExact();
    }
    static long cost(long tokens,BigDecimal usd,BigDecimal exchange,BigDecimal multiplier) {
        return BigDecimal.valueOf(tokens).multiply(usd).multiply(exchange).multiply(multiplier).multiply(BigDecimal.valueOf(10))
            .divide(BigDecimal.valueOf(1000000),0,RoundingMode.UP).longValueExact();
    }
}
