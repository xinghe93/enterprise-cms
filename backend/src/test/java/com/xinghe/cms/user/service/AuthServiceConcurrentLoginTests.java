package com.xinghe.cms.user.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xinghe.cms.security.AuthSessionService;
import com.xinghe.cms.security.PasswordHasher;
import com.xinghe.cms.user.dto.LoginRequest;
import com.xinghe.cms.user.mapper.UserAuthMapper;
import com.xinghe.cms.user.mapper.row.CurrentUserStatusRow;
import com.xinghe.cms.user.mapper.row.UserCredentialsRow;
import com.xinghe.cms.user.mapper.row.UserProfileRow;
import com.xinghe.cms.user.vo.LoginResultVO;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 复审①的确定性并发回归：两个首次登录必须「都取得纪元」之后才允许各自签发。
 *
 * <p>旧实现里两个请求都会读到空纪元，第一个签发时现场创建纪元，第二个因为
 * 纪元已存在而拿到 401。用 {@link CyclicBarrier} 强制交错顺序，避免依赖线程调度
 * 的偶然时序；两个 Token 同时有效由 {@code AuthSessionServiceRedisIT} 在真实 Redis 上验证。</p>
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceConcurrentLoginTests {

    private static final LocalDateTime TIME = LocalDateTime.of(2026, 11, 2, 16, 0, 0);

    @Mock
    private UserAuthMapper userMapper;
    @Mock
    private PasswordHasher passwordHasher;
    @Mock
    private AuthSessionService sessions;
    @InjectMocks
    private AuthService authService;

    @Test
    void twoConcurrentLoginsBothObtainEpochBeforeEitherIssues() throws Exception {
        lenient().when(userMapper.findCredentialsByUsername("editor_demo"))
                .thenReturn(new UserCredentialsRow(7L, "editor_demo", "stored-hash", 1));
        lenient().when(passwordHasher.matches("pw", "stored-hash")).thenReturn(true);
        lenient().when(userMapper.findStatusById(7L))
                .thenReturn(new CurrentUserStatusRow("editor_demo", null, 1));
        lenient().when(userMapper.findProfileById(7L))
                .thenReturn(new UserProfileRow(7L, "editor_demo", "演示编辑", null, null, 1, TIME, TIME));
        lenient().when(userMapper.findRolesByUserId(7L)).thenReturn(List.of());

        // 屏障：任何一个登录都不许在另一个也取得纪元之前继续往下走。
        CyclicBarrier bothObtainedEpoch = new CyclicBarrier(2);
        AtomicInteger issued = new AtomicInteger();
        when(sessions.ensureEpoch(7L)).thenAnswer(invocation -> {
            bothObtainedEpoch.await(10, TimeUnit.SECONDS);
            return "shared-epoch";
        });
        when(sessions.issue(eq(7L), eq("shared-epoch")))
                .thenAnswer(invocation -> "token-" + issued.incrementAndGet());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<LoginResultVO> first = pool.submit(
                    () -> authService.login(new LoginRequest("editor_demo", "pw")));
            Future<LoginResultVO> second = pool.submit(
                    () -> authService.login(new LoginRequest("editor_demo", "pw")));

            LoginResultVO resultA = first.get(15, TimeUnit.SECONDS);
            LoginResultVO resultB = second.get(15, TimeUnit.SECONDS);

            assertNotNull(resultA, "第一个登录不得因并发首次签发而返回 401");
            assertNotNull(resultB, "第二个登录不得因并发首次签发而返回 401");
            assertNotEquals(resultA.accessToken(), resultB.accessToken(), "每次登录必须签发独立 Token");
            assertEquals(2, issued.get());
            verify(sessions, times(2)).ensureEpoch(7L);
            verify(sessions, times(2)).issue(7L, "shared-epoch");
        } finally {
            pool.shutdownNow();
        }
    }
}
