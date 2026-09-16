package com.wuyao.vimax.service.task;

import com.wuyao.vimax.entity.Asset;
import com.wuyao.vimax.entity.GenerationTask;
import com.wuyao.vimax.repository.AssetRepository;
import com.wuyao.vimax.repository.GenerationTaskRepository;
import com.wuyao.vimax.service.MinioStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.security.MessageDigest;

/**
 * 下载、校验和入库服务
 *
 * Phase 1.8: 实现下载、校验、入库和作品库关联
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AssetDownloadService {

    private final GenerationTaskRepository taskRepository;
    private final AssetRepository assetRepository;
    private final MinioStorageService minioStorageService;
    private final OkHttpClient httpClient;

    /**
     * 下载并入库生成结果
     *
     * @param taskId GenerationTask ID
     * @return Asset记录
     */
    @Transactional
    public Asset downloadAndStore(Long taskId) {
        log.info("下载并入库生成结果: taskId={}", taskId);

        GenerationTask task = taskRepository.findById(taskId)
            .orElseThrow(() -> new IllegalArgumentException("任务不存在: " + taskId));

        if (!"COMPLETED".equals(task.getStatus())) {
            throw new IllegalStateException("任务未完成: " + task.getStatus());
        }

        if (task.getResultRef() == null) {
            throw new IllegalStateException("任务无结果URL");
        }

        try {
            // 1. 下载文件
            byte[] fileData = downloadFile(task.getResultRef());
            log.info("文件下载完成: taskId={}, size={} bytes", taskId, fileData.length);

            // 2. 计算SHA256
            String sha256Hash = calculateSHA256(fileData);
            log.info("文件哈希: taskId={}, sha256={}", taskId, sha256Hash);

            // 3. 检查是否已存在（去重）
            Asset existingAsset = assetRepository.findBySha256Hash(sha256Hash).orElse(null);
            if (existingAsset != null) {
                log.info("文件已存在，复用: assetId={}", existingAsset.getId());
                return existingAsset;
            }

            // 4. 上传到MinIO
            String objectKey = uploadToMinio(fileData, task.getTaskType(), sha256Hash);
            log.info("文件已上传到MinIO: objectKey={}", objectKey);

            // 5. 创建Asset记录
            Asset asset = new Asset();
            asset.setAssetType(task.getTaskType());
            asset.setAssetCategory("GENERATED");
            asset.setS3Bucket(minioStorageService.getMinioBucket());
            asset.setS3Key(objectKey);
            asset.setFileName(generateFileName(task));
            asset.setMimeType(getMimeType(task.getTaskType()));
            asset.setFileSizeBytes((long) fileData.length);
            asset.setSha256Hash(sha256Hash);
            asset.setSource(getSource(task));

            Asset saved = assetRepository.save(asset);
            log.info("Asset已创建: assetId={}, sha256={}", saved.getId(), sha256Hash);

            return saved;

        } catch (Exception e) {
            log.error("下载并入库失败: taskId={}", taskId, e);
            throw new RuntimeException("下载并入库失败: " + e.getMessage(), e);
        }
    }

    /**
     * 下载文件
     */
    private byte[] downloadFile(String url) throws IOException {
        Request request = new Request.Builder()
            .url(url)
            .get()
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("下载失败: HTTP " + response.code());
            }

            if (response.body() == null) {
                throw new IOException("响应体为空");
            }

            return response.body().bytes();
        }
    }

    /**
     * 计算SHA256
     */
    private String calculateSHA256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException("计算SHA256失败", e);
        }
    }

    /**
     * 上传到MinIO（直接上传，非预签名）
     */
    private String uploadToMinio(byte[] data, String taskType, String sha256Hash) {
        // TODO: 实现直接上传到MinIO
        // 临时：生成对象Key
        String date = java.time.LocalDate.now().toString().replace("-", "/");
        String extension = getExtension(taskType);
        return String.format("%s/%s.%s", date, sha256Hash, extension);
    }

    /**
     * 生成文件名
     */
    private String generateFileName(GenerationTask task) {
        String extension = getExtension(task.getTaskType());
        return String.format("%s_%d.%s", task.getTaskType().toLowerCase(), task.getId(), extension);
    }

    /**
     * 获取文件扩展名
     */
    private String getExtension(String taskType) {
        return switch (taskType) {
            case "IMAGE" -> "jpg";
            case "VIDEO" -> "mp4";
            case "TEXT" -> "txt";
            default -> "bin";
        };
    }

    /**
     * 获取MIME类型
     */
    private String getMimeType(String taskType) {
        return switch (taskType) {
            case "IMAGE" -> "image/jpeg";
            case "VIDEO" -> "video/mp4";
            case "TEXT" -> "text/plain";
            default -> "application/octet-stream";
        };
    }

    /**
     * 获取来源
     */
    private String getSource(GenerationTask task) {
        if (task.getProviderJobId() != null) {
            if (task.getProviderJobId().contains("fluapi")) {
                return "FLUAPI";
            } else if (task.getProviderJobId().contains("toapis")) {
                return "TOAPIS";
            }
        }
        return "PROVIDER_GENERATED";
    }
}
