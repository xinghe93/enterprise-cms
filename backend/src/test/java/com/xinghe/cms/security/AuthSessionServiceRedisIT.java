package com.xinghe.cms.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xinghe.cms.security.AuthSessionService.SessionIdentity;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 会话纪元在<strong>真实 Redis</strong> 上的回归，覆盖单元测试无法验证的 Lua 语义。
 *
 * <p>需要可连接的 Redis：默认 {@code 127.0.0.1:6379}，可用环境变量
 * {@code CMS_REDIS_HOST}、{@code CMS_REDIS_PORT}、
 * {@code CMS_REDIS_PASSWORD}（或 {@code SPRING_DATA_REDIS_PASSWORD}）覆盖。
 * Redis 不可用时整类跳过，不阻塞 {@code mvn verify}。建议使用隔离实例/独立逻辑库，
 * 清理只删除本测试自己创建的键，不做 KEYS 全库扫描。</p>
 *
 * <p>命名以 {@code IT} 结尾，默认不被 surefire 收集，需显式执行：
 * {@code ./mvnw test -Dtest=AuthSessionServiceRedisIT}。</p>
 */
class AuthSessionServiceRedisIT {

    /** 独立的测试用户 ID，避免与开发数据的键冲突。 */
    private static final long USER_ID = 9_000_001L;
    private static final String EPOCH_KEY = "cms:auth:generation:" + USER_ID;
    private static final String SESSION_PREFIX = "cms:auth:session:";

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;

    private final Set<String> trackedSessions = new HashSet<>();
    private AuthSessionService sessions;

