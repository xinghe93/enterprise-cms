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
import java.util.Locale;
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
 * <p>路径判断用容器解码后的 {@code servletPath}（与 MVC 映射输入一致），
 * 不用未解码的 {@code requestURI}，否则 /%61pi/... 这类编码变体会绕过前缀判断；
 * /api/ 前缀判断大小写不敏感，避免 /API/... 变体被当成非 API 请求放行。
 * PUBLIC 端点只有 login 一个，必须大小写敏感的精确匹配，不用 startsWith / contains。
 * 契约中全部业务接口都位于 /api/ 下，其余静态资源不属于本过滤器职责。</p>
 *
 * <p>检测到账号停用时，除返回 401 外按「本次请求校验旧 Token 时确认的纪元」条件刷新
 * 该用户会话纪元：只有 Redis 当前纪元仍是那个观察值时才轮换。这样延迟的旧请求
 * （读到停用状态后暂停，期间账号完成停用撤销、重新启用并重新登录）恢复后不会
 * 无条件撤销，从而作废重新启用后签发的新 Token。旧请求本身仍返回 401。</p>
 *
 * <p>账号启停接口仍须在数据库事务提交后调用 {@code revokeAllForUser}，不得先刷新
 * Redis 再提交数据库。Redis/数据库故障返回 500，不放行、不吞异常。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class BearerTokenAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(BearerTokenAuthenticationFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String API_PREFIX = "/api/";
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
        boolean apiRequest = path.toLowerCase(Locale.ROOT).startsWith(API_PREFIX);
        return !apiRequest || LOGIN_PATH.equals(path);
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
            // 记下校验旧 Token 时确认的纪元，供停用路径做条件撤销。
            AuthSessionService.SessionIdentity identity = sessions.resolveIdentity(token);
            if (identity == null) {
                writeError(response, ResultCode.UNAUTHORIZED);
                return;
            }
            // 每请求重查账号状态是契约要求：停用与权限变更必须即时生效，不能缓存放行。
            CurrentUserStatusRow status = userMapper.findStatusById(identity.userId());
            if (status == null || status.status() == null || status.status() != 1) {
                // 只有观察到的纪元仍是当前纪元时才允许轮换；期间已撤销并重新登录的场景
                // 纪元已变，本次不撤销，避免作废新会话。旧请求一律返回 401。
                sessions.revokeAllForUserIfEpoch(identity.userId(), identity.epoch());
                writeError(response, ResultCode.UNAUTHORIZED);
                return;
            }
            request.setAttribute(CurrentUserProvider.REQUEST_ATTRIBUTE,
                    new CurrentUser(identity.userId(), status.username(), status.departmentId()));
            request.setAttribute(CurrentUserProvider.TOKEN_ATTRIBUTE, token);
        } catch (Exception exception) {
            // 契约要求 Redis/DB 故障不得放行；连同编程错误一并拒绝并记录完整堆栈，
            // 不是静默吞异常。返回统一 500 信封，不暴露内部细节。
            log.error("认证依赖故障，拒绝本次请求", exception);
            if (!response.isCommitted()) {
                writeError(response, ResultCode.INTERNAL_SERVER_ERROR);
            }
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
