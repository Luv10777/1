package com.wuyao.growth.store;

import java.util.Optional;

/**
 * 让依赖门店的模块在关店前说"现在不行"，并在关店后收拾自己那部分关联。
 * 由各模块实现，门店模块不反过来依赖它们。
 */
public interface StoreClosureGuard {

    /** @return 现在不能关这家店的原因，写给用户看；可以关时为空 */
    Optional<String> blocksClosing(Long storeId);

    /** 门店已经关闭：清理只对营业中的门店有意义的关联。与关店在同一个事务里执行。 */
    default void closed(Long storeId) {
    }
}
