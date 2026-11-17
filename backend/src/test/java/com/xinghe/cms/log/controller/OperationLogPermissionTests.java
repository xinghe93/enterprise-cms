package com.xinghe.cms.log.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.xinghe.cms.common.contract.AuthorizationSnapshot;
import com.xinghe.cms.common.contract.CurrentUser;
import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.exception.GlobalExceptionHandler;
import com.xinghe.cms.common.response.PageResult;
import com.xinghe.cms.common.response.ResultCode;
import com.xinghe.cms.log.dto.OperationLogQuery;
import com.xinghe.cms.log.service.OperationLogService;
import com.xinghe.cms.log.support.LogPermissionGuard;
import com.xinghe.cms.log.vo.OperationLogVO;
import com.xinghe.cms.permission.service.AuthorizationQueryService;
import com.xinghe.cms.security.CurrentUserProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 日志查询接口的功能权限验收：匿名 401、无权限员工 403、有权限用户 200。
 *
 * <p>使用 standalone MockMvc 装配真实 Controller、真实 {@link LogPermissionGuard} 与
 * 真实 A 的 {@link CurrentUserProvider}，只把授权快照与日志服务替换为替身；这样不必
 * 启动完整 Spring 上下文，也不会被 Bearer 过滤器拦截（其鉴权行为由真实联调验证）。</p>
 */
class OperationLogPermissionTests {

    private static final long EMPLOYEE_ID = 7L;

    private static final long ADMIN_ID = 9L;

    private StubOperationLogService stubService;

    private StubAuthorizationQueryService stubAuthorization;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        stubService = new StubOperationLogService();
        stubAuthorization = new StubAuthorizationQueryService();
        LogPermissionGuard guard = new LogPermissionGuard(new CurrentUserProvider(), stubAuthorization);
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.setApplicationContext(new StaticApplicationContext());
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new OperationLogController(stubService, guard))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    void anonymousListReturnsUnauthorized() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/admin/logs")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat(stubService.pageCalled).isFalse();
    }

    @Test
    void anonymousDetailReturnsUnauthorized() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/admin/logs/7")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void employeeWithoutPermissionCannotReadList() throws Exception {
        stubAuthorization.grant(EMPLOYEE_ID, Set.of());

        MvcResult result = mockMvc.perform(getAs(EMPLOYEE_ID, "/api/admin/logs")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(stubService.pageCalled).isFalse();
    }

    @Test
    void employeeWithoutPermissionCannotReadDetail() throws Exception {
        stubAuthorization.grant(EMPLOYEE_ID, Set.of());

        MvcResult result = mockMvc.perform(getAs(EMPLOYEE_ID, "/api/admin/logs/7")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(stubService.detailCalled).isFalse();
    }

    @Test
    void detailRequiresReadPermissionEvenWithListPermission() throws Exception {
        stubAuthorization.grant(ADMIN_ID, Set.of("log:list"));

        MvcResult result = mockMvc.perform(getAs(ADMIN_ID, "/api/admin/logs/7")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(stubService.detailCalled).isFalse();
    }

    @Test
    void listAllowsUserWithListPermission() throws Exception {
        stubAuthorization.grant(ADMIN_ID, Set.of("log:list"));

        MvcResult result = mockMvc.perform(getAs(ADMIN_ID, "/api/admin/logs")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(stubService.pageCalled).isTrue();
    }

    @Test
    void detailAllowsUserWithReadPermission() throws Exception {
        stubAuthorization.grant(ADMIN_ID, Set.of("log:read"));

        MvcResult result = mockMvc.perform(getAs(ADMIN_ID, "/api/admin/logs/7")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(stubService.detailCalled).isTrue();
        assertThat(stubService.lastDetailId).isEqualTo(7L);
    }

    private MockHttpServletRequestBuilder getAs(long userId, String path) {
        return get(path).requestAttr(CurrentUserProvider.REQUEST_ATTRIBUTE,
                new CurrentUser(userId, "it_user", null));
    }

    /**
     * 可配置授权快照的替身：未登记的用户按 A 的语义视为未认证。
     */
    static class StubAuthorizationQueryService implements AuthorizationQueryService {

        private final Map<Long, Set<String>> grants = new HashMap<>();

        void grant(long userId, Set<String> permissionCodes) {
            grants.put(userId, permissionCodes);
        }

        @Override
        public AuthorizationSnapshot getForUser(Long userId) {
            Set<String> permissionCodes = grants.get(userId);
            if (permissionCodes == null) {
                throw new BusinessException(ResultCode.UNAUTHORIZED);
            }
            return new AuthorizationSnapshot(Set.of(), permissionCodes);
        }
    }

    /**
     * 日志服务替身：只记录调用，便于断言权限拦截发生在业务调用之前。
     */
    static class StubOperationLogService implements OperationLogService {

        private boolean pageCalled;
        private boolean detailCalled;
        private Long lastDetailId;

        @Override
        public PageResult<OperationLogVO> page(OperationLogQuery query) {
            pageCalled = true;
            return new PageResult<>(List.of(), 0, 1, 10);
        }

        @Override
        public OperationLogVO detail(Long id) {
            detailCalled = true;
            lastDetailId = id;
            OperationLogVO vo = new OperationLogVO();
            vo.setId(id);
            return vo;
        }
    }
}
