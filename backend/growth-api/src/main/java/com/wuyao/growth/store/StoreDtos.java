package com.wuyao.growth.store;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public final class StoreDtos {
    private StoreDtos() {
    }

    public record CreateRequest(
            @NotBlank @Size(max = 120) String name,
            @Size(max = 300) String address,
            @Size(max = 40) String phone,
            @Size(max = 120) String businessHours,
            @Positive Long brandId) {
    }

    public record UpdateRequest(
            @NotBlank @Size(max = 120) String name,
            @Size(max = 300) String address,
            @Size(max = 40) String phone,
            @Size(max = 120) String businessHours,
            Long version,
            @Positive Long brandId) {
    }

    public record StoreView(Long id, String name, String status, String address, String phone,
                            String businessHours, Long version, Instant createdAt, Long brandId) {
        static StoreView of(Store store) {
            return new StoreView(store.getId(), store.getName(), store.getStatus(), store.getAddress(),
                    store.getPhone(), store.getBusinessHours(), store.getVersion(), store.getCreatedAt(),
                    store.getBrandId());
        }
    }
}
