package com.wuyao.growth.iam.service;

import java.util.List;
import java.util.Optional;

/**
 * 账号注销时，让依赖账号或商户的模块说"现在不行"、说清楚会失去什么，并在注销时收拾自己那部分。
 * 由各模块实现，身份模块不反过来依赖它们。三个方法都在当前商户的租户上下文里调用。
 */
public interface AccountClosureGuard {

    /**
     * 这次注销涉及的范围。
     *
     * @param userIds    要注销的账号：只注销自己时只有本人，关闭商户时是商户里全部未移除的账号
     * @param wholeTenant 是否连同整个商户一起关闭
     */
    record Closure(Long tenantId, List<Long> userIds, boolean wholeTenant) {
    }

    /** @return 现在不能注销的原因，写给用户看；可以注销时为空 */
    default Optional<String> blocksClosing(Closure closure) {
        return Optional.empty();
    }

    /** @return 注销后会作废、需要本人事先知道的东西，每条一句话，写给用户看 */
    default List<String> losses(Closure closure) {
        return List.of();
    }

    /** 注销已确认：清理自己那部分。与注销在同一个事务里执行，抛异常会让整次注销回滚。 */
    default void closed(Closure closure) {
    }
}
