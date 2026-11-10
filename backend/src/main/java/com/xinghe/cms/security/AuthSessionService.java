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
 * 校验与签发一律失败。早期实现会在键缺失时回退到固定值 {@code "0"}，一旦纪元键因
 * 过期、主从切换或运维误删而消失，此前被撤销、但会话键尚未过期的 Token 会因
 * 重新匹配上这个固定值而复活。</p>
 *
 * <p><strong>并发首次登录</strong>：登录在密码校验通过后调用
 * {@link #ensureEpoch(Long)}，用一段 Lua 原子完成「读取或初始化」并返回<strong>确定的非空纪元</strong>。
 * 多个并发登录要么读到同一个已存在的纪元，要么由第一个创建、其余读到同一个值，
 * 不会出现「都读到空纪元、只有一个能签发」而让正确的账号密码收到 401。</p>
 *
 * <p><strong>签发与撤销的原子性</strong>：{@link #issue(Long, String)} 在一段 Lua 内
 * 完成「比对已取得的纪元 → 续期纪元键 → 写入会话」，单个 Redis 往返内不可被其他命令插入。
 * 登录复查状态之后若发生撤销，纪元已轮换，比对必然失败，签发返回 {@code null}，
 * 调用方必须按登录失败处理，不能换用新纪元重试。</p>
 *
 * <p><strong>条件撤销</strong>：过滤器在校验旧 Token 时通过
 * {@link #resolveIdentity(String)} 记下当时确认的纪元；发现账号停用后调用
 * {@link #revokeAllForUserIfEpoch(Long, String)}，只有当 Redis 当前纪元仍是该观察值时才轮换。
 * 这样「旧请求暂停 → 期间完成停用撤销、重新启用并重新登录 → 旧请求恢复」不会误伤新会话。</p>
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
    private static final Duration EPOCH_TTL = Duration.ofDays(30);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    /** 校验 Token 时确认的用户与纪元，供条件撤销使用。 */
    public record SessionIdentity(long userId, String epoch) {
    }

    /**
     * 取得或初始化纪元：KEYS[1] 纪元键；ARGV 为候选新纪元、纪元 TTL。
     * 键缺失（或为空串脏值）时原子写入候选值，否则只续期；无论哪种情况都返回当前值。
     */
    private static final DefaultRedisScript<String> ENSURE_EPOCH = new DefaultRedisScript<>(
            "local current = redis.call('GET', KEYS[1])\n"
                    + "if not current or current == '' then\n"
                    + "  current = ARGV[1]\n"
                    + "  redis.call('SET', KEYS[1], current, 'EX', ARGV[2])\n"
                    + "else\n"
                    + "  redis.call('EXPIRE', KEYS[1], ARGV[2])\n"
                    + "end\n"
                    + "return current", String.class);

    /**
     * 原子签发：KEYS[1] 纪元键、KEYS[2] 会话键；ARGV 依次为登录时取得的纪元、
     * 纪元 TTL、会话值前缀（{@code userId:}）、会话 TTL。
     * 当前纪元缺失或与取得的纪元不符（期间发生撤销）时返回 0，不写任何键。
     */
    private static final DefaultRedisScript<Long> ISSUE_SESSION = new DefaultRedisScript<>(
            "local current = redis.call('GET', KEYS[1])\n"
                    + "if not current or current == '' then return 0 end\n"
                    + "if current ~= ARGV[1] then return 0 end\n"
                    + "redis.call('EXPIRE', KEYS[1], ARGV[2])\n"
                    + "redis.call('SET', KEYS[2], ARGV[3] .. current, 'EX', ARGV[4])\n"
                    + "return 1", Long.class);

    /**
     * 条件撤销：KEYS[1] 纪元键；ARGV 为请求观察到的纪元、候选新纪元、纪元 TTL。
     * 只有当前纪元仍等于观察值时才轮换并返回 1；否则不做任何写操作返回 0。
     */
    private static final DefaultRedisScript<Long> REVOKE_IF_EPOCH = new DefaultRedisScript<>(
            "local current = redis.call('GET', KEYS[1])\n"
                    + "if not current or current == '' then return 0 end\n"
                    + "if current ~= ARGV[1] then return 0 end\n"
                    + "redis.call('SET', KEYS[1], ARGV[2], 'EX', ARGV[3])\n"
                    + "return 1", Long.class);

    private final StringRedisTemplate redis;

    public AuthSessionService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 读取当前会话纪元（只读诊断用，<strong>登录不得使用</strong>）。
     *
     * <p>登录必须调用 {@link #ensureEpoch(Long)}：只读方法会返回空，两个并发的首次登录
     * 都拿到空值后只有一个能创建纪元，另一个会被签发脚本拒绝。键缺失时本方法不回退
     * 任何固定值，避免已撤销的旧 Token 复活。</p>
     */
    public Optional<String> currentEpoch(Long userId) {
        String value = redis.opsForValue().get(EPOCH_PREFIX + userId);
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    /**
     * 原子取得或初始化会话纪元，返回确定的非空随机串。
     * 密码校验通过后、复查账号状态前调用；并发的多个登录会得到同一个纪元。
     */
    public String ensureEpoch(Long userId) {
        String epoch = redis.execute(ENSURE_EPOCH, List.of(EPOCH_PREFIX + userId),
                newEpoch(), Long.toString(EPOCH_TTL.getSeconds()));
        if (epoch == null || epoch.isBlank()) {
            // 依赖故障不能伪装成登录成功；由调用方转成 500。
            throw new IllegalStateException("会话纪元初始化未返回有效值");
        }
        return epoch;
    }

    /**
     * 按登录时取得的纪元签发一个独立会话（单段 Lua 原子完成比对与写入）。
     *
     * @param expectedEpoch {@link #ensureEpoch(Long)} 返回的纪元，必须是非空确定值
     * @return 随机不透明 Token；期间纪元已变化或已丢失时返回 {@code null}，
     *         调用方必须当作登录失败，不能换用新纪元重试签发
     */
    public String issue(Long userId, String expectedEpoch) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = ENCODER.encodeToString(bytes);
        Long result = redis.execute(ISSUE_SESSION,
                List.of(EPOCH_PREFIX + userId, SESSION_PREFIX + token),
                expectedEpoch,
                Long.toString(EPOCH_TTL.getSeconds()),
                userId + ":",
                Long.toString(TTL_SECONDS));
        return result != null && result == 1L ? token : null;
    }

    /**
     * 解析有效会话并返回校验时确认的纪元；Token 不存在、已过期、纪元已变更或
     * 纪元键已丢失时返回 null 并清理该 Token。
     *
     * <p>先读会话再读纪元，撤销发生在两次读取之间时第二次读到的已是新纪元，
     * 比对必然失败，因此不需要额外加锁。</p>
     */
    public SessionIdentity resolveIdentity(String token) {
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
            if (epoch.isEmpty() || current == null || current.isEmpty() || !epoch.equals(current)) {
                revoke(token);
                return null;
            }
            return new SessionIdentity(userId, epoch);
        } catch (NumberFormatException exception) {
            revoke(token);
            return null;
        }
    }

    /** 解析有效会话，只返回用户 ID；需要纪元做条件撤销时用 {@link #resolveIdentity(String)}。 */
    public Long resolve(String token) {
        SessionIdentity identity = resolveIdentity(token);
        return identity == null ? null : identity.userId();
    }

    /** 只撤销当前会话。 */
    public void revoke(String token) {
        redis.delete(SESSION_PREFIX + token);
    }

    /** 写入全新的随机纪元，使指定用户的全部旧会话失效；重新启用账号也不会恢复。 */
    public void revokeAllForUser(Long userId) {
        redis.opsForValue().set(EPOCH_PREFIX + userId, newEpoch(), EPOCH_TTL);
    }

    /**
     * 按观察到的纪元条件轮换：只有当前纪元仍是该请求校验旧 Token 时确认的值才撤销。
     * 用于过滤器发现账号停用的兜底路径，避免延迟的旧请求作废重新启用后的新会话。
     *
     * @return 是否真的执行了轮换；false 表示期间纪元已变化，本次不撤销
     */
    public boolean revokeAllForUserIfEpoch(Long userId, String observedEpoch) {
        Long result = redis.execute(REVOKE_IF_EPOCH, List.of(EPOCH_PREFIX + userId),
                observedEpoch, newEpoch(), Long.toString(EPOCH_TTL.getSeconds()));
        return result != null && result == 1L;
    }

    private String newEpoch() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
    }
}
