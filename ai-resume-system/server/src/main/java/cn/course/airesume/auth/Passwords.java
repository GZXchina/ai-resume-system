package cn.course.airesume.auth;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * PBKDF2-HMAC-SHA256 密码散列，格式：iterations:base64(salt):base64(hash)。
 * 与 db/data.sql 种子数据一致（120000 次迭代、32 字节输出）。
 */
public final class Passwords {
    private static final int ITERATIONS = 120000;
    private static final int KEY_BITS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    private Passwords() {}

    public static String hash(String password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return ITERATIONS + ":" + Base64.getEncoder().encodeToString(salt) + ":"
                + Base64.getEncoder().encodeToString(derive(password, salt, ITERATIONS));
    }

    public static boolean verify(String password, String stored) {
        try {
            String[] parts = stored.split(":");
            int it = Integer.parseInt(parts[0]);
            byte[] salt = Base64.getDecoder().decode(parts[1]);
            byte[] expect = Base64.getDecoder().decode(parts[2]);
            byte[] actual = derive(password, salt, it);
            return MessageDigest.isEqual(expect, actual);
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] derive(String password, byte[] salt, int iterations) {
        try {
            KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("密码散列计算失败", e);
        }
    }
}
