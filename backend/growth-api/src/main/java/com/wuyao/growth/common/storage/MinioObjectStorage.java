package com.wuyao.growth.common.storage;

import io.minio.*;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import io.minio.http.Method;
import io.minio.errors.ErrorResponseException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** 开发用 MinIO，生产换腾讯云 COS / 阿里云 OSS 时只换这个实现类。 */
@Slf4j
@Component
public class MinioObjectStorage implements ObjectStorage {

    private final MinioClient client;
    private final MinioClient signingClient;
    private final String bucket;

    @org.springframework.beans.factory.annotation.Autowired
    public MinioObjectStorage(@Value("${growth.storage.endpoint}") String endpoint,
                              @Value("${growth.storage.access-key}") String accessKey,
                              @Value("${growth.storage.secret-key}") String secretKey,
                              @Value("${growth.storage.bucket}") String bucket,
                              @Value("${growth.storage.public-endpoint:${growth.storage.endpoint}}") String publicEndpoint,
                              @Value("${growth.storage.region:us-east-1}") String region) {
        this.client = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
        this.signingClient = MinioClient.builder().endpoint(publicEndpoint)
                .credentials(accessKey, secretKey).region(region).build();
        this.bucket = bucket;
    }

    public MinioObjectStorage(String endpoint, String accessKey, String secretKey, String bucket) {
        this(endpoint, accessKey, secretKey, bucket, endpoint, "us-east-1");
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
            return signingClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
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
    public Optional<StoredObject> stat(String key) {
        try {
            var result = client.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build());
            return Optional.of(new StoredObject(result.size(), result.contentType()));
        } catch (ErrorResponseException e) {
            if ("NoSuchKey".equals(e.errorResponse().code()) || "NoSuchObject".equals(e.errorResponse().code())) {
                return Optional.empty();
            }
            throw unavailable(key, e);
        } catch (Exception e) {
            throw unavailable(key, e);
        }
    }

    private BizException unavailable(String key, Exception cause) {
        log.error("读取对象信息失败: key={}", key, cause);
        return BizException.of(ErrorCode.STORAGE_UNAVAILABLE, "对象存储暂时不可用，请稍后重试");
    }

    @Override
    public void delete(String key) {
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
        } catch (Exception e) {
            log.warn("删除对象失败: {}", key, e);
        }
    }

    @Override
    public byte[] read(String key, int maxBytes) {
        try (var stream = client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build())) {
            byte[] data = stream.readNBytes(maxBytes + 1);
            if (data.length > maxBytes) throw new IllegalArgumentException("图片文件过大");
            return data;
        } catch (Exception e) {
            throw unavailable(key, e);
        }
    }

    @Override
    public void put(String key, byte[] data, String contentType) {
        try {
            client.putObject(PutObjectArgs.builder().bucket(bucket).object(key)
                    .stream(new java.io.ByteArrayInputStream(data), data.length, -1)
                    .contentType(contentType).build());
        } catch (Exception e) {
            throw unavailable(key, e);
        }
    }
}
