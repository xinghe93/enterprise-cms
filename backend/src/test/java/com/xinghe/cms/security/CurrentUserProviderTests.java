package com.xinghe.cms.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.xinghe.cms.common.contract.CurrentUser;
import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.response.ResultCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class CurrentUserProviderTests {

    private final CurrentUserProvider provider = new CurrentUserProvider();

    @AfterEach
    void clearContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void missingRequestContextIsUnauthorized() {
        BusinessException exception = assertThrows(BusinessException.class, provider::requireCurrentUser);
        assertEquals(ResultCode.UNAUTHORIZED, exception.getResultCode());
        assertThrows(BusinessException.class, provider::requireCurrentToken);
    }

    @Test
    void readsOnlyTheVerifiedIdentityWrittenByTheFilter() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(CurrentUserProvider.REQUEST_ATTRIBUTE,
                new CurrentUser(7L, "editor_demo", 3L));
        request.setAttribute(CurrentUserProvider.TOKEN_ATTRIBUTE, "opaque-token");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        CurrentUser current = provider.requireCurrentUser();
        assertEquals(7L, current.userId());
        assertEquals("editor_demo", current.username());
        assertEquals(3L, current.departmentId());
        assertEquals("opaque-token", provider.requireCurrentToken());
    }

    @Test
    void identityWithoutTokenIsRejected() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(CurrentUserProvider.REQUEST_ATTRIBUTE, new CurrentUser(7L, "u", null));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        BusinessException exception = assertThrows(BusinessException.class, provider::requireCurrentToken);
        assertEquals(ResultCode.UNAUTHORIZED, exception.getResultCode());
    }
}
