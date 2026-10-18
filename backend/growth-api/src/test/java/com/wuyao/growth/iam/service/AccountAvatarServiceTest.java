package com.wuyao.growth.iam.service;

import com.wuyao.growth.common.storage.ObjectStorage;
import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.iam.entity.User;
import com.wuyao.growth.iam.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AccountAvatarServiceTest {
    final UserRepository users = mock(UserRepository.class);
    final ObjectStorage storage = mock(ObjectStorage.class);
    final AccountAvatarService avatars = new AccountAvatarService(users, storage);
    User user;

    @BeforeEach void setup() {
        TenantContext.set(2L);
        user = new User();
        user.setId(1L);
        user.setTenantId(2L);
        user.setAvatarKey("previous.png");
        user.setAvatarUploadKey("pending");
        user.setAvatarUploadExpiresAt(Instant.now().plusSeconds(60));
        when(users.findForUpdate(1L)).thenReturn(Optional.of(user));
        when(storage.presignGet(anyString(), eq(Duration.ofDays(7)))).thenAnswer(call -> "https://test/" + call.getArgument(0));
    }

    @AfterEach void clearTenant() { TenantContext.clear(); }

    @Test void scopesTicketsAndRejectsOldOrExpiredUploads() {
        var ticket = avatars.presign(1L);
        assertThat(ticket.uploadKey()).startsWith("t2/avatars/1/upload-");
        assertThat(user.getAvatarUploadKey()).isEqualTo(ticket.uploadKey());
        assertThatThrownBy(() -> avatars.save(1L, "pending")).isInstanceOf(BizException.class);
        user.setAvatarUploadExpiresAt(Instant.now().minusSeconds(1));
        assertThatThrownBy(() -> avatars.save(1L, ticket.uploadKey())).isInstanceOf(BizException.class);
        verify(storage, never()).stat(anyString());
        assertThat(user.getAvatarKey()).isEqualTo("previous.png");
    }

    @Test void rejectsOtherTenantsAndInactiveAccountsBeforeUsingStorage() {
        TenantContext.set(3L);
        assertThatThrownBy(() -> avatars.presign(1L)).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> avatars.save(1L, "pending")).isInstanceOf(BizException.class);
        TenantContext.set(2L);
        user.setStatus("DISABLED");
        assertThatThrownBy(() -> avatars.presign(1L)).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> avatars.save(1L, "pending")).isInstanceOf(BizException.class);
        verifyNoInteractions(storage);
    }

    @Test void rejectsMissingOversizedCorruptAndExcessiveDimensionFilesWithoutReplacingAvatar() throws Exception {
        when(storage.stat("pending")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> avatars.save(1L, "pending")).isInstanceOf(BizException.class);
        when(storage.stat("pending")).thenReturn(Optional.of(new ObjectStorage.StoredObject(2_097_153, "image/png")));
        assertThatThrownBy(() -> avatars.save(1L, "pending")).isInstanceOf(BizException.class);
        verify(storage, never()).read(anyString(), anyInt());
        when(storage.stat("pending")).thenReturn(Optional.of(new ObjectStorage.StoredObject(100, "image/png")));
        for (byte[] data : new byte[][] { "not an image".getBytes(), image(4097, 1, "png"), image(10, 10, "gif") }) {
            when(storage.read("pending", AccountAvatarService.MAX_BYTES)).thenReturn(data);
            assertThatThrownBy(() -> avatars.save(1L, "pending")).isInstanceOf(BizException.class);
        }
        verify(storage, never()).put(anyString(), any(byte[].class), anyString());
        assertThat(user.getAvatarKey()).isEqualTo("previous.png");
    }

    @Test void normalizesPngAndJpegIntoAnIndependentCroppedImageAndConsumesTheTicket() throws Exception {
        for (String format : new String[] { "png", "jpeg" }) {
            byte[] data = image(80, 40, format);
            user.setAvatarUploadKey("pending");
            user.setAvatarUploadExpiresAt(Instant.now().plusSeconds(60));
            when(storage.stat("pending")).thenReturn(Optional.of(new ObjectStorage.StoredObject(data.length, "image/" + format)));
            when(storage.read("pending", AccountAvatarService.MAX_BYTES)).thenReturn(data);
            var result = avatars.save(1L, "pending");
            assertThat(user.getAvatarKey()).startsWith("t2/avatars/1/").endsWith(".png").doesNotContain("upload-");
            assertThat(result.avatarUrl()).isEqualTo("https://test/" + user.getAvatarKey());
            assertThat(user.getAvatarUploadKey()).isNull();
            assertThat(user.getAvatarUploadExpiresAt()).isNull();
            assertThatThrownBy(() -> avatars.save(1L, "pending")).isInstanceOf(BizException.class);
        }
        var data = ArgumentCaptor.forClass(byte[].class);
        verify(storage, times(2)).put(anyString(), data.capture(), eq("image/png"));
        for (byte[] bytes : data.getAllValues()) {
            var image = ImageIO.read(new ByteArrayInputStream(bytes));
            assertThat(image.getWidth()).isEqualTo(256);
            assertThat(image.getHeight()).isEqualTo(256);
        }
    }

    private byte[] image(int width, int height, String format) throws Exception {
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), format, output);
        return output.toByteArray();
    }
}
