package com.wuyao.growth.common.storage;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.exception.CosServiceException;
import com.qcloud.cos.http.HttpMethodName;
import com.qcloud.cos.http.HttpProtocol;
import com.qcloud.cos.model.GeneratePresignedUrlRequest;
import com.qcloud.cos.model.GetObjectRequest;
import com.qcloud.cos.region.Region;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

/** Private COS objects accessed through short-lived HTTPS signatures. */
@Component
public class CosObjectStorage implements ObjectStorage {
    private final COSClient client;
    private final String bucket;

    @Autowired
    public CosObjectStorage(@Value("${growth.storage.cos.secret-id:}") String id,
                            @Value("${growth.storage.cos.secret-key:}") String secret,
                            @Value("${growth.storage.cos.region:ap-shanghai}") String region,
                            @Value("${growth.storage.cos.bucket:}") String bucket) {
        this.bucket = bucket;
        if (id.isBlank() || secret.isBlank() || region.isBlank() || bucket.isBlank()) {
            client = null;
        } else {
            var config = new ClientConfig(new Region(region));
            config.setHttpProtocol(HttpProtocol.https);
            config.setConnectionTimeout(10000);
            config.setSocketTimeout(30000);
            client = new COSClient(new BasicCOSCredentials(id, secret), config);
        }
    }
    CosObjectStorage(COSClient client, String bucket) { this.client = client; this.bucket = bucket; }
    public String presignPut(String key, Duration ttl) { return presign(key, ttl, HttpMethodName.PUT); }
    public String presignGet(String key, Duration ttl) { return presign(key, ttl, HttpMethodName.GET); }
    private String presign(String key, Duration ttl, HttpMethodName method) {
        requireConfigured();
        if (ttl.toSeconds() < 1 || ttl.compareTo(Duration.ofHours(1)) > 0)
            throw BizException.of(ErrorCode.BAD_REQUEST, "COS 临时链接有效期必须在 1 秒到 1 小时之间");
        try {
            var request = new GeneratePresignedUrlRequest(bucket, key, method);
            request.setExpiration(Date.from(Instant.now().plus(ttl)));
            // Browser PUT supplies the actual Content-Type; it is not part of the signature.
            return client.generatePresignedUrl(request).toExternalForm();
        } catch (Exception error) { throw unavailable(); }
    }
    public Optional<StoredObject> stat(String key) {
        requireConfigured();
        try {
            var metadata = client.getObjectMetadata(bucket, key);
            return Optional.of(new StoredObject(metadata.getContentLength(), metadata.getContentType()));
        } catch (CosServiceException error) {
            if (missingObject(error)) return Optional.empty();
            // COS HEAD has no XML error body: SDK reports "404 Not Found".
            // A ranged GET distinguishes NoSuchKey from NoSuchBucket without
            // requiring any extra bucket-level permission or downloading the file.
            if (error.getStatusCode() == 404 && (error.getErrorCode() == null
                    || "404 Not Found".equals(error.getErrorCode()))) return confirmMissing(key);
            throw unavailable();
        } catch (Exception error) { throw unavailable(); }
    }
    public void delete(String key) {
        requireConfigured();
        try { client.deleteObject(bucket, key); }
        catch (CosServiceException error) {
            if (!missingObject(error)) throw unavailable();
        } catch (Exception error) { throw unavailable(); }
    }
    private void requireConfigured() {
        if (client == null) throw BizException.of(ErrorCode.STORAGE_UNAVAILABLE,
                "腾讯云 COS 尚未配置完整，请在后端填写 COS_SECRET_ID、COS_SECRET_KEY、COS_REGION 和 COS_BUCKET");
    }
    private BizException unavailable() {
        // SDK exceptions can include signed URLs; never return or log them.
        return BizException.of(ErrorCode.STORAGE_UNAVAILABLE, "腾讯云 COS 请求失败，请检查凭证、存储桶地域和对象读写权限");
    }
    private boolean missingObject(CosServiceException error) {
        return "NoSuchKey".equals(error.getErrorCode()) || "NoSuchObject".equals(error.getErrorCode());
    }
    private Optional<StoredObject> confirmMissing(String key) {
        var request = new GetObjectRequest(bucket, key);
        request.setRange(0, 0);
        try (var object = client.getObject(request)) {
            // The object may have been recreated between HEAD and GET. Retry the
            // operation later rather than treating an existing object as deleted.
            throw unavailable();
        } catch (CosServiceException error) {
            if (missingObject(error)) return Optional.empty();
            throw unavailable();
        } catch (Exception error) { throw unavailable(); }
    }
    @PreDestroy public void close() { if (client != null) client.shutdown(); }
}
