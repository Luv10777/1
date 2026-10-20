package com.wuyao.growth.product;

import java.util.Optional;

/**
 * 让依赖商品的模块在删除商品前说"现在不行"。由各模块实现，商品模块不反过来依赖它们。
 * 在当前商户的租户上下文、删除所在的事务里调用。
 */
public interface ProductDeletionGuard {

    /** @return 现在不能删除这件商品的原因，写给用户看；可以删除时为空 */
    Optional<String> blocksDeletion(Long productId);
}
