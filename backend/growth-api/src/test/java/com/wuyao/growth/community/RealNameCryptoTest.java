package com.wuyao.growth.community;

import com.wuyao.growth.common.web.BizException;
import org.junit.jupiter.api.Test;
import java.util.Base64;

import static org.assertj.core.api.Assertions.*;

class RealNameCryptoTest {
    private final String key = Base64.getEncoder().encodeToString(new byte[32]);

    @Test void identityCiphertextsAreRandomizedAndBoundToTenantUserAndField() {
        var crypto = new RealNameCrypto(key);
        byte[] value = crypto.encrypt("仅用于测试", 1L, 2L, "name");
        assertThat(crypto.decrypt(value, 1L, 2L, "name")).isEqualTo("仅用于测试");
        assertThat(value).isNotEqualTo(crypto.encrypt("仅用于测试", 1L, 2L, "name"));
        assertThatThrownBy(() -> crypto.decrypt(value, 9L, 2L, "name")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> crypto.decrypt(value, 1L, 9L, "name")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> crypto.decrypt(value, 1L, 2L, "number")).isInstanceOf(IllegalStateException.class);
        value[value.length - 1] ^= 1;
        assertThatThrownBy(() -> crypto.decrypt(value, 1L, 2L, "name")).isInstanceOf(IllegalStateException.class);
    }

    @Test void absentOrMalformedKeysNeverAllowUnencryptedStorage() {
        assertThatThrownBy(() -> new RealNameCrypto("").encrypt("test", 1L, 2L, "name")).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> new RealNameCrypto("invalid-key")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new RealNameCrypto(Base64.getEncoder().encodeToString(new byte[16]))).isInstanceOf(IllegalStateException.class);
    }

    @Test void idValidationRejectsBadChecksumsAndImpossibleBirthDates() {
        assertThatCode(() -> RealNameService.validateIdNumber("11010519491231002X")).doesNotThrowAnyException();
        assertThatThrownBy(() -> RealNameService.validateIdNumber("110105194912310021")).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> RealNameService.validateIdNumber("11010519490231002X")).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> RealNameService.validateIdNumber("123")).isInstanceOf(BizException.class);
    }
}
