package com.wuyao.growth.knowledge;

import com.wuyao.growth.brand.BrandDtos;
import com.wuyao.growth.brand.BrandService;
import com.wuyao.growth.iam.service.AccountService;
import com.wuyao.growth.store.StoreAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Who may touch knowledge owned by a store or a brand. A store's knowledge follows the store's own rule:
 * whoever can enter the store. A brand's is read by every member, the way the brand itself is, and changed
 * only by the owner, who is the only one allowed to edit the brand.
 */
@Component
@RequiredArgsConstructor
class KnowledgeAccess {

    private final StoreAccessService stores;
    private final BrandService brands;
    private final AccountService accounts;

    void requireRead(Long storeId, Long brandId, Long userId) {
        if (storeId != null) stores.requireAccess(storeId, userId);
        else brands.get(brandId);
    }

    void requireWrite(Long storeId, Long brandId, Long userId) {
        if (storeId != null) {
            stores.requireAccess(storeId, userId);
            return;
        }
        accounts.requireOwner(userId);
        brands.get(brandId);
    }

    /** The brand the store belongs to while that brand is in use; checks the caller may enter the store. */
    Optional<Long> brandOf(Long storeId, Long userId) {
        stores.requireAccess(storeId, userId);
        return brands.profileForStore(storeId).map(BrandDtos.Profile::id);
    }
}
