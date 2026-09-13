package com.wuyao.vimax.service;

import io.minio.*;
import io.minio.http.Method;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * MinIO 存储服务
 *
 * 负责文件上传、下载、校验
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MinioStorageService {

    private final MinioClient minioClient;
    private final String minioBucket;

    /**
     * 获取 Bucket 名称
     */
    public String getMinioBucket() {
        return minioBucket;
    }

    /**
     * 生成上传预签名 URL
     *
     * @param fileName 原始文件名
     * @param contentType MIME类型
     * @return 预签名URL和对象Key
     */
    public UploadUrlResult generateUploadUrl(String fileName, String contentType) {
        try {
            // 确保 bucket 存在
            ensureBucketExists();

            // 生成唯一的对象Key
            String objectKey = generateObjectKey(fileName);

            // 生成预签名上传 URL（15分钟有效）
            String uploadUrl = minioClient.getPresignedObjectUrl(
                GetPresignedObjectUrlArgs.builder()
                    .method(Method.PUT)
                    .bucket(minioBucket)
                    .object(objectKey)
                    .expiry(15, TimeUnit.MINUTES)
                    .build()
            );

            log.info("生成上传URL: bucket={}, key={}", minioBucket, objectKey);

            return new UploadUrlResult(uploadUrl, objectKey, minioBucket);

        } catch (Exception e) {
            log.error("生成上传URL失败", e);
            throw new RuntimeException("生成上传URL失败: " + e.getMessage(), e);
        }
    }

    /**
     * 生成下载预签名 URL
     *
     * @param objectKey 对象Key
     * @return 预签名URL（24小时有效）
     */
    public String generateDownloadUrl(String objectKey) {
        try {
            String downloadUrl = minioClient.getPresignedObjectUrl(
                GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(minioBucket)
                    .object(objectKey)
                    .expiry(24, TimeUnit.HOURS)
                    .build()
            );

            log.debug("生成下载URL: key={}", objectKey);
            return downloadUrl;

        } catch (Exception e) {
            log.error("生成下载URL失败: key={}", objectKey, e);
            throw new RuntimeException("生成下载URL失败: " + e.getMessage(), e);
        }
    }

    /**
     * 获取对象元数据（验证文件是否存在）
     *
     * @param objectKey 对象Key
     * @return 对象统计信息
     */
    public ObjectStat getObjectStat(String objectKey) {
        try {
            StatObjectResponse stat = minioClient.statObject(
                StatObjectArgs.builder()
                    .bucket(minioBucket)
                    .object(objectKey)
                    .build()
            );

            return new ObjectStat(
                stat.size(),
                stat.contentType(),
                stat.etag()
            );

        } catch (Exception e) {
            log.error("获取对象元数据失败: key={}", objectKey, e);
            throw new RuntimeException("对象不存在或无法访问: " + objectKey, e);
        }
    }

    /**
     * 删除对象
     *
     * @param objectKey 对象Key
     */
    public void deleteObject(String objectKey) {
        try {
            minioClient.removeObject(
                RemoveObjectArgs.builder()
                    .bucket(minioBucket)
                    .object(objectKey)
                    .build()
            );

            log.info("删除对象成功: key={}", objectKey);

        } catch (Exception e) {
            log.error("删除对象失败: key={}", objectKey, e);
            throw new RuntimeException("删除对象失败: " + e.getMessage(), e);
        }
    }

    /**
     * 确保 Bucket 存在
     */
    private void ensureBucketExists() {
        try {
            boolean exists = minioClient.bucketExists(
                BucketExistsArgs.builder()
                    .bucket(minioBucket)
                    .build()
            );

            if (!exists) {
                log.info("创建 Bucket: {}", minioBucket);
                minioClient.makeBucket(
                    MakeBucketArgs.builder()
                        .bucket(minioBucket)
                        .build()
                );
            }

        } catch (Exception e) {
            log.error("检查或创建 Bucket 失败", e);
            throw new RuntimeException("Bucket 初始化失败: " + e.getMessage(), e);
        }
    }

    /**
     * 生成唯一的对象Key
     *
     * 格式: {date}/{uuid}/{filename}
     * 例如: 2026/08/26/abc123-def456/image.jpg
     */
    private String generateObjectKey(String fileName) {
        String date = java.time.LocalDate.now().toString().replace("-", "/");
        String uuid = UUID.randomUUID().toString();
        String sanitizedFileName = sanitizeFileName(fileName);

        return String.format("%s/%s/%s", date, uuid, sanitizedFileName);
    }

    /**
     * 清理文件名（移除特殊字符）
     */
    private String sanitizeFileName(String fileName) {
        return fileName.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    /**
     * 计算文件 SHA256
     *
     * @param data 文件数据
     * @return SHA256 哈希
     */
    public static String calculateSHA256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);

            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }

            return hexString.toString();

        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 算法不可用", e);
        }
    }

    // 返回类型
    public record UploadUrlResult(String uploadUrl, String objectKey, String bucket) {}
    public record ObjectStat(long size, String contentType, String etag) {}
}
