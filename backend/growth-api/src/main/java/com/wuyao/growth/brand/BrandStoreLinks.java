package com.wuyao.growth.brand;

import java.util.Map;
import java.util.Optional;

/**
 * 品牌模块需要知道的门店归属。由门店模块实现，品牌模块不直接访问门店的表。
 * 所有方法都在当前租户上下文内执行。
 */
public interface BrandStoreLinks {

    /** 每个品牌名下营业中的门店数；没有门店的品牌不出现在结果里。 */
    Map<Long, Long> activeStoreCounts();

    long activeStores(Long brandId);

    /** 把还没有品牌的门店归到指定品牌名下，返回受影响的门店数。 */
    int adoptUnassigned(Long brandId);

    Optional<Long> brandIdOf(Long storeId);
}
