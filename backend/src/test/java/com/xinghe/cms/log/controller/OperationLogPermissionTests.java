package com.xinghe.cms.log.controller;

import java.util.List;
import java.util.Set;

import com.xinghe.cms.common.contract.AuthorizationSnapshot;
import com.xinghe.cms.common.response.PageResult;
import com.xinghe.cms.log.dto.OperationLogQuery;
import com.xinghe.cms.log.service.OperationLogService;
import com.xinghe.cms.log.vo.OperationLogVO;
import com.xinghe.cms.permission.service.AuthorizationQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 日志查询接口的功能权限验收：匿名 401、无权限员工 403、有权限用户 200。
 *
 * <p>启动完整 Spring 上下文以走真实的 {@code @RequirePermission} AOP 与
 * A 的 {@link com.xinghe.cms.security.CurrentUserProvider}，只把授权快照与日志服务
 * 替换为替身。MVC 层不注册 Bearer 过滤器（MockMvc 不挂容器过滤器），
 * 身份通过 {@link TestAuthenticationFilter} 写入请求属性模拟。</p>
 */
@SpringBootTest
@Import(OperationLogPermissionTests.TestConfig.class)
class OperationLogPermissionTests {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private StubOperationLogService stubService;

    @Autowired
    private StubAuthorizationQueryService stubAuthorization;

    /** 不带身份：模拟匿名/会话失效。 */
    private MockMvc unauthenticatedMvc;

    /** 带身份：模拟已登录用户。 */
    private MockMvc authenticatedMvc;

    @BeforeEach
    void setUp() {
        unauthenticatedMvc = MockMvcBuilders.webAppContextSetup(context).build();
        authenticatedMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(new TestAuthenticationFilter())
                .build();
        stubService.reset();
        stubAuthorization.reset();
    }

    @Test
    void anonymousListReturnsUnauthorized() throws Exception {
        MvcResult result = unauthenticatedMvc.perform(get("/api/admin/logs")).andReturn();

        assertError(result, 401, "请先登录");
        assertThat(stubService.pageCalled).isFalse();
    }

    @Test
    void anonymousDetailReturnsUnauthorized() throws Exception {
        MvcResult result = unauthenticatedMvc.perform(get("/api/admin/logs/7")).andReturn();

        assertError(result, 401, "请先登录");
        assertThat(stubService.detailCalled).isFalse();
    }

    @Test
    void employeeWithoutPermissionCannotReadList() throws Exception {
        stubAuthorization.permissionCodes = Set.of();

        MvcResult result = authenticatedMvc.perform(get("/api/admin/logs")).andReturn();

        assertError(result, 403, "缺少访问权限: log:list");
        assertThat(stubService.pageCalled).isFalse();
    }

    @Test
    void employeeWithoutPermissionCannotReadDetail() throws Exception {
        stubAuthorization.permissionCodes = Set.of();

        MvcResult result = authenticatedMvc.perform(get("/api/admin/logs/7")).andReturn();

        assertError(result, 403, "缺少访问权限: log:read");
        assertThat(stubService.detailCalled).isFalse();
    }

    @Test
    void detailRequiresReadPermissionEvenWithListPermission() throws Exception {
        stubAuthorization.permissionCodes = Set.of("log:list");

        MvcResult listResult = authenticatedMvc.perform(get("/api/admin/logs")).andReturn();
        assertThat(listResult.getResponse().getStatus()).isEqualTo(200);
        assertThat(stubService.pageCalled).isTrue();

        MvcResult detailResult = authenticatedMvc.perform(get("/api/admin/logs/7")).andReturn();
        assertError(detailResult, 403, "缺少访问权限: log:read");
        assertThat(stubService.detailCalled).isFalse();
    }

    @Test
    void detailAllowsUserWithReadPermission() throws Exception {
        stubAuthorization.permissionCodes = Set.of("log:read");

        MvcResult result = authenticatedMvc.perform(get("/api/admin/logs/7")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(stubService.detailCalled).isTrue();
        assertThat(stubService.lastDetailId).isEqualTo(7L);
        assertThat(stubAuthorization.lastUserId).isEqualTo(TestAuthenticationFilter.USER_ID);
        JsonNode data = body(result).get("data");
        assertThat(data.get("id").isString()).isTrue();
        assertThat(data.get("id").asString()).isEqualTo("7");
    }

    @Test
    void authorizationSnapshotIsQueriedByAuthenticatedUserId() throws Exception {
        stubAuthorization.permissionCodes = Set.of("log:list");

        MvcResult result = authenticatedMvc.perform(get("/api/admin/logs")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(stubAuthorization.lastUserId).isEqualTo(TestAuthenticationFilter.USER_ID);
    }

    private JsonNode body(MvcResult result) throws Exception {
        return jsonMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private void assertError(MvcResult result, int status, String message) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(status);
        JsonNode json = body(result);
        assertThat(json.has("code")).isTrue();
        assertThat(json.has("message")).isTrue();
        assertThat(json.has("data")).isTrue();
        assertThat(json.has("timestamp")).isTrue();
        assertThat(json.get("code").asInt()).isEqualTo(status);
        assertThat(json.get("message").asString()).isEqualTo(message);
        assertThat(json.get("data").isNull()).isTrue();
        assertThat(json.get("timestamp").isTextual()).isTrue();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {

        @Bean
        @Primary
        StubOperationLogService stubOperationLogService() {
            return new StubOperationLogService();
        }

        @Bean
        @Primary
        StubAuthorizationQueryService stubAuthorizationQueryService() {
            return new StubAuthorizationQueryService();
        }
    }

    /**
     * 可配置权限码的授权快照替身：用户始终视为已认证，只调整权限集合；
     * 同时记录实际查询使用的 userId，用于验证权限快照按认证身份取用。
     */
    static class StubAuthorizationQueryService implements AuthorizationQueryService {

        private Set<String> permissionCodes = Set.of();
        private Long lastUserId;

        void reset() {
            permissionCodes = Set.of();
            lastUserId = null;
        }

        @Override
        public AuthorizationSnapshot getForUser(Long userId) {
            this.lastUserId = userId;
            return new AuthorizationSnapshot(Set.of("EMPLOYEE"), permissionCodes);
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

        void reset() {
            pageCalled = false;
            detailCalled = false;
            lastDetailId = null;
        }
    }
}
