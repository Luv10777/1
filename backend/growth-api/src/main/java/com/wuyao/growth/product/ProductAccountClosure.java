package com.wuyao.growth.product;

import com.wuyao.growth.iam.service.AccountClosureGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** 商户关闭时把在线商品全部撤下，对外的商品列表和商品图不再能打开。 */
@Component
@RequiredArgsConstructor
public class ProductAccountClosure implements AccountClosureGuard {
    private final ProductRepository products;

    @Override
    public void closed(Closure closure) {
        if (closure.wholeTenant()) products.withdrawAll(closure.tenantId(), Instant.now());
    }
}
