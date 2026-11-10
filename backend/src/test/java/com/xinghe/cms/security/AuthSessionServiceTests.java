package com.xinghe.cms.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
 * 会话纪元的单元回归：原子取得/签发/条件撤销、纪元丢失即失效、纪元不复用。
 *
 * <p>Lua 脚本的真实语义由 {@code AuthSessionServiceRedisIT} 在真实 Redis 上验证，
 * 这里只锁定调用契约（各脚本一次往返、参数顺序、不额外写入会话键）。</p>
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
        // resolve() 等路径会用到 opsForValue()，用 lenient 避免严格 stubbing 误报。
        lenient().when(redis.opsForValue()).thenReturn(values);
        sessions = new AuthSessionService(redis);
    }

    /** ENSURE_EPOCH 有 2 个可变参数；不同脚本的参数个数不同，分开打桩避免互相吞掉。 */
    private void stubEnsureEpoch(String result) {
        doReturn(result).when(redis).execute(any(RedisScript.class), anyList(), any(), any());
    }

    /** ISSUE_SESSION 有 4 个可变参数。 */
    private void stubIssueResult(Long result) {
        doReturn(result).when(redis).execute(any(RedisScript.class), anyList(),
                any(), any(), any(), any());
    }

    /** REVOKE_IF_EPOCH 有 3 个可变参数。 */
    private void stubRevokeIfEpoch(Long result) {
        doReturn(result).when(redis).execute(any(RedisScript.class), anyList(),
                any(), any(), any());
    }

    /** 并发首次登录：取得/初始化纪元必须返回确定值，且只用一次 Redis 往返。 */
    @Test
    void ensureEpochReturnsDeterminedEpochInSingleRoundTrip() {
        stubEnsureEpoch("epoch-1");

        assertEquals("epoch-1", sessions.ensureEpoch(7L));

        ArgumentCaptor<String> candidate = ArgumentCaptor.forClass(String.class);
        verify(redis).execute(any(RedisScript.class), eq(List.of(EPOCH_KEY)),
                candidate.capture(), eq("2592000"));
        assertEquals(22, candidate.getValue().length(), "16 字节 base64url 随机纪元");
    }

    @Test
    void ensureEpochFailsClosedWhenRedisReturnsNoValue() {
        stubEnsureEpoch(null);
        assertThrows(IllegalStateException.class, () -> sessions.ensureEpoch(7L));
    }

    @Test
    void ensureEpochFailsClosedWhenRedisReturnsBlankValue() {
        stubEnsureEpoch("   ");
        assertThrows(IllegalStateException.class, () -> sessions.ensureEpoch(7L));
    }

    /** 签发只比对已取得的纪元，比对/续期/写会话合并为一次往返。 */
    @Test
    void issueComparesDeterminedEpochInSingleRoundTrip() {
        stubIssueResult(1L);

        String token = sessions.issue(7L, "epoch-1");

        assertNotNull(token);
        verify(redis).execute(any(RedisScript.class),
                eq(List.of(EPOCH_KEY, "cms:auth:session:" + token)),
                eq("epoch-1"), eq("2592000"), eq("7:"), eq("7200"));
        // 原子性：会话必须在脚本内写入，不能再有第二次往返的 set。
        verify(values, never()).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
    }

    /** 登录期间纪元被撤销刷新：脚本返回 0，必须拒绝签发。 */
    @Test
    void issueRefusesWhenEpochChangedDuringLogin() {
        stubIssueResult(0L);

        assertNull(sessions.issue(7L, "epoch-1"));

        verify(values, never()).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
    }

    /** 取得纪元后纪元键丢失：同样拒绝，不能当成首次登录重建。 */
    @Test
    void issueRefusesWhenEpochDisappearedAfterLoginStarted() {
        stubIssueResult(0L);

        assertNull(sessions.issue(7L, "epoch-1"));

        verify(values, never()).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test
    void issuePassesExpectedEpochToScriptForComparison() {
        stubIssueResult(1L);

        assertNotNull(sessions.issue(7L, "epoch-observed"));

        ArgumentCaptor<Object> firstArg = ArgumentCaptor.forClass(Object.class);
        verify(redis).execute(any(RedisScript.class), anyList(), firstArg.capture(),
                any(), any(), any());
        assertEquals("epoch-observed", firstArg.getValue());
    }

    /** P1：纪元键不存在时不再回退成固定初始版本（只读诊断方法）。 */
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

    /** 过滤器需要拿到校验旧 Token 时确认的纪元，用于条件撤销。 */
    @Test
    void resolveIdentityReturnsObservedEpoch() {
        when(values.get(SESSION_KEY)).thenReturn("7:epoch-1");
        when(values.get(EPOCH_KEY)).thenReturn("epoch-1");

        AuthSessionService.SessionIdentity identity = sessions.resolveIdentity("t");

        assertNotNull(identity);
        assertEquals(7L, identity.userId());
        assertEquals("epoch-1", identity.epoch());
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
    void resolveRejectsBlankEpochKeyInsteadOfAcceptingDirtyValue() {
        when(values.get(SESSION_KEY)).thenReturn("7:epoch-1");
        when(values.get(EPOCH_KEY)).thenReturn("");

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

    /** 条件撤销把观察纪元交给 Lua，由脚本决定是否轮换；不额外做第二次往返。 */
    @Test
    void revokeAllForUserIfEpochPassesObservedEpochToScript() {
        stubRevokeIfEpoch(1L);

        assertTrue(sessions.revokeAllForUserIfEpoch(7L, "epoch-observed"));

        ArgumentCaptor<String> replacement = ArgumentCaptor.forClass(String.class);
        verify(redis).execute(any(RedisScript.class), eq(List.of(EPOCH_KEY)),
                eq("epoch-observed"), replacement.capture(), eq("2592000"));
        assertEquals(22, replacement.getValue().length());
        verify(values, never()).set(eq(EPOCH_KEY), anyString(), any(Duration.class));
    }

    @Test
    void revokeAllForUserIfEpochReportsSkippedRotation() {
        stubRevokeIfEpoch(0L);

        assertFalse(sessions.revokeAllForUserIfEpoch(7L, "stale-epoch"));
    }

    @Test
    void revokeDeletesOnlyGivenSession() {
        sessions.revoke("t");

        verify(redis).delete(SESSION_KEY);
        verify(redis, never()).delete(startsWith("cms:auth:generation:"));
    }
}
