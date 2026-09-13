package com.wuyao.growth.common.tenant;

/**
 * 契约 5：租户身份只有一个来源——JWT。
 *
 * 任何接口都不接受前端传 tenantId / merchantId。
 * 想知道当前租户，调 {@link #require()}，不要加方法参数。
 */
public final class TenantContext {

    /** 没有租户上下文时用的哨兵值。RLS 策略匹配不到任何行，失败方向是"什么都看不见"。 */
    public static final String SYSTEM = "0";

    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(Long tenantId) {
        CURRENT.set(tenantId);
    }

    public static Long get() {
        return CURRENT.get();
    }

    public static Long require() {
        Long id = CURRENT.get();
        if (id == null) {
            throw new IllegalStateException("当前线程没有租户上下文");
        }
        return id;
    }

    public static void clear() {
        CURRENT.remove();
    }

    /** 以指定租户身份执行一段逻辑（worker 抢到任务后用这个切进租户）。 */
    public static <T> T runAs(Long tenantId, java.util.function.Supplier<T> action) {
        Long previous = CURRENT.get();
        try {
            CURRENT.set(tenantId);
            return action.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
