package com.xinghe.cms.security;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Redis 不透明 Bearer 会话（已确认方案 D1：固定 7200 秒，无滑动续期、无刷新令牌）。
 *
 * <p>每个 Token 独立生成，登录不踢出其他会话；退出只撤销当前 Token。
 * 用 per-user 的 generation 值使「停用账号 → 全部旧会话失效」可以一次完成，
 * 且重新启用后旧 Token 仍与当前 generation 不符，不会恢复。
 * 账号启停接口提交后须调用 {@link #revokeAllForUser(Long)}。</p>
 *
 * <p>Redis 访问异常一律向上抛出，由调用方转成 500；绝不静默放行或伪装成登录成功。</p>
 */
@Service
public class AuthSessionService {

    public static final long TTL_SECONDS = 7200L;

    private static final String SESSION_PREFIX = "cms:auth:session:";
    private static final String GENERATION_PREFIX = "cms:auth:generation:";
    private static final Duration GENERATION_TTL = Duration.ofDays(30);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final StringRedisTemplate redis;

    public AuthSessionService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 签发一个独立会话，返回随机不透明 Token。 */
    public String issue(Long userId) {
        long generation = currentGeneration(userId);
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = ENCODER.encodeToString(bytes);
        redis.opsForValue().set(SESSION_PREFIX + token,
                userId + ":" + generation, TTL_SECONDS, TimeUnit.SECONDS);
        return token;
    }

    /** 解析有效会话；Token 不存在、已过期或 generation 已变更时返回 null 并清理该 Token。 */
    public Long resolve(String token) {
        String value = redis.opsForValue().get(SESSION_PREFIX + token);
        if (value == null) {
            return null;
        }
        int separator = value.indexOf(':');
        if (separator <= 0) {
            revoke(token);
            return null;
        }
        try {
            long userId = Long.parseLong(value.substring(0, separator));
            long generation = Long.parseLong(value.substring(separator + 1));
            if (generation != currentGeneration(userId)) {
                revoke(token);
                return null;
            }
            return userId;
        } catch (NumberFormatException exception) {
            revoke(token);
            return null;
        }
    }

    /** 只撤销当前会话。 */
    public void revoke(String token) {
        redis.delete(SESSION_PREFIX + token);
    }

    /** 使指定用户的全部旧会话失效；重新启用账号也不会恢复旧 Token。 */
    public void revokeAllForUser(Long userId) {
        String key = GENERATION_PREFIX + userId;
        redis.opsForValue().increment(key);
        redis.expire(key, GENERATION_TTL);
    }

    private long currentGeneration(Long userId) {
        String value = redis.opsForValue().get(GENERATION_PREFIX + userId);
        if (value == null || value.isBlank()) {
            return 0L;
        }
        return Long.parseLong(value);
    }
}
