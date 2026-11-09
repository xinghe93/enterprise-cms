package com.xinghe.cms.security;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

/**
 * Redis 不透明 Bearer 会话（已确认方案 D1：固定 7200 秒，无滑动续期、无刷新令牌）。
 *
 * <p>每个 Token 独立生成；登录不踢出其他会话，退出只撤销当前 Token。
 * 每个用户持有一个「会话纪元」（epoch）字符串，会话的 Redis 值形如
 * {@code userId:epoch}。账号状态变更提交后须调用
 * {@link #revokeAllForUser(Long)} 写入一个全新的随机纪元，使此前签发的全部 Token
 * 与当前纪元不符；账号重新启用也不会恢复旧 Token。</p>
 *
 * <p>纪元是一次性随机值，不再用自增计数：即使版本键过期，下一次撤销写入的仍是
 * 全新值，不会「从 0 加回 1」让尚未过期、也未被清理的旧 1 号 Token 复活。
 * 签发时由一段 Lua 脚本原子完成「读取纪元 + 刷新纪元键 TTL」，把 TTL 续到 30 天，
 * 因此任何存活的 Token（最长 2 小时）都不会因版本键先过期而提前失效。</p>
 *
 * <p>登录采用「取纪元 → 复查账号状态 → 带纪元签发」：签发时若当前纪元已与登录
 * 开始时不同，说明期间发生了停用/撤销，直接拒绝签发，避免按新纪元签出在账号
 * 重新启用后仍然可用的 Token。详见 {@code AuthService#login}。</p>
 *
 * <p>纪元写入使用单条 {@code SET key value EX ttl}，本身就是原子操作，无需
 * INCR + EXPIRE 两步；Redis 访问异常一律向上抛出，由调用方转成 500，
 * 绝不静默放行或伪装成登录成功。</p>
 */
@Service
public class AuthSessionService {

    public static final long TTL_SECONDS = 7200L;

    private static final String SESSION_PREFIX = "cms:auth:session:";
    /** 沿用已有部署的键名；值由自增整数改为一次性随机纪元，旧会话自然失效。 */
    private static final String EPOCH_PREFIX = "cms:auth:generation:";
    private static final String INITIAL_EPOCH = "0";
    private static final Duration EPOCH_TTL = Duration.ofDays(30);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    /**
     * 原子读取纪元并续期：GET 与 EXPIRE 在 Redis 内一次完成，避免
     * 「读到纪元 → 键过期 → 才写入会话」造成刚签发的 Token 立即失效。
     */
    private static final DefaultRedisScript<String> READ_EPOCH = new DefaultRedisScript<>(
            "local current = redis.call('GET', KEYS[1])\n"
                    + "if current then redis.call('EXPIRE', KEYS[1], ARGV[1]) end\n"
                    + "return current or ARGV[2]", String.class);

    private final StringRedisTemplate redis;

    public AuthSessionService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 当前会话纪元；键不存在时返回固定的初始纪元，不创建键。 */
    public String currentEpoch(Long userId) {
        String value = redis.opsForValue().get(EPOCH_PREFIX + userId);
        return value == null || value.isBlank() ? INITIAL_EPOCH : value;
    }

    /**
     * 按登录开始时读到的纪元签发一个独立会话。
     *
     * @return 随机不透明 Token；期间纪元已变化（并发停用/撤销）时返回 {@code null}，
     *         调用方必须当作登录失败，不能换用新纪元重试签发。
     */
    public String issue(Long userId, String expectedEpoch) {
        String current = refreshEpoch(userId);
        if (!current.equals(expectedEpoch)) {
            return null;
        }
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = ENCODER.encodeToString(bytes);
        redis.opsForValue().set(SESSION_PREFIX + token,
                userId + ":" + expectedEpoch, TTL_SECONDS, TimeUnit.SECONDS);
        return token;
    }

    /** 解析有效会话；Token 不存在、已过期或纪元已变更时返回 null 并清理该 Token。 */
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
            String epoch = value.substring(separator + 1);
            if (epoch.isEmpty() || !epoch.equals(currentEpoch(userId))) {
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

    /** 写入全新的随机纪元，使指定用户的全部旧会话失效；重新启用账号也不会恢复。 */
    public void revokeAllForUser(Long userId) {
        redis.opsForValue().set(EPOCH_PREFIX + userId, newEpoch(), EPOCH_TTL);
    }

    private String refreshEpoch(Long userId) {
        String value = redis.execute(READ_EPOCH, List.of(EPOCH_PREFIX + userId),
                Long.toString(EPOCH_TTL.getSeconds()), INITIAL_EPOCH);
        return value == null || value.isBlank() ? INITIAL_EPOCH : value;
    }

    private String newEpoch() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
    }
}
