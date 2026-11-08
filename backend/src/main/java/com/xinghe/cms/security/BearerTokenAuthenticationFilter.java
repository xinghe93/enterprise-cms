package com.xinghe.cms.security;

import com.xinghe.cms.common.contract.CurrentUser;
import com.xinghe.cms.common.response.ResultCode;
import com.xinghe.cms.user.mapper.UserAuthMapper;
import com.xinghe.cms.user.mapper.row.CurrentUserStatusRow;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 在 MVC 参数绑定前完成 Bearer 身份验证，并写入 {@link CurrentUser} 供全模块读取。
 *
 * <p>登录接口为 PUBLIC，跳过本过滤器；其余 /api/ 接口均要求已登录（AUTHENTICATED 或
 * 后续由 B 的权限 AOP 校验功能权限）。过滤器不走 Controller Advice，因此按现有
 * {@code ApiResponse} 契约自行写出统一 JSON 错误体，不依赖 MVC 的序列化器。</p>
 *
 * <p>检测到账号停用时，除返回 401 外主动递增该用户 generation，使其余旧会话立即失效。
 * Redis/数据库故障返回 500，不放行、不吞异常。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class BearerTokenAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(BearerTokenAuthenticationFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String LOGIN_PATH = "/api/auth/login";

    private final AuthSessionService sessions;
    private final UserAuthMapper userMapper;

    public BearerTokenAuthenticationFilter(AuthSessionService sessions, UserAuthMapper userMapper) {
        this.sessions = sessions;
        this.userMapper = userMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return !path.startsWith("/api/") || LOGIN_PATH.equals(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        try {
            String token = bearerToken(request.getHeader("Authorization"));
            if (token == null) {
                writeError(response, ResultCode.UNAUTHORIZED);
                return;
            }
            Long userId = sessions.resolve(token);
            if (userId == null) {
                writeError(response, ResultCode.UNAUTHORIZED);
                return;
            }
            CurrentUserStatusRow status = userMapper.findStatusById(userId);
            if (status == null || status.status() == null || status.status() != 1) {
                sessions.revokeAllForUser(userId);
                writeError(response, ResultCode.UNAUTHORIZED);
                return;
            }
            request.setAttribute(CurrentUserProvider.REQUEST_ATTRIBUTE,
                    new CurrentUser(userId, status.username(), status.departmentId()));
            request.setAttribute(CurrentUserProvider.TOKEN_ATTRIBUTE, token);
        } catch (Exception exception) {
            log.error("认证依赖故障，拒绝本次请求", exception);
            writeError(response, ResultCode.INTERNAL_SERVER_ERROR);
            return;
        }
        chain.doFilter(request, response);
    }

    private String bearerToken(String header) {
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }

    private void writeError(HttpServletResponse response, ResultCode resultCode) throws IOException {
        response.setStatus(resultCode.getCode());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        // 与 ApiResponse 的字段顺序和取值一致：code/message/data/timestamp，data 恒为 null。
        // message 只取自 ResultCode 固定常量，不含用户输入，因此无需额外 JSON 转义。
        String json = "{\"code\":" + resultCode.getCode()
                + ",\"message\":\"" + resultCode.getMessage()
                + "\",\"data\":null,\"timestamp\":\"" + Instant.now() + "\"}";
        response.getWriter().write(json);
    }
}
