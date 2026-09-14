package com.wuyao.growth.common.storage;

import io.minio.*;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import io.minio.http.Method;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** 开发用 MinIO，生产换腾讯云 COS / 阿里云 OSS 时只换这个实现类。 */
@Slf4j
@Component
public class MinioObjectStorage implements ObjectStorage {

    private final MinioClient client;
    private final String bucket;

    public MinioObjectStorage(@Value("${growth.storage.endpoint}") String endpoint,
                              @Value("${growth.storage.access-key}") String accessKey,
                              @Value("${growth.storage.secret-key}") String secretKey,
                              @Value("${growth.storage.bucket}") String bucket) {
        this.client = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
        this.bucket = bucket;
    }

    @Override
    public String presignPut(String key, Duration ttl) {
        return presign(Method.PUT, key, ttl);
    }

    @Override
    public String presignGet(String key, Duration ttl) {
        return presign(Method.GET, key, ttl);
    }

    private String presign(Method method, String key, Duration ttl) {
        try {
            return client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(method)
                    .bucket(bucket)
                    .object(key)
                    .expiry((int) ttl.toSeconds(), TimeUnit.SECONDS)
                    .build());
        } catch (Exception e) {
            log.error("对象存储不可用: key={}", key, e);
            throw BizException.of(ErrorCode.STORAGE_UNAVAILABLE, "对象存储暂时不可用，请稍后重试");
        }
    }

    @Override
    public boolean exists(String key) {
        try {
            client.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void delete(String key) {
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
        } catch (Exception e) {
            log.warn("删除对象失败: {}", key, e);
        }
    }
}
