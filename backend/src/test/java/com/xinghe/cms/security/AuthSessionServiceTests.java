package com.xinghe.cms.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * 会话纪元的单元回归：原子签发、纪元丢失即失效、纪元不复用。
 *
 * <p>Lua 脚本的真实语义由 {@code AuthSessionServiceRedisIT} 在真实 Redis 上验证，
 * 这里只锁定调用契约（一次往返、参数顺序、不额外写入会话键）。</p>
 */
@ExtendWith(MockitoExtension.class)
class AuthSessionServiceTests {

    private static final String EPOCH_KEY = "cms:auth:generation:7";
    private static final String SESSION_KEY = "cms:auth:session:t";

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> values;

    private AuthSessionService sessions;

    @BeforeEach
    void setUp() {
        // revoke() 等路径不经过 opsForValue()，用 lenient 避免严格 stubbing 误报。
        lenient().when(redis.opsForValue()).thenReturn(values);
        sessions = new AuthSessionService(redis);
    }

    private void stubIssueResult(Long result) {
        doReturn(result).when(redis).execute(any(RedisScript.class), anyList(),
                any(), any(), any(), any(), any());
    }

    /** 首次登录：允许脚本现场创建随机纪元，并与会话写入合并为一次往返。 */
    @Test
    void issueCreatesRandomEpochOnFirstLoginInSingleRoundTrip() {
        stubIssueResult(1L);

        String token = sessions.issue(7L, Optional.empty());

        assertNotNull(token);
        ArgumentCaptor<String> newEpoch = ArgumentCaptor.forClass(String.class);
        verify(redis).execute(any(RedisScript.class),
                eq(List.of(EPOCH_KEY, "cms:auth:session:" + token)),
                eq(""), newEpoch.capture(), eq("2592000"), eq("7:"), eq("7200"));
        assertEquals(22, newEpoch.getValue().length(), "16 字节 base64url 随机纪元");
        // 原子性：会话必须在脚本内写入，不能再有第二次往返的 set。
        verify(values, never()).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
    }

    /** 登录期间纪元被撤销刷新：脚本返回 0，必须拒绝签发。 */
    @Test
    void issueRefusesWhenEpochChangedDuringLogin() {
        stubIssueResult(0L);

        assertNull(sessions.issue(7L, Optional.of("epoch-1")));

        verify(values, never()).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
    }

    /** 登录开始时存在纪元、签发时已丢失（被撤销或键过期）：同样拒绝，不能当作首次登录重建。 */
    @Test
    void issueRefusesWhenEpochDisappearedAfterLoginStarted() {
        stubIssueResult(0L);

        assertNull(sessions.issue(7L, Optional.of("epoch-1")));

        verify(values, never()).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test
    void issuePassesExpectedEpochToScriptForComparison() {
        stubIssueResult(1L);

        assertNotNull(sessions.issue(7L, Optional.of("epoch-1")));

        ArgumentCaptor<Object> firstArg = ArgumentCaptor.forClass(Object.class);
        verify(redis).execute(any(RedisScript.class), anyList(), firstArg.capture(),
                any(), any(), any(), any());
        assertEquals("epoch-1", firstArg.getValue());
    }

    /** P1：纪元键不存在时不再回退成固定初始版本。 */
    @Test
    void currentEpochIsEmptyInsteadOfFixedFallbackWhenKeyMissing() {
        when(values.get(EPOCH_KEY)).thenReturn(null);

        assertEquals(Optional.empty(), sessions.currentEpoch(7L));
    }

    @Test
    void currentEpochReturnsStoredValue() {
        when(values.get(EPOCH_KEY)).thenReturn("epoch-1");

        assertEquals(Optional.of("epoch-1"), sessions.currentEpoch(7L));
    }

    @Test
    void resolveAcceptsMatchingEpoch() {
        when(values.get(SESSION_KEY)).thenReturn("7:epoch-1");
        when(values.get(EPOCH_KEY)).thenReturn("epoch-1");

        assertEquals(7L, sessions.resolve("t"));
    }

    /** P1 核心回归：纪元键丢失后，已撤销/未撤销的 Token 都必须失效，不能复活。 */
    @Test
    void resolveRejectsTokenWhenEpochKeyIsGone() {
        when(values.get(SESSION_KEY)).thenReturn("7:0");
        when(values.get(EPOCH_KEY)).thenReturn(null);

        assertNull(sessions.resolve("t"));

        verify(redis).delete(SESSION_KEY);
    }

    @Test
    void resolveRejectsTokenWhenEpochWasRefreshed() {
        when(values.get(SESSION_KEY)).thenReturn("7:epoch-1");
        when(values.get(EPOCH_KEY)).thenReturn("epoch-2");

        assertNull(sessions.resolve("t"));

        verify(redis).delete(SESSION_KEY);
    }

    @Test
    void resolveRejectsMalformedSessionValueAndDeletesIt() {
        when(values.get(SESSION_KEY)).thenReturn("no-separator");

        assertNull(sessions.resolve("t"));

        verify(redis).delete(SESSION_KEY);
    }

    @Test
    void resolveRejectsNonNumericUserIdAndDeletesIt() {
        when(values.get(SESSION_KEY)).thenReturn("abc:epoch-1");

        assertNull(sessions.resolve("t"));

        verify(redis).delete(SESSION_KEY);
    }

    /** 纪元是一次性随机值：连续撤销写入的值必不相同，不会复用旧版本。 */
    @Test
    void revokeAllForUserNeverReusesAnEpoch() {
        sessions.revokeAllForUser(7L);
        sessions.revokeAllForUser(7L);

        ArgumentCaptor<String> epochs = ArgumentCaptor.forClass(String.class);
        verify(values, times(2)).set(eq(EPOCH_KEY), epochs.capture(), eq(Duration.ofDays(30)));
        assertNotEquals(epochs.getAllValues().get(0), epochs.getAllValues().get(1));
        assertEquals(22, epochs.getAllValues().get(0).length(), "16 字节 base64url 随机纪元");
        assertTrue(epochs.getAllValues().stream().noneMatch(String::isBlank));
    }

    @Test
    void revokeDeletesOnlyGivenSession() {
        sessions.revoke("t");

        verify(redis).delete(SESSION_KEY);
        verify(redis, never()).delete(startsWith("cms:auth:generation:"));
    }
}
