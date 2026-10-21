package com.wuyao.growth.alliance;

import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.iam.service.AccountClosureGuard;
import com.wuyao.growth.product.ProductDeletionGuard;
import com.wuyao.growth.store.StoreClosureGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 有券没用完时，商品不能删、门店不能关、供券商户不能整体关闭。 */
final class AllianceGuards {
    private AllianceGuards() {}

    @Component
    @RequiredArgsConstructor
    static class Products implements ProductDeletionGuard {
        private final AllianceOfferRepository offers;
        private final AllianceOfferClaimRepository claims;

        @Override
        public Optional<String> blocksDeletion(Long productId) {
            if (offers.existsByProductIdAndStatusIn(productId, AllianceOfferService.OPEN)) {
                return Optional.of("这件商品还有在架的联盟券供券单，请先撤回");
            }
            long outstanding = claims.countOutstandingForProduct(productId);
            return outstanding == 0 ? Optional.empty()
                    : Optional.of("这件商品还有 " + outstanding + " 张联盟券未核销，核销或过期后才能删除");
        }
    }

    @Component
    @RequiredArgsConstructor
    static class Stores implements StoreClosureGuard {
        private final AllianceOfferRepository offers;
        private final AllianceOfferClaimRepository claims;

        @Override
        public Optional<String> blocksClosing(Long storeId) {
            if (offers.existsByStoreIdAndStatusIn(storeId, AllianceOfferService.OPEN)) {
                return Optional.of("这家门店还有在架的联盟券供券单，请先撤回");
            }
            long outstanding = claims.countOutstandingForStore(storeId);
            return outstanding == 0 ? Optional.empty()
                    : Optional.of("这家门店还有 " + outstanding + " 张联盟券未核销，核销或过期后才能关店");
        }
    }

    @Component
    @RequiredArgsConstructor
    static class Accounts implements AccountClosureGuard {
        private final AllianceOfferRepository offers;
        private final AllianceOfferClaimRepository claims;
        private final AllianceVoucherRepository vouchers;

        @Override
        public Optional<String> blocksClosing(Closure closure) {
            if (!closure.wholeTenant()) return Optional.empty();
            long outstanding = claims.countByTenantIdAndStatus(TenantContext.require(), "CLAIMED");
            return outstanding == 0 ? Optional.empty()
                    : Optional.of("商户还有 " + outstanding + " 张已领出的联盟券未核销，核销或过期后才能关闭商户");
        }

        @Override
        public List<String> losses(Closure closure) {
            if (!closure.wholeTenant()) return List.of();
            long unused = vouchers.countByTenantIdAndStatus(TenantContext.require(), "ACTIVE");
            return unused == 0 ? List.of() : List.of(unused + " 张未使用的联盟券将无法再使用");
        }

        @Override
        public void closed(Closure closure) {
            if (closure.wholeTenant()) offers.withdrawAll(closure.tenantId(), Instant.now());
        }
    }
}
