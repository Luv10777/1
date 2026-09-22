package com.wuyao.growth.common.task;

import java.util.Map;

/**
 * 契约 6：所有异步活儿都实现这个接口，不要各模块自己搞一套调度。
 *
 * 加一种任务只需要两步：
 *   1. 写个 @Component 实现本接口，type() 返回任务类型
 *   2. 在自己的 service 里调 TaskService.submit(...)
 * 剩下的排队、抢占、重试、租户切换都由框架处理。
 *
 * 执行时租户上下文已经切好了，直接查自己模块的表即可。
 */
public interface TaskHandler {

    /** 任务类型，全局唯一。建议用 模块_动作，例如 ASSET_PROBE、IMAGE_GENERATE。 */
    String type();

    /**
     * 返回值写进 tasks.result。框架会重试，不能假设只执行一次。
     * 调供应商或扣费时使用 task.id 派生稳定幂等键，不使用 attempts 派生业务幂等键。
     * 租约只保护任务状态回写，不能撤销已经发生的外部副作用。
     */
    Map<String, Object> handle(Task task);
}
