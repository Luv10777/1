package com.wuyao.growth.store;

import com.wuyao.growth.brand.BrandStoreLinks;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/** 门店归属对品牌模块的公开面。只依赖门店自己的仓库，不反过来依赖品牌服务。 */
@Component
@RequiredArgsConstructor
class StoreBrandLinks implements BrandStoreLinks {
    private final StoreRepository storeRepository;

    @Override
    @Transactional(readOnly = true)
    public Map<Long, Long> activeStoreCounts() {
        return storeRepository.countActiveByBrand().stream()
                .collect(Collectors.toMap(row -> (Long) row[0], row -> (Long) row[1]));
    }

    @Override
    @Transactional(readOnly = true)
    public long activeStores(Long brandId) {
        return storeRepository.countByBrandIdAndStatus(brandId, "ACTIVE");
    }

    @Override
    @Transactional
    public int adoptUnassigned(Long brandId) {
        return storeRepository.assignBrandWhereMissing(brandId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Long> brandIdOf(Long storeId) {
        return storeRepository.findById(storeId).map(Store::getBrandId);
    }
}
