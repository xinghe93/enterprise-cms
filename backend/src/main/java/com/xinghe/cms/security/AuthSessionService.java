package com.xinghe.cms.security;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

/**
 * Redis 不透明 Bearer 会话（已确认方案 D1：固定 7200 秒，无滑动续期、无刷新令牌）。
 *
 * <p>每个 Token 独立生成；登录不踢出其他会话，退出只撤销当前 Token。
 * 每个用户持有一个「会话纪元」（epoch）随机串，会话的 Redis 值形如
 * {@code userId:epoch}。账号状态变更提交后须调用
 * {@link #revokeAllForUser(Long)} 写入一个全新的随机纪元，使此前签发的全部 Token
 * 与当前纪元不符；账号重新启用也不会恢复旧 Token。</p>
 *
 * <p><strong>纪元缺失即拒绝</strong>：纪元键不存在时不存在任何「默认版本」，
 * 校验一律失败。早期实现会在键缺失时回退到固定值 {@code "0"}，一旦纪元键因
 * 过期、主从切换或运维误删而消失，此前被撤销、但会话键尚未过期的 Token 会因
 * 重新匹配上这个固定值而复活。现在首次登录由 {@link #issue} 的 Lua 现场生成
 * 随机纪元并落库，键缺失只可能是「纪元已丢失」，按失效处理。</p>
 *
 * <p><strong>签发与撤销的原子性</strong>：「比对纪元 → 续期纪元键 TTL → 写入会话」
 * 在一段 Lua 脚本内一次完成，单个 Redis 往返内不可被其他命令插入。
 * 早期实现分两次往返，撤销恰好落在「比对通过」与「写入会话」之间时，登录会返回
 * 200 却拿到一个立即失效的 Token；现在该窗口不存在。</p>
 *
 * <p>会话有效期为契约规定的 7200 秒，不做滑动续期；纪元键 TTL 为 30 天并在每次
 * 成功登录时续期，因此存活的 Token（最长 2 小时）不会因纪元键先过期而提前失效。
 * Redis 访问异常一律向上抛出，由调用方转成 500，绝不静默放行或伪装成登录成功。</p>
 */
@Service
public class AuthSessionService {

    public static final long TTL_SECONDS = 7200L;

    private static final String SESSION_PREFIX = "cms:auth:session:";
    /** 沿用已有键名；值是一次性随机串，不做自增。 */
    private static final String EPOCH_PREFIX = "cms:auth:generation:";
    /** 登录开始时纪元键不存在，用空串占位；真实纪元永远是非空随机串，不会与之冲突。 */
    private static final String ABSENT_EPOCH = "";
    private static final Duration EPOCH_TTL = Duration.ofDays(30);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    /**
     * 原子签发：比对纪元 → 续期纪元键 → 写会话。
     *
     * <p>KEYS[1] 纪元键，KEYS[2] 会话键；ARGV 依次为：登录时读到的纪元
     * （空串表示当时不存在）、首次登录要写入的新纪元、纪元 TTL、会话值前缀
     * （{@code userId:}）、会话 TTL。返回 1 表示签发成功，0 表示纪元已变化或
     * 已丢失，调用方必须按登录失败处理。</p>
     */
    private static final DefaultRedisScript<Long> ISSUE_SESSION = new DefaultRedisScript<>(
            "local current = redis.call('GET', KEYS[1])\n"
                    + "if not current then\n"
                    + "  if ARGV[1] ~= '' then return 0 end\n"
                    + "  current = ARGV[2]\n"
                    + "  redis.call('SET', KEYS[1], current, 'EX', ARGV[3])\n"
                    + "else\n"
                    + "  if ARGV[1] == '' or current ~= ARGV[1] then return 0 end\n"
                    + "  redis.call('EXPIRE', KEYS[1], ARGV[3])\n"
                    + "end\n"
                    + "redis.call('SET', KEYS[2], ARGV[4] .. current, 'EX', ARGV[5])\n"
                    + "return 1", Long.class);

    private final StringRedisTemplate redis;

    public AuthSessionService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 读取登录开始时的会话纪元。
     *
     * @return 当前纪元；键不存在时为空，表示「尚无纪元」，<strong>不回退为任何固定值</strong>，
     *         调用方必须把「空」原样传给 {@link #issue(Long, Optional)}，
     *         由 Lua 判断能否现场创建。
     */
    public Optional<String> currentEpoch(Long userId) {
        String value = redis.opsForValue().get(EPOCH_PREFIX + userId);
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    /**
     * 按登录开始时读到的纪元签发一个独立会话（单段 Lua 原子完成比对与写入）。
     *
     * @param expectedEpoch {@link #currentEpoch(Long)} 的结果；为空表示登录开始时
     *                      尚无纪元键，允许由本次签发创建随机纪元
     * @return 随机不透明 Token；期间纪元已变化、已丢失或账号已被撤销时返回
     *         {@code null}，调用方必须当作登录失败，不能换用新纪元重试签发
     */
    public String issue(Long userId, Optional<String> expectedEpoch) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = ENCODER.encodeToString(bytes);
        Long result = redis.execute(ISSUE_SESSION,
                List.of(EPOCH_PREFIX + userId, SESSION_PREFIX + token),
                expectedEpoch.orElse(ABSENT_EPOCH),
                newEpoch(),
                Long.toString(EPOCH_TTL.getSeconds()),
                userId + ":",
                Long.toString(TTL_SECONDS));
        return result != null && result == 1L ? token : null;
    }

    /**
     * 解析有效会话；Token 不存在、已过期、纪元已变更或纪元键已丢失时返回 null
     * 并清理该 Token。
     *
     * <p>先读会话再读纪元，撤销发生在两次读取之间时第二次读到的已是新纪元，
     * 比对必然失败，因此不需要额外加锁。</p>
     */
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
            String current = redis.opsForValue().get(EPOCH_PREFIX + userId);
            if (epoch.isEmpty() || current == null || !epoch.equals(current)) {
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

    private String newEpoch() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
    }
}
