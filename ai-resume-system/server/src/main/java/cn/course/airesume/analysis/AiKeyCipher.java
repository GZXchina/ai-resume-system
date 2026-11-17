package cn.course.airesume.analysis;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * API-KEY 加密存储（v1.9）：AES-256-GCM，随机 IV，密文格式 {@code enc:v1:base64(iv||cipher||tag)}。
 *
 * <p>主密钥优先级：
 * <ol>
 *   <li>配置项 {@code ai.secret-key}（Base64 或任意口令，任意口令经 SHA-256 派生）；</li>
 *   <li>本机密钥文件 {@code ~/.ai-resume/ai-secret.key}（首次启动自动生成，32 字节随机数）；</li>
 *   <li>以上都不可用时使用进程内随机密钥（仅本次运行有效，重启后密文无法解密，会回落到环境变量密钥）。</li>
 * </ol>
 *
 * <p>解密失败不抛异常，由调用方按「未配置密钥」处理，避免损坏的历史数据阻塞服务启动。
 */
@Component
public class AiKeyCipher {
    private static final Logger log = LoggerFactory.getLogger(AiKeyCipher.class);

    private static final String PREFIX = "enc:v1:";
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;
    private static final Path KEY_FILE =
            Paths.get(System.getProperty("user.home", "."), ".ai-resume", "ai-secret.key");

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public AiKeyCipher(@Value("${ai.secret-key:}") String configuredSecret) {
        this.key = new SecretKeySpec(resolveKey(configuredSecret), "AES");
    }

    /** 是否为系统加密后的密文。 */
    public static boolean isEncrypted(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    /** 加密；空值原样返回空串。 */
    public String encrypt(String plain) {
        if (plain == null || plain.isBlank()) return "";
        try {
            byte[] iv = new byte[IV_LEN];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(encrypted, 0, out, iv.length, encrypted.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("API-KEY 加密失败：" + e.getMessage(), e);
        }
    }

    /**
     * 解密；空值返回空串，历史明文（未带前缀）原样返回，解密失败返回空串。
     */
    public String decrypt(String stored) {
        if (stored == null || stored.isBlank()) return "";
        String value = stored.trim();
        if (!isEncrypted(value)) return value;
        try {
            byte[] raw = Base64.getDecoder().decode(value.substring(PREFIX.length()));
            if (raw.length <= IV_LEN) return "";
            byte[] iv = new byte[IV_LEN];
            System.arraycopy(raw, 0, iv, 0, IV_LEN);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] plain = cipher.doFinal(raw, IV_LEN, raw.length - IV_LEN);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("API-KEY 解密失败（密钥文件可能已更换）：{}", e.getMessage());
            return "";
        }
    }

    private static byte[] resolveKey(String configured) {
        if (configured != null && !configured.isBlank()) {
            String s = configured.trim();
            try {
                byte[] raw = Base64.getDecoder().decode(s);
                if (raw.length == 16 || raw.length == 24 || raw.length == 32) return raw;
            } catch (IllegalArgumentException ignored) {
                // 非 Base64：按口令处理
            }
            return sha256(s.getBytes(StandardCharsets.UTF_8));
        }
        try {
            if (Files.isRegularFile(KEY_FILE)) {
                byte[] raw = Files.readAllBytes(KEY_FILE);
                if (raw.length == 16 || raw.length == 24 || raw.length == 32) return raw;
                log.warn("密钥文件长度异常（{} 字节），将重新生成", raw.length);
            }
            byte[] raw = new byte[32];
            new SecureRandom().nextBytes(raw);
            Files.createDirectories(KEY_FILE.getParent());
            Files.write(KEY_FILE, raw,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            restrictPermissions(KEY_FILE);
            log.info("已生成本机 API-KEY 加密密钥：{}", KEY_FILE);
            return raw;
        } catch (Exception e) {
            log.warn("本机密钥文件不可用（{}），本次运行使用临时密钥", e.getMessage());
            byte[] raw = new byte[32];
            new SecureRandom().nextBytes(raw);
            return raw;
        }
    }

    private static void restrictPermissions(Path file) {
        try {
            Files.setPosixFilePermissions(file, Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (Exception ignored) {
            // Windows 不支持 POSIX 权限，忽略
        }
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 密钥掩码：仅保留前缀 3 位与后 4 位，供前端展示。 */
    public static String mask(String plain) {
        if (plain == null || plain.isBlank()) return "";
        String s = plain.trim();
        if (s.length() <= 8) return "****";
        return s.substring(0, 3) + "****" + s.substring(s.length() - 4);
    }
}
