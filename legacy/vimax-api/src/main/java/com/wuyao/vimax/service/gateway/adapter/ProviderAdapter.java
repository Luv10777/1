package com.wuyao.vimax.service.gateway.adapter;

/**
 * Provider Adapter 接口
 *
 * 统一封装 FluAPI 和 ToAPIs 的调用
 */
public interface ProviderAdapter {

    /**
     * 提交任务
     *
     * @param request 任务请求
     * @param apiKey API密钥
     * @return 任务响应
     */
    ProviderTaskResponse submitTask(ProviderTaskRequest request, String apiKey);

    /**
     * 查询任务状态
     *
     * @param providerJobId Provider返回的任务ID
     * @param apiKey API密钥
     * @return 任务响应
     */
    ProviderTaskResponse checkTaskStatus(String providerJobId, String apiKey);

    /**
     * 取消任务（可选）
     *
     * @param providerJobId Provider返回的任务ID
     * @param apiKey API密钥
     * @return 是否成功
     */
    default boolean cancelTask(String providerJobId, String apiKey) {
        throw new UnsupportedOperationException("Cancel not supported by this provider");
    }

    /**
     * 获取 Provider 名称
     */
    String getProviderName();

    /**
     * 是否支持同步返回
     */
    boolean isSynchronous();
}
