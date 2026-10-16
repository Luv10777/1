package com.wuyao.growth.store;

import com.wuyao.growth.iam.service.AccountClosureGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * 商户关闭时关掉它的全部门店，再让依赖门店的模块收拾各自的关联。
 * 不再逐店问"现在能不能关"：整个商户都不在了，比如只开放给某家店的声音也没有别处可去。
 * 正在直播的情况由直播模块在注销前拦下。
 */
@Component
@RequiredArgsConstructor
public class StoreAccountClosure implements AccountClosureGuard {
    private final StoreRepository stores;
    private final List<StoreClosureGuard> storeGuards;

    @Override
    public void closed(Closure closure) {
        if (!closure.wholeTenant()) return;
        for (Store store : stores.findAllByTenantIdAndStatusOrderByIdAsc(closure.tenantId(), "ACTIVE")) {
            store.setStatus("ARCHIVED");
            store.setUpdatedAt(Instant.now());
            stores.saveAndFlush(store);
            storeGuards.forEach(guard -> guard.closed(store.getId()));
        }
    }
}
