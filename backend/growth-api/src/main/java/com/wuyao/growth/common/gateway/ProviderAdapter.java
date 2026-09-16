package com.wuyao.growth.common.gateway;

import java.util.Set;

/**
 * 接一家新供应商 = 写一个这个接口的实现，别处一行不用改。
 */
public interface ProviderAdapter {

    /** 供应商代号，例如 FLUAPI、TOAPIS。 */
    String code();

    /** 这家能干哪些活。 */
    Set<ModelAlias> supports();

    ProviderResult invoke(ProviderRequest request);
}
