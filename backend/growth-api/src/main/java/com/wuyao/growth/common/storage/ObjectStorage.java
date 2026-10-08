package com.wuyao.growth.common.storage;

import java.time.Duration;
import java.io.InputStream;
import java.util.Optional;

/**
 * 契约 7：文件一律预签名直传，不经过后端。
 * 后端只负责发签名和记元数据，几百兆的视频不要走应用服务器。
 */
public interface ObjectStorage {

    /** 生成上传用的预签名 URL，前端拿着它直接 PUT 到对象存储。 */
    String presignPut(String key, Duration ttl);

    /** 生成下载用的预签名 URL。 */
    String presignGet(String key, Duration ttl);

    /** 只在对象不存在时返回 empty；存储故障必须抛出业务异常。 */
    Optional<StoredObject> stat(String key);

    default boolean exists(String key) {
        return stat(key).isPresent();
    }

    record StoredObject(long sizeBytes, String contentType) {
    }

    /** Storage failures must propagate so callers retain records for retry. */
    void delete(String key);

    /** Small image objects only; implementations enforce the byte limit before decoding. */
    byte[] read(String key, int maxBytes);

    /** Download bounded media to a worker's temporary file. */
    default void download(String key, java.nio.file.Path target, int maxBytes) throws java.io.IOException {
        java.nio.file.Files.write(target, read(key, maxBytes));
    }

    void put(String key, byte[] data, String contentType);

    /** Stream a provider response directly into object storage. Implementations must not buffer it in the JVM. */
    void put(String key, InputStream data, long size, String contentType);
}
