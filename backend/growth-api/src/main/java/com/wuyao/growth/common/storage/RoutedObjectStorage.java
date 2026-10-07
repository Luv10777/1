package com.wuyao.growth.common.storage;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import java.io.InputStream;
import java.time.Duration;
import java.util.Optional;

/** The persisted key retains the destination, including after configuration changes. */
@Primary @Component
public class RoutedObjectStorage implements ObjectStorage {
    private final ObjectStorage minio;
    private final ObjectStorage cos;
    public RoutedObjectStorage(MinioObjectStorage minio, CosObjectStorage cos) {
        this.minio = minio; this.cos = cos;
    }
    private ObjectStorage forKey(String key) { return key.startsWith("cos/") ? cos : minio; }
    public String presignPut(String key, Duration ttl) { return forKey(key).presignPut(key, ttl); }
    public String presignGet(String key, Duration ttl) { return forKey(key).presignGet(key, ttl); }
    public Optional<StoredObject> stat(String key) { return forKey(key).stat(key); }
    public void delete(String key) { forKey(key).delete(key); }
    public byte[] read(String key, int maxBytes) { return forKey(key).read(key, maxBytes); }
    public void put(String key, byte[] data, String contentType) { forKey(key).put(key, data, contentType); }
    public void put(String key, InputStream data, long size, String contentType) {
        forKey(key).put(key, data, size, contentType);
    }
}
