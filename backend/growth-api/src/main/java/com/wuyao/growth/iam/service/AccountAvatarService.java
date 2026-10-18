package com.wuyao.growth.iam.service;

import com.wuyao.growth.common.storage.ObjectStorage;
import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import com.wuyao.growth.iam.dto.AccountDtos;
import com.wuyao.growth.iam.entity.User;
import com.wuyao.growth.iam.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** 头像直传后校验并生成独立 PNG；上传签名不能覆盖已经保存的头像。 */
@Service
@RequiredArgsConstructor
public class AccountAvatarService {
    static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final Duration UPLOAD_TTL = Duration.ofMinutes(15);
    private final UserRepository users;
    private final ObjectStorage storage;

    public String url(String key) {
        return key == null ? null : storage.presignGet(key, Duration.ofDays(7));
    }

    @Transactional
    public AccountDtos.AvatarUploadTicket presign(Long userId) {
        User user = activeForUpdate(userId);
        String key = "t%d/avatars/%d/upload-%s".formatted(user.getTenantId(), userId, UUID.randomUUID());
        Instant expires = Instant.now().plus(UPLOAD_TTL);
        String uploadUrl = storage.presignPut(key, UPLOAD_TTL);
        user.setAvatarUploadKey(key);
        user.setAvatarUploadExpiresAt(expires);
        return new AccountDtos.AvatarUploadTicket(key, uploadUrl, expires);
    }

    @Transactional
    public AccountDtos.Profile save(Long userId, String uploadKey) {
        User user = activeForUpdate(userId);
        if (!uploadKey.equals(user.getAvatarUploadKey()) || user.getAvatarUploadExpiresAt() == null
                || !user.getAvatarUploadExpiresAt().isAfter(Instant.now())) {
            throw invalid("头像上传已过期或已被替换，请重新选择图片");
        }
        var object = storage.stat(uploadKey).orElseThrow(() -> invalid("头像尚未上传完成，请重试"));
        if (object.sizeBytes() <= 0 || object.sizeBytes() > MAX_BYTES) throw invalid("头像图片不能超过 2 MB");
        byte[] normalized = normalize(storage.read(uploadKey, MAX_BYTES));
        String key = "t%d/avatars/%d/%s.png".formatted(user.getTenantId(), userId, UUID.randomUUID());
        storage.put(key, normalized, "image/png");
        String avatarUrl = url(key);
        user.setAvatarKey(key);
        user.setAvatarUploadKey(null);
        user.setAvatarUploadExpiresAt(null);
        user.setUpdatedAt(Instant.now());
        return AccountDtos.Profile.of(user, avatarUrl);
    }

    private User activeForUpdate(Long userId) {
        Long tenantId = TenantContext.require();
        return users.findForUpdate(userId)
                .filter(user -> tenantId.equals(user.getTenantId()) && "ACTIVE".equals(user.getStatus()))
                .orElseThrow(() -> BizException.of(ErrorCode.FORBIDDEN, "账号不可用或不属于当前商户"));
    }

    private byte[] normalize(byte[] bytes) {
        if (bytes.length == 0 || bytes.length > MAX_BYTES) throw invalid("头像图片不能超过 2 MB");
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw invalid("请选择有效的 JPG 或 PNG 图片");
            ImageReader reader = readers.next();
            try {
                if (!Set.of("png", "jpeg").contains(reader.getFormatName().toLowerCase(java.util.Locale.ROOT))) {
                    throw invalid("请选择有效的 JPG 或 PNG 图片");
                }
                reader.setInput(input, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                // 在解码前限制像素，避免小文件展开占用大量内存。
                if (width < 1 || height < 1 || width > 4096 || height > 4096) {
                    throw invalid("头像尺寸不能超过 4096 × 4096 像素，请缩小后重试");
                }
                BufferedImage source = reader.read(0);
                BufferedImage avatar = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
                var graphics = avatar.createGraphics();
                try {
                    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                    int side = Math.min(width, height), x = (width - side) / 2, y = (height - side) / 2;
                    graphics.drawImage(source, 0, 0, 256, 256, x, y, x + side, y + side, null);
                } finally {
                    graphics.dispose();
                }
                var output = new ByteArrayOutputStream();
                ImageIO.write(avatar, "png", output);
                return output.toByteArray();
            } finally {
                reader.dispose();
            }
        } catch (IOException | IllegalArgumentException e) {
            throw invalid("图片无法读取，请选择有效的 JPG 或 PNG 图片");
        }
    }

    private BizException invalid(String message) {
        return BizException.of(ErrorCode.BAD_REQUEST, message);
    }
}
