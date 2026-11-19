package com.xinghe.cms.log.controller;

import com.xinghe.cms.common.contract.CurrentUser;
import com.xinghe.cms.security.CurrentUserProvider;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.io.IOException;

/**
 * 测试用认证过滤器：把已认证用户写入请求属性，等价于 A 的
 * {@code BearerTokenAuthenticationFilter} 在真实请求中写入 {@link CurrentUser} 的行为。
 *
 * <p>只用于 MockMvc 测试；真实鉴权链路由联调与 A 的过滤器测试覆盖。</p>
 */
final class TestAuthenticationFilter implements Filter {

    /** 测试统一使用的已认证用户 ID。 */
    static final long USER_ID = 990101L;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        request.setAttribute(CurrentUserProvider.REQUEST_ATTRIBUTE,
                new CurrentUser(USER_ID, "log_demo", null));
        chain.doFilter(request, response);
    }
}
