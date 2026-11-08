package com.xinghe.cms.security;

import com.xinghe.cms.common.contract.CurrentUser;
import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.response.ResultCode;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 当前请求身份的公共入口（A 提供，B/C/D 注入使用）。
 *
 * <p>仅读取 {@link BearerTokenAuthenticationFilter} 在 MVC 参数绑定前写入的已验证身份；
 * 无 Token、无效/过期/已撤销 Token、停用用户、非 HTTP 上下文均抛 UNAUTHORIZED，
 * 不返回可误认为已认证的空身份，也不接受请求参数中的 userId。</p>
 */
@Component
public class CurrentUserProvider {

    public static final String REQUEST_ATTRIBUTE = "com.xinghe.cms.security.CURRENT_USER";
    public static final String TOKEN_ATTRIBUTE = "com.xinghe.cms.security.CURRENT_TOKEN";

    public CurrentUser requireCurrentUser() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes
                && servletAttributes.getRequest().getAttribute(REQUEST_ATTRIBUTE) instanceof CurrentUser currentUser) {
            return currentUser;
        }
        throw new BusinessException(ResultCode.UNAUTHORIZED);
    }

    /** 退出登录时使用的当前会话 Token，来源同上，不由客户端另行指定。 */
    public String requireCurrentToken() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes
                && servletAttributes.getRequest().getAttribute(TOKEN_ATTRIBUTE) instanceof String token
                && !token.isBlank()) {
            return token;
        }
        throw new BusinessException(ResultCode.UNAUTHORIZED);
    }
}
