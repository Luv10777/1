package com.wuyao.growth.common.storage;

import java.time.Duration;

/**
 * 契约 7：文件一律预签名直传，不经过后端。
 * 后端只负责发签名和记元数据，几百兆的视频不要走应用服务器。
 */
public interface ObjectStorage {

    /** 生成上传用的预签名 URL，前端拿着它直接 PUT 到对象存储。 */
    String presignPut(String key, Duration ttl);

    /** 生成下载用的预签名 URL。 */
    String presignGet(String key, Duration ttl);

    boolean exists(String key);

    void delete(String key);
}
