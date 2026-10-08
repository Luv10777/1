package com.wuyao.growth.store;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class StoreDtos {
    private static final String TIME = "^$|^([01]\\d|2[0-3]):[0-5]\\d$";

    private StoreDtos() {
    }

    /**
     * 门店档案里的三项在新建和修改时都可以不带：不带（null）表示不改动，
     * 带空字符串或空列表表示清空。这样只会填四项基本资料的旧表单不会把档案抹掉。
     */
    public record CreateRequest(
            @NotBlank @Size(max = 120) String name,
            @Size(max = 300) String address,
            @Size(max = 40) String phone,
            @Size(max = 120) String businessHours,
            @Positive Long brandId,
            @Size(max = 500) String transportGuide,
            @Size(max = 20) List<@NotBlank @Size(max = 20) String> amenities,
            @Size(max = 30) @Valid List<@NotNull SpecialHour> specialHours) {
    }

    public record UpdateRequest(
            @NotBlank @Size(max = 120) String name,
            @Size(max = 300) String address,
            @Size(max = 40) String phone,
            @Size(max = 120) String businessHours,
            Long version,
            @Positive Long brandId,
            @Size(max = 500) String transportGuide,
            @Size(max = 20) List<@NotBlank @Size(max = 20) String> amenities,
            @Size(max = 30) @Valid List<@NotNull SpecialHour> specialHours) {
    }

    /**
     * 一条特殊营业安排：每周固定的某一天，或某个具体日期；要么休息，要么换一个营业时间。
     *
     * @param scope   WEEKLY 每周，DATE 指定日期
     * @param weekday 1（周一）到 7（周日），WEEKLY 时必填
     * @param date    yyyy-MM-dd，DATE 时必填
     * @param closed  true 表示这一天不营业；false 时 opensAt 和 closesAt 必填
     */
    public record SpecialHour(
            @NotBlank @Pattern(regexp = "WEEKLY|DATE", message = "只能是 WEEKLY 或 DATE") String scope,
            @Min(1) @Max(7) Integer weekday,
            @Pattern(regexp = "^$|^\\d{4}-\\d{2}-\\d{2}$", message = "需为 yyyy-MM-dd") String date,
            @NotNull Boolean closed,
            @Pattern(regexp = TIME, message = "需为 HH:mm") String opensAt,
            @Pattern(regexp = TIME, message = "需为 HH:mm") String closesAt,
            @Size(max = 60) String note) {
    }

    /** stores.profile 这个 JSON 字段的内容。旧数据是空对象，三项都可能为 null。 */
    public record Extras(String transportGuide, List<String> amenities, List<SpecialHour> specialHours) {
        static final Extras EMPTY = new Extras(null, List.of(), List.of());

        public List<String> amenities() {
            return amenities == null ? List.of() : amenities;
        }

        public List<SpecialHour> specialHours() {
            return specialHours == null ? List.of() : specialHours;
        }
    }

    public record StoreView(Long id, String name, String status, String address, String phone,
                            String businessHours, Long version, Instant createdAt, Long brandId,
                            String transportGuide, List<String> amenities, List<SpecialHour> specialHours) {
        static StoreView of(Store store) {
            Extras extras = store.getProfile() == null ? Extras.EMPTY : store.getProfile();
            return new StoreView(store.getId(), store.getName(), store.getStatus(), store.getAddress(),
                    store.getPhone(), store.getBusinessHours(), store.getVersion(), store.getCreatedAt(),
                    store.getBrandId(), extras.transportGuide(), extras.amenities(), extras.specialHours());
        }
    }

    /** 其他模块生成内容时可以引用的门店事实，例如直播回答"在哪、几点关门、能不能停车"。 */
    public record Profile(Long id, String name, String address, String phone, String businessHours,
                          String transportGuide, List<String> amenities, List<SpecialHour> specialHours) {
        static Profile of(Store store) {
            Extras extras = store.getProfile() == null ? Extras.EMPTY : store.getProfile();
            return new Profile(store.getId(), store.getName(), store.getAddress(), store.getPhone(),
                    store.getBusinessHours(), extras.transportGuide(), extras.amenities(), extras.specialHours());
        }
    }
}
