package com.xinghe.cms.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Optional;
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
 * Redis 不可用时整类跳过，不阻塞 {@code mvn verify}。</p>
 *
 * <p>命名以 {@code IT} 结尾，默认不被 surefire 收集，需显式执行：
 * {@code ./mvnw test -Dtest=AuthSessionServiceRedisIT}。</p>
 */
class AuthSessionServiceRedisIT {

    /** 独立的测试用户 ID，避免与开发数据的键冲突。 */
    private static final long USER_ID = 9_000_001L;
    private static final String EPOCH_KEY = "cms:auth:generation:" + USER_ID;

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;

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
        Set<String> keys = redis.keys("cms:auth:session:*");
        if (keys != null && !keys.isEmpty()) {
            keys.stream()
                    .filter(key -> String.valueOf(redis.opsForValue().get(key))
                            .startsWith(USER_ID + ":"))
                    .forEach(redis::delete);
        }
    }

    @Test
    void firstLoginCreatesRandomEpochAndTokenResolves() {
        assertTrue(sessions.currentEpoch(USER_ID).isEmpty(), "初始无纪元键");

        String token = sessions.issue(USER_ID, Optional.empty());

        assertNotNull(token);
        String epoch = redis.opsForValue().get(EPOCH_KEY);
        assertNotNull(epoch, "首次登录必须写入随机纪元");
        assertEquals(22, epoch.length());
        assertEquals(USER_ID + ":" + epoch, redis.opsForValue().get("cms:auth:session:" + token));
        assertEquals(USER_ID, sessions.resolve(token));
        Long epochTtl = redis.getExpire(EPOCH_KEY);
        assertTrue(epochTtl != null && epochTtl > Duration.ofDays(29).getSeconds(), "纪元 TTL 约 30 天");
        Long sessionTtl = redis.getExpire("cms:auth:session:" + token);
        assertTrue(sessionTtl != null && sessionTtl <= AuthSessionService.TTL_SECONDS
                && sessionTtl > AuthSessionService.TTL_SECONDS - 60, "会话 TTL 固定 7200 秒");
    }

    /** P1 核心回归：纪元键被删除后，仍有会话键的旧 Token 必须失效，绝不被当作初始版本复活。 */
    @Test
    void deletingEpochKeyInvalidatesTokenInsteadOfRevivingIt() {
        String token = sessions.issue(USER_ID, Optional.empty());
        assertNotNull(token);
        assertEquals(USER_ID, sessions.resolve(token));

        redis.delete(EPOCH_KEY);

        assertNull(sessions.resolve(token), "纪元键丢失后 Token 必须失效");
        assertNull(redis.opsForValue().get("cms:auth:session:" + token), "失效 Token 应被清理");
    }

    /** 纪元存在、Token 值仍是旧版固定 "0"（历史数据）：必须拒绝。 */
    @Test
    void legacyFixedEpochSessionIsRejected() {
        String token = sessions.issue(USER_ID, Optional.empty());
        assertNotNull(token);
        redis.opsForValue().set("cms:auth:session:" + token,
                USER_ID + ":0", AuthSessionService.TTL_SECONDS);

        assertNull(sessions.resolve(token));
    }

    /** 撤销使该用户全部会话失效；重新启用后的新会话不受旧纪元影响，但不会恢复旧 Token。 */
    @Test
    void revokeAllInvalidatesEveryTokenAndReenabledAccountDoesNotRestoreThem() {
        String first = sessions.issue(USER_ID, Optional.empty());
        String second = sessions.issue(USER_ID, sessions.currentEpoch(USER_ID));
        assertNotNull(first);
        assertNotNull(second);
        assertEquals(USER_ID, sessions.resolve(second));

        sessions.revokeAllForUser(USER_ID);

        assertNull(sessions.resolve(first), "撤销后旧 Token 全部失效");
        assertNull(sessions.resolve(second));

        String afterReenable = sessions.issue(USER_ID, sessions.currentEpoch(USER_ID));
        assertNotNull(afterReenable, "重新启用后可以重新登录");
        assertEquals(USER_ID, sessions.resolve(afterReenable));
        assertNull(sessions.resolve(second), "旧 Token 重新登录后仍不可用");
    }

    /** P2 对应行为：登录期间纪元已变（撤销），签发必须拒绝，不能签出立即失效的 Token。 */
    @Test
    void issueRefusesWhenEpochChangedBetweenLoginStartAndIssue() {
        String token = sessions.issue(USER_ID, Optional.empty());
        assertNotNull(token);
        Optional<String> epochAtLoginStart = sessions.currentEpoch(USER_ID);

        sessions.revokeAllForUser(USER_ID);

        assertNull(sessions.issue(USER_ID, epochAtLoginStart));
    }

    /** 纪元键在登录开始后消失（过期/误删）：同样拒绝，不能按「首次登录」重建。 */
    @Test
    void issueRefusesWhenEpochKeyVanishedAfterLoginStart() {
        assertNotNull(sessions.issue(USER_ID, Optional.empty()));
        Optional<String> epochAtLoginStart = sessions.currentEpoch(USER_ID);

        redis.delete(EPOCH_KEY);

        assertNull(sessions.issue(USER_ID, epochAtLoginStart));
        assertNull(redis.opsForValue().get(EPOCH_KEY), "拒绝签发时不得写入新纪元");
    }

    /** 并发签发：纪元不变时多个 Token 可共存，互不影响（登录不踢出其他会话）。 */
    @Test
    void concurrentLoginsCoexist() {
        String first = sessions.issue(USER_ID, Optional.empty());
        String second = sessions.issue(USER_ID, sessions.currentEpoch(USER_ID));

        assertNotNull(first);
        assertNotNull(second);
        assertEquals(USER_ID, sessions.resolve(first));
        assertEquals(USER_ID, sessions.resolve(second));
        sessions.revoke(second);
        assertEquals(USER_ID, sessions.resolve(first), "退出只撤销当前 Token");
    }
}
