package com.wuyao.growth.copywriting;

import com.wuyao.growth.brand.BrandDtos;
import com.wuyao.growth.brand.BrandService;
import com.wuyao.growth.store.StoreDtos;
import com.wuyao.growth.store.StoreService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Stream;

/** 把一次生成要用的门店、品牌、口吻和门店志整理成给模型的素材，以及校验要用的事实和允许的占位符。 */
@Component
@RequiredArgsConstructor
class CopyContexts {
    /** 门店占位符和资料字段的对应，顺序就是提示里列出的顺序。 */
    static final List<String> TOKENS = List.of("店名", "地址", "营业时间", "电话");
    private final StoreService stores;
    private final BrandService brands;
    private final CopyVoiceRepository voices;
    private final CopyJournalRepository journal;

    record Built(CopyPrompt.Context context, Set<String> allowedTokens, String storeName) {}

    @Transactional(readOnly = true)
    Built build(CopyBatch batch, int imageCount, List<String> avoidAngles) {
        var store = stores.profile(batch.getStoreId()).orElseThrow(() -> new IllegalStateException("门店不存在或已停用"));
        var values = storeValues(store);
        var allowed = allowedTokens(batch.getStoreLevel(), values);
        var brand = brands.profileForStore(batch.getStoreId()).orElse(null);
        String facts = String.join("\n", Stream.of(
                CopyPrompt.blank(batch.getNote()) ? null : "商家写的话：" + batch.getNote().strip(),
                batch.getProductFacts(), brandFacts(brand)).filter(Objects::nonNull).toList());
        var recent = journal.findByStoreIdOrderByIdDesc(batch.getStoreId(), PageRequest.of(0, 10)).stream()
                .map(e -> CopyPrompt.text(CopyPrompt.blank(e.getTitle()) ? e.getBody().replace('\n', ' ') : e.getTitle(), 30))
                .toList();
        var context = new CopyPrompt.Context(CopyPlatform.valueOf(batch.getPlatform()), batch.getStyle(), batch.getStoreLevel(),
                imageCount, batch.getNote(), facts, List.copyOf(allowed), batch.isVoiceOn() ? voice(batch.getStoreId(), brand) : null,
                recent, avoidAngles);
        return new Built(context, allowed, store.name());
    }

    /** 门店里填了的字段，key 是占位符名。 */
    static Map<String, String> storeValues(StoreDtos.Profile store) {
        var values = new LinkedHashMap<String, String>();
        put(values, "店名", store.name());
        put(values, "地址", store.address());
        put(values, "营业时间", store.businessHours());
        put(values, "电话", store.phone());
        return values;
    }

    static Set<String> allowedTokens(String storeLevel, Map<String, String> values) {
        return switch (storeLevel) {
            case "NONE" -> Set.of();
            case "LIGHT" -> values.containsKey("店名") ? Set.of("店名") : Set.of();
            default -> new LinkedHashSet<>(TOKENS.stream().filter(values::containsKey).toList());
        };
    }

    private String voice(Long storeId, BrandDtos.Profile brand) {
        var profile = voices.findByStoreId(storeId).filter(v -> "READY".equals(v.getStatus()) && v.getProfile() != null);
        if (profile.isPresent()) {
            var v = profile.get();
            var text = new StringBuilder(String.valueOf(v.getProfile().get("summary")));
            if (v.getProfile().get("traits") instanceof List<?> traits && !traits.isEmpty()) {
                text.append("；特点：").append(String.join("、", traits.stream().map(String::valueOf).toList()));
            }
            text.append("。照这个口吻写，下面的样本只学语气，不要用其中的事实：");
            v.getSamples().stream().limit(3).forEach(s -> text.append("「").append(CopyPrompt.text(s, 120)).append("」"));
            return text.toString();
        }
        return brand != null && !CopyPrompt.blank(brand.languageStyle()) ? "品牌语言风格：" + brand.languageStyle().strip() : null;
    }

    private static String brandFacts(BrandDtos.Profile brand) {
        if (brand == null) return null;
        var parts = Stream.of(label("品牌", brand.name()), label("口号", brand.slogan()), label("简介", brand.intro()),
                label("定位", brand.positioning()), label("目标人群", brand.targetAudience())).filter(Objects::nonNull).toList();
        return parts.isEmpty() ? null : String.join("；", parts);
    }

    private static String label(String name, String value) {
        return CopyPrompt.blank(value) ? null : name + "：" + value.strip();
    }

    private static void put(Map<String, String> values, String key, String value) {
        if (!CopyPrompt.blank(value)) values.put(key, value.strip());
    }
}
