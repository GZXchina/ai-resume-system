package cn.course.airesume.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * API-KEY 加密单元测试（v1.9）：AES-256-GCM 往返、随机 IV、密文格式、历史明文与掩码。
 */
class AiKeyCipherTest {

    private AiKeyCipher cipher;

    @BeforeEach
    void setUp() {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) key[i] = (byte) (i * 7 + 1);
        cipher = new AiKeyCipher(Base64.getEncoder().encodeToString(key));
    }

    @Test
    @DisplayName("加密后可解密还原，且带 enc:v1: 前缀")
    void encryptAndDecryptRoundTrip() {
        String plain = "sk-abcdefghijklmnop123456";
        String stored = cipher.encrypt(plain);
        assertTrue(AiKeyCipher.isEncrypted(stored));
        assertTrue(stored.startsWith("enc:v1:"));
        assertNotEquals(plain, stored);
        assertFalse(stored.contains(plain));
        assertEquals(plain, cipher.decrypt(stored));
    }

    @Test
    @DisplayName("同一明文两次加密结果不同（随机 IV）")
    void randomIvProducesDifferentCipherText() {
        String plain = "sk-abcdefghijklmnop123456";
        assertNotEquals(cipher.encrypt(plain), cipher.encrypt(plain));
    }

    @Test
    @DisplayName("历史明文（未加密）原样返回，便于平滑迁移")
    void legacyPlainTextIsReturnedAsIs() {
        assertEquals("sk-legacy-plain-text", cipher.decrypt("sk-legacy-plain-text"));
    }

    @Test
    @DisplayName("空值与损坏密文返回空串，不抛异常")
    void blankAndBrokenValue() {
        assertEquals("", cipher.encrypt(""));
        assertEquals("", cipher.encrypt(null));
        assertEquals("", cipher.decrypt(null));
        assertEquals("", cipher.decrypt("enc:v1:not-base64!!"));
        assertEquals("", cipher.decrypt("enc:v1:" + Base64.getEncoder().encodeToString(new byte[8])));
        assertEquals("", cipher.decrypt("enc:v1:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"));
    }

    @Test
    @DisplayName("掩码只保留前 3 位与后 4 位")
    void mask() {
        assertEquals("sk-****cdef", AiKeyCipher.mask("sk-abcdefghijcdef"));
        assertEquals("****", AiKeyCipher.mask("short"));
        assertEquals("", AiKeyCipher.mask(null));
        assertEquals("", AiKeyCipher.mask("   "));
    }
}