    @BeforeAll
    static void connect() {
        String host = env("CMS_REDIS_HOST", "127.0.0.1");
        int port = Integer.parseInt(env("CMS_REDIS_PORT", "6379"));
        String password = System.getenv("CMS_REDIS_PASSWORD") != null
                ? System.getenv("CMS_REDIS_PASSWORD")
                : System.getenv("SPRING_DATA_REDIS_PASSWORD");
        RedisStandaloneConfiguration standalone = new RedisStandaloneConfiguration(host, port);
        if (password != null && !password.isBlank()) {
            standalone.setPassword(password);
        }
        factory = new LettuceConnectionFactory(standalone,
                LettuceClientConfiguration.builder()
                        .commandTimeout(Duration.ofSeconds(2))
                        .build());
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        boolean reachable;
        try {
            reachable = "PONG".equals(redis.getConnectionFactory().getConnection().ping());
        } catch (RuntimeException exception) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable,
                "Redis 不可用（" + host + ":" + port + "），跳过真实 Redis 回归");
    }

    @AfterAll
    static void disconnect() {
        if (factory != null) {
            factory.destroy();
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    @BeforeEach
    void setUp() {
        sessions = new AuthSessionService(redis);
        cleanUp();
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    private void cleanUp() {
        redis.delete(EPOCH_KEY);
        for (String key : trackedSessions) {
            redis.delete(key);
        }
        trackedSessions.clear();
    }

    private String issue(String epoch) {
        String token = sessions.issue(USER_ID, epoch);
        if (token != null) {
            trackedSessions.add(SESSION_PREFIX + token);
        }
        return token;
    }

    private String login() {
        return issue(sessions.ensureEpoch(USER_ID));
    }

    @Test
    void firstLoginCreatesRandomEpochAndTokenResolves() {
        assertTrue(sessions.currentEpoch(USER_ID).isEmpty(), "初始无纪元键");

        String epoch = sessions.ensureEpoch(USER_ID);
        String token = issue(epoch);

        assertNotNull(token);
        assertNotNull(epoch);
        assertEquals(22, epoch.length(), "16 字节 base64url 随机纪元");
        assertEquals(epoch, redis.opsForValue().get(EPOCH_KEY));
        assertEquals(USER_ID + ":" + epoch, redis.opsForValue().get(SESSION_PREFIX + token));
        assertEquals(USER_ID, sessions.resolve(token));
        Long epochTtl = redis.getExpire(EPOCH_KEY);
        assertTrue(epochTtl != null && epochTtl > Duration.ofDays(29).getSeconds(), "纪元 TTL 约 30 天");
        Long sessionTtl = redis.getExpire(SESSION_PREFIX + token);
        assertTrue(sessionTtl != null && sessionTtl <= AuthSessionService.TTL_SECONDS
                && sessionTtl > AuthSessionService.TTL_SECONDS - 60, "会话 TTL 固定 7200 秒");
    }

    /** 复审①：两个请求都先取得纪元、之后才各自签发，必须都成功且同时有效。 */
    @Test
    void twoConcurrentLoginsBothObtainEpochBeforeEitherIssues() {
        String epochA = sessions.ensureEpoch(USER_ID);
        String epochB = sessions.ensureEpoch(USER_ID);
        assertEquals(epochA, epochB, "并发首次登录必须取得同一个随机纪元");

        String tokenA = issue(epochA);
        String tokenB = issue(epochB);

        assertNotNull(tokenA, "第一个登录不得因并发签发返回 401");
        assertNotNull(tokenB, "第二个登录不得因并发签发返回 401");
        assertNotEquals(tokenA, tokenB, "每次登录必须签发独立 Token");
        assertEquals(USER_ID, sessions.resolve(tokenA), "Token A 同时有效");
        assertEquals(USER_ID, sessions.resolve(tokenB), "Token B 同时有效");
    }

    /** P1 核心回归：纪元键被删除后，仍有会话键的旧 Token 必须失效，绝不被当作初始版本复活。 */
    @Test
    void deletingEpochKeyInvalidatesTokenInsteadOfRevivingIt() {
        String token = login();
        assertNotNull(token);
        assertEquals(USER_ID, sessions.resolve(token));

        redis.delete(EPOCH_KEY);

        assertNull(sessions.resolve(token), "纪元键丢失后 Token 必须失效");
        assertNull(redis.opsForValue().get(SESSION_PREFIX + token), "失效 Token 应被清理");
    }

    /** 纪元存在、Token 值仍是旧版固定 "0"（历史数据）：必须拒绝。 */
    @Test
    void legacyFixedEpochSessionIsRejected() {
        String token = login();
        assertNotNull(token);
        redis.opsForValue().set(SESSION_PREFIX + token,
                USER_ID + ":0", AuthSessionService.TTL_SECONDS);

        assertNull(sessions.resolve(token));
    }

    /** 撤销使该用户全部会话失效；重新启用后的新会话不受旧纪元影响，但不会恢复旧 Token。 */
    @Test
    void revokeAllInvalidatesEveryTokenAndReenabledAccountDoesNotRestoreThem() {
        String first = login();
        String second = login();
        assertNotNull(first);
        assertNotNull(second);
        assertEquals(USER_ID, sessions.resolve(second));

        sessions.revokeAllForUser(USER_ID);

        assertNull(sessions.resolve(first), "撤销后旧 Token 全部失效");
        assertNull(sessions.resolve(second));

        String afterReenable = login();
        assertNotNull(afterReenable, "重新启用后可以重新登录");
        assertEquals(USER_ID, sessions.resolve(afterReenable));
        assertNull(sessions.resolve(second), "旧 Token 重新登录后仍不可用");
    }

    /** P2 对应行为：登录期间纪元已变（撤销），签发必须拒绝，不能签出立即失效的 Token。 */
    @Test
    void issueRefusesWhenEpochChangedBetweenLoginStartAndIssue() {
        assertNotNull(login());
        String epochAtLoginStart = sessions.ensureEpoch(USER_ID);

        sessions.revokeAllForUser(USER_ID);

        assertNull(issue(epochAtLoginStart), "复查后发生撤销时旧纪元不得签发");
    }

    /** 纪元键在取得之后消失（过期/误删）：同样拒绝，不能按「首次登录」重建。 */
    @Test
    void issueRefusesWhenEpochKeyVanishedAfterLoginStart() {
        assertNotNull(login());
        String epochAtLoginStart = sessions.ensureEpoch(USER_ID);

        redis.delete(EPOCH_KEY);

        assertNull(issue(epochAtLoginStart));
        assertNull(redis.opsForValue().get(EPOCH_KEY), "拒绝签发时不得写入新纪元");
    }

    /** 复审②：确定性复现「旧请求暂停 → 撤销 → 重新启用并登录 → 旧请求恢复」。 */
    @Test
    void staleDisabledRequestCannotInvalidateSessionIssuedAfterReenable() {
        // 旧请求校验 Token 时确认的纪元，正是条件撤销要携带的观察值。
        String epoch0 = sessions.ensureEpoch(USER_ID);
        String oldToken = issue(epoch0);
        assertNotNull(oldToken);
        SessionIdentity observed = sessions.resolveIdentity(oldToken);
        assertNotNull(observed);
        assertEquals(epoch0, observed.epoch());

        // 期间：停用事务提交后撤销 → 纪元轮换；账号重新启用并重新登录。
        sessions.revokeAllForUser(USER_ID);
        String epoch1 = sessions.ensureEpoch(USER_ID);
        assertNotEquals(epoch0, epoch1);
        String newToken = issue(epoch1);
        assertNotNull(newToken);
        assertEquals(USER_ID, sessions.resolve(newToken));

        // 旧请求恢复：按过期纪元做条件撤销，必须跳过轮换。
        assertFalse(sessions.revokeAllForUserIfEpoch(observed.userId(), observed.epoch()));
        assertEquals(epoch1, redis.opsForValue().get(EPOCH_KEY), "不得轮换纪元、不得作废新会话");
        assertEquals(USER_ID, sessions.resolve(newToken), "新 Token 仍可访问受保护接口");
        assertNull(sessions.resolve(oldToken), "旧 Token 仍失效");
    }

    /** 条件撤销在纪元未变时仍然生效，停用兜底路径没有被削弱。 */
    @Test
    void conditionalRevokeRotatesOnlyWhenObservedEpochIsCurrent() {
        String epoch0 = sessions.ensureEpoch(USER_ID);
        String oldToken = issue(epoch0);
        assertNotNull(oldToken);
        SessionIdentity observed = sessions.resolveIdentity(oldToken);
        assertNotNull(observed);

        assertTrue(sessions.revokeAllForUserIfEpoch(observed.userId(), observed.epoch()));
        String epoch1 = redis.opsForValue().get(EPOCH_KEY);
        assertNotEquals(epoch0, epoch1);
        assertNull(sessions.resolve(oldToken), "轮换后旧 Token 立即失效");

        // 再用已经过期的观察值撤销：不得二次轮换。
        assertFalse(sessions.revokeAllForUserIfEpoch(USER_ID, epoch0));
        assertEquals(epoch1, redis.opsForValue().get(EPOCH_KEY));
    }

    /** 并发签发：纪元不变时多个 Token 可共存，互不影响（登录不踢出其他会话）。 */
    @Test
    void concurrentLoginsCoexist() {
        String first = login();
        String second = login();

        assertNotNull(first);
        assertNotNull(second);
        assertNotEquals(first, second);
        assertEquals(USER_ID, sessions.resolve(first));
        assertEquals(USER_ID, sessions.resolve(second));
        sessions.revoke(second);
        assertEquals(USER_ID, sessions.resolve(first), "退出只撤销当前 Token");
    }

    /** 契约：Token 固定 7200 秒，普通请求（resolve）不滑动续期。 */
    @Test
    void ordinaryResolveDoesNotExtendSessionTtl() {
        String token = login();
        assertNotNull(token);
        Long before = redis.getExpire(SESSION_PREFIX + token);

        assertEquals(USER_ID, sessions.resolve(token));

        Long after = redis.getExpire(SESSION_PREFIX + token);
        assertNotNull(before);
        assertNotNull(after);
        assertTrue(after <= before && before - after < 5, "普通请求不得续期");
    }
}
