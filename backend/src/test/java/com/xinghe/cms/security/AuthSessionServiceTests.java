package com.xinghe.cms.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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

/** 会话纪元的单元回归：并发签发、纪元不复用、TTL 续期与旧版本 Token 拒绝。 */
@ExtendWith(MockitoExtension.class)
class AuthSessionServiceTests {

    private static final String EPOCH_KEY = "cms:auth:generation:7";

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

    private void stubEpochRead(String epoch) {
        doReturn(epoch).when(redis).execute(any(RedisScript.class), anyList(), any(), any());
    }

    @Test
    void issueWritesSessionWithCapturedEpochAndRefreshesEpochKeyTtl() {
        stubEpochRead("epoch-1");

        String token = sessions.issue(7L, "epoch-1");

        assertNotNull(token);
        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(values).set(startsWith("cms:auth:session:"), value.capture(),
                eq(AuthSessionService.TTL_SECONDS), eq(TimeUnit.SECONDS));
        assertEquals("7:epoch-1", value.getValue());
        // 读取纪元时由 Lua 原子续期 30 天，保证存活的 Token 不会先于纪元键失效。
        verify(redis).execute(any(RedisScript.class), eq(List.of(EPOCH_KEY)),
                eq("2592000"), eq("0"));
    }

    /** 登录期间纪元被停用/撤销刷新：必须拒绝签发，不能按新纪元签出可用 Token。 */
    @Test
    void issueRefusesWhenEpochChangedDuringLogin() {
        stubEpochRead("epoch-2");

        assertNull(sessions.issue(7L, "epoch-1"));

        verify(values, never()).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
    }

    /** 纪元是一次性随机值：键过期后再次撤销也不会从 0 加回 1 复用旧版本。 */
    @Test
    void revokeAllForUserNeverReusesAnEpoch() {
        sessions.revokeAllForUser(7L);
        sessions.revokeAllForUser(7L);

        ArgumentCaptor<String> epochs = ArgumentCaptor.forClass(String.class);
        verify(values, times(2)).set(eq(EPOCH_KEY), epochs.capture(),
                eq(Duration.ofDays(30)));
        assertNotEquals(epochs.getAllValues().get(0), epochs.getAllValues().get(1));
        assertEquals(22, epochs.getAllValues().get(0).length(), "16 字节 base64url 随机纪元");
    }

    @Test
    void resolveAcceptsMatchingEpoch() {
        when(values.get("cms:auth:session:t")).thenReturn("7:epoch-1");
        when(values.get(EPOCH_KEY)).thenReturn("epoch-1");

        assertEquals(7L, sessions.resolve("t"));
    }

    @Test
    void currentEpochFallsBackToInitialValue() {
        when(values.get(EPOCH_KEY)).thenReturn(null);
        assertEquals("0", sessions.currentEpoch(7L));
    }

    /** 旧自增版本 Token 在新机制下不会被误认作有效，也不能在纪元重置后复活。 */
    @Test
    void resolveRejectsStaleNumericGenerationTokenAndDeletesIt() {
        when(values.get("cms:auth:session:old")).thenReturn("7:1");
        when(values.get(EPOCH_KEY)).thenReturn(null);

        assertNull(sessions.resolve("old"));

        verify(redis).delete("cms:auth:session:old");
    }

    @Test
    void resolveRejectsTokenWhenEpochWasRefreshed() {
        when(values.get("cms:auth:session:t")).thenReturn("7:epoch-1");
        when(values.get(EPOCH_KEY)).thenReturn("epoch-2");

        assertNull(sessions.resolve("t"));

        verify(redis).delete("cms:auth:session:t");
    }
}
