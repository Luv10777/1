package com.wuyao.growth.brand;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public final class BrandDtos {

    private static final String WEBSITE = "^$|^https?://\\S+$";
    private static final String COLOR = "^$|^#[0-9a-fA-F]{6}$";

    private BrandDtos() {
    }

    /** 新建和修改共用的品牌资料。 */
    interface Fields {
        String name();
        String industry();
        String slogan();
        String intro();
        String website();
        String positioning();
        String targetAudience();
        String languageStyle();
        String primaryColor();
        String viGuidelines();
        Long logoAssetId();
        Long miniProgramQrAssetId();
        Long wechatQrAssetId();
        String history();
        String brandStory();
        String coreTeam();
        String culture();
    }

    public record CreateRequest(
            @NotBlank(message = "品牌名不能为空") @Size(max = 80) String name,
            @Size(max = 60) String industry,
            @Size(max = 120) String slogan,
            @Size(max = 1000) String intro,
            @Size(max = 300) @Pattern(regexp = WEBSITE, message = "需以 http:// 或 https:// 开头") String website,
            @Size(max = 500) String positioning,
            @Size(max = 500) String targetAudience,
            @Size(max = 500) String languageStyle,
            @Pattern(regexp = COLOR, message = "需为 #RRGGBB 格式") String primaryColor,
            @Size(max = 2000) String viGuidelines,
            @Positive Long logoAssetId,
            @Positive Long miniProgramQrAssetId,
            @Positive Long wechatQrAssetId,
            @Size(max = 2000) String history,
            @Size(max = 4000) String brandStory,
            @Size(max = 2000) String coreTeam,
            @Size(max = 2000) String culture) implements Fields {
    }

    public record UpdateRequest(
            @NotBlank(message = "品牌名不能为空") @Size(max = 80) String name,
            @Size(max = 60) String industry,
            @Size(max = 120) String slogan,
            @Size(max = 1000) String intro,
            @Size(max = 300) @Pattern(regexp = WEBSITE, message = "需以 http:// 或 https:// 开头") String website,
            @Size(max = 500) String positioning,
            @Size(max = 500) String targetAudience,
            @Size(max = 500) String languageStyle,
            @Pattern(regexp = COLOR, message = "需为 #RRGGBB 格式") String primaryColor,
            @Size(max = 2000) String viGuidelines,
            @Positive Long logoAssetId,
            @Positive Long miniProgramQrAssetId,
            @Positive Long wechatQrAssetId,
            @Size(max = 2000) String history,
            @Size(max = 4000) String brandStory,
            @Size(max = 2000) String coreTeam,
            @Size(max = 2000) String culture,
            @NotNull(message = "品牌版本不能为空") @PositiveOrZero Long version) implements Fields {
    }

    public record View(Long id, String name, boolean defaultBrand, String status, long storeCount,
                       String industry, String slogan, String intro, String website,
                       String positioning, String targetAudience, String languageStyle,
                       String primaryColor, String viGuidelines,
                       Long logoAssetId, Long miniProgramQrAssetId, Long wechatQrAssetId,
                       String history, String brandStory, String coreTeam, String culture,
                       Long version, Instant createdAt, Instant updatedAt) {

        static View of(Brand brand, long storeCount) {
            return new View(brand.getId(), brand.getName(), brand.isDefaultBrand(), brand.getStatus(), storeCount,
                    brand.getIndustry(), brand.getSlogan(), brand.getIntro(), brand.getWebsite(),
                    brand.getPositioning(), brand.getTargetAudience(), brand.getLanguageStyle(),
                    brand.getPrimaryColor(), brand.getViGuidelines(),
                    brand.getLogoAssetId(), brand.getMiniProgramQrAssetId(), brand.getWechatQrAssetId(),
                    brand.getHistory(), brand.getBrandStory(), brand.getCoreTeam(), brand.getCulture(),
                    brand.getVersion(), brand.getCreatedAt(), brand.getUpdatedAt());
        }
    }

    /** 其他模块生成内容时可以引用的品牌资料：只有文字，不含视觉资产。 */
    public record Profile(Long id, String name, String slogan, String intro,
                          String positioning, String targetAudience, String languageStyle) {

        static Profile of(Brand brand) {
            return new Profile(brand.getId(), brand.getName(), brand.getSlogan(), brand.getIntro(),
                    brand.getPositioning(), brand.getTargetAudience(), brand.getLanguageStyle());
        }
    }
}
