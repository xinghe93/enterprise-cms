package com.xinghe.cms.security;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.springframework.stereotype.Component;

/**
 * 账号密码哈希（A 主实现，B 审查；见《四人协作开发指南》第 5 节）。
 *
 * <p>选型：JDK 内置 PBKDF2WithHmacSHA256，随机 16 字节盐、210000 次迭代、256 位输出，
 * 不引入任何新的加密依赖。SunJCE 的 PBKDF2 按 UTF-8 处理 char[]，密码原样传入，
 * 不 trim、不做长度截断；存储格式为
 * {@code pbkdf2-sha256$<iterations>$<base64url(salt)>$<base64url(hash)>}，
 * 长度远小于 sys_user.password_hash 的 VARCHAR(255)。</p>
 *
 * <p>校验使用常量时间比较；编码格式异常只返回 false，不泄露账号是否存在。
 * 迭代次数与摘要长度按本系统生成格式设上限/定值校验：password_hash 若被篡改成超大
 * 迭代次数，登录时会造成 CPU 拒绝服务，因此不允许按外部数据任意放缩计算量。</p>
 */
@Component
public class PasswordHasher {

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final String FORMAT_PREFIX = "pbkdf2-sha256";
    private static final int ITERATIONS = 210_000;
    private static final int MAX_ITERATIONS = 1_000_000;
    private static final int SALT_LENGTH = 16;
    private static final int KEY_LENGTH = 256;
    private static final int DIGEST_LENGTH = KEY_LENGTH / 8;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    public String hash(String password) {
        if (password == null) {
            throw new IllegalArgumentException("password 不能为 null");
        }
        byte[] salt = new byte[SALT_LENGTH];
        RANDOM.nextBytes(salt);
        byte[] derived = derive(password.toCharArray(), salt, ITERATIONS, KEY_LENGTH);
        return FORMAT_PREFIX + "$" + ITERATIONS + "$"
                + ENCODER.encodeToString(salt) + "$" + ENCODER.encodeToString(derived);
    }

    public boolean matches(String password, String encoded) {
        if (password == null || encoded == null) {
            return false;
        }
        String[] parts = encoded.split("\\$", -1);
        if (parts.length != 4 || !FORMAT_PREFIX.equals(parts[0])) {
            return false;
        }
        try {
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = DECODER.decode(parts[2]);
            byte[] expected = DECODER.decode(parts[3]);
            if (iterations <= 0 || iterations > MAX_ITERATIONS
                    || salt.length != SALT_LENGTH || expected.length != DIGEST_LENGTH) {
                return false;
            }
            byte[] actual = derive(password.toCharArray(), salt, iterations, expected.length * 8);
            return MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static byte[] derive(char[] password, byte[] salt, int iterations, int keyLength) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, keyLength);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException exception) {
            throw new IllegalStateException("密码哈希算法不可用", exception);
        } finally {
            spec.clearPassword();
        }
    }
}
