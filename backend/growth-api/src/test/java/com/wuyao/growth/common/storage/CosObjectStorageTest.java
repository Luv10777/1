package com.wuyao.growth.common.storage;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.exception.CosServiceException;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.model.GetObjectRequest;
import com.qcloud.cos.model.COSObject;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.stream.Collectors;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CosObjectStorageTest {
    @Test void sdkSignsPublicHttpsUrlsWithBoundedExpiryAndEncodedKeysWithoutNetwork() {
        try (var storage = new ClosingStorage()) {
            String key = "cos/t1/voice-samples/测试 voice.wav";
            var get = URI.create(storage.value.presignGet(key, Duration.ofMinutes(10)));
            var put = URI.create(storage.value.presignPut(key, Duration.ofMinutes(10)));
            assertThat(get.getScheme()).isEqualTo("https");
            assertThat(get.getHost()).isEqualTo("test-1250000000.cos.ap-shanghai.myqcloud.com");
            assertThat(get.getPath()).isEqualTo("/" + key);
            String signature = URLDecoder.decode(get.getRawQuery().substring("sign=".length()), StandardCharsets.UTF_8);
            var query = Arrays.stream(signature.split("&")).map(pair -> pair.split("=", 2))
                    .collect(Collectors.toMap(pair -> pair[0], pair -> URLDecoder.decode(pair[1], StandardCharsets.UTF_8)));
            assertThat(query).containsKey("q-signature");
            assertThat(query.get("q-header-list")).doesNotContain("content-type");
            var time = query.get("q-sign-time").split(";");
            assertThat(Long.parseLong(time[1]) - Long.parseLong(time[0])).isBetween(599L, 601L);
            assertThat(put.getRawQuery()).isNotEqualTo(get.getRawQuery());
            assertThatThrownBy(() -> storage.value.presignGet(key, Duration.ofDays(1))).hasMessageContaining("1 小时");
        }
    }
    private static class ClosingStorage implements AutoCloseable {
        final CosObjectStorage value = new CosObjectStorage("test-id", "test-secret", "ap-shanghai", "test-1250000000");
        public void close() { value.close(); }
    }
    @Test void absentCredentialsFailBeforeReturningAnUploadTicket() {
        var storage = new CosObjectStorage("", "", "ap-shanghai", "test-1250000000");
        assertThatThrownBy(() -> storage.presignPut("cos/key", Duration.ofMinutes(10))).hasMessageContaining("COS_SECRET_ID");
    }
    @Test void metadataAndErrorsDoNotMistakePermissionOrBucketFailuresForMissingObjects() {
        var client = mock(COSClient.class);
        var storage = new CosObjectStorage(client, "bucket");
        var metadata = new ObjectMetadata(); metadata.setContentLength(1234); metadata.setContentType("audio/wav");
        when(client.getObjectMetadata("bucket", "key")).thenReturn(metadata);
        assertThat(storage.stat("key")).contains(new ObjectStorage.StoredObject(1234, "audio/wav"));
        for (String code : new String[]{"NoSuchKey", "NoSuchObject", "NoSuchBucket", "AccessDenied"}) {
            var error = new CosServiceException("signed-url-must-not-leak"); error.setErrorCode(code);
            doThrow(error).when(client).getObjectMetadata("bucket", "key");
            if (code.equals("NoSuchKey") || code.equals("NoSuchObject")) assertThat(storage.stat("key")).isEmpty();
            else assertThatThrownBy(() -> storage.stat("key")).hasMessageContaining("COS 请求失败").hasMessageNotContaining("signed-url");
        }
        doThrow(new IllegalStateException("network")).when(client).deleteObject("bucket", "key");
        assertThatThrownBy(() -> storage.delete("key")).hasMessageContaining("COS 请求失败");
    }
    @Test void head404UsesBoundedGetToDistinguishDeletedObjectFromMissingBucketOrDeniedAccess() {
        var client = mock(COSClient.class);
        var storage = new CosObjectStorage(client, "bucket");
        var headError = new CosServiceException("no body");
        headError.setStatusCode(404); headError.setErrorCode("404 Not Found");
        when(client.getObjectMetadata("bucket", "key")).thenThrow(headError);
        for (String code : new String[]{"NoSuchKey", "NoSuchBucket", "AccessDenied"}) {
            var getError = new CosServiceException("private request"); getError.setErrorCode(code);
            doThrow(getError).when(client).getObject(any(GetObjectRequest.class));
            if (code.equals("NoSuchKey")) assertThat(storage.stat("key")).isEmpty();
            else assertThatThrownBy(() -> storage.stat("key")).hasMessageContaining("COS 请求失败");
        }
        var requests = org.mockito.ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(client, times(3)).getObject(requests.capture());
        assertThat(requests.getValue().getRange()).containsExactly(0L, 0L);
        assertThat(requests.getValue().getKey()).isEqualTo("key");
        assertThat(requests.getValue().getBucketName()).isEqualTo("bucket");
    }
    @Test void reappearingObjectIsNotTreatedAsDeletedAndItsStreamIsClosed() throws Exception {
        var client = mock(COSClient.class);
        var storage = new CosObjectStorage(client, "bucket");
        var error = new CosServiceException("head missing"); error.setStatusCode(404);
        when(client.getObjectMetadata("bucket", "key")).thenThrow(error);
        var object = mock(COSObject.class);
        when(client.getObject(any(GetObjectRequest.class))).thenReturn(object);
        assertThatThrownBy(() -> storage.stat("key")).hasMessageContaining("COS 请求失败");
        verify(object).close();
    }
    @Test void persistedKeysKeepOldFilesInMinioAndNewFilesInCosForEveryOperation() {
        var minio = mock(MinioObjectStorage.class);
        var cos = mock(CosObjectStorage.class);
        var storage = new RoutedObjectStorage(minio, cos);
        var ttl = Duration.ofMinutes(5);
        for (String key : new String[]{"t1/voice-samples/old", "cos/t1/voice-samples/new"}) {
            storage.presignPut(key, ttl); storage.presignGet(key, ttl); storage.stat(key); storage.delete(key);
        }
        verify(minio).presignPut("t1/voice-samples/old", ttl);
        verify(minio).presignGet("t1/voice-samples/old", ttl);
        verify(minio).stat("t1/voice-samples/old"); verify(minio).delete("t1/voice-samples/old");
        verify(cos).presignPut("cos/t1/voice-samples/new", ttl);
        verify(cos).presignGet("cos/t1/voice-samples/new", ttl);
        verify(cos).stat("cos/t1/voice-samples/new"); verify(cos).delete("cos/t1/voice-samples/new");
        verifyNoMoreInteractions(minio, cos);
    }
}
