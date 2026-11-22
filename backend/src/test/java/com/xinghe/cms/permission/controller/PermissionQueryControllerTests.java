package com.xinghe.cms.permission.controller;

import com.xinghe.cms.common.contract.AuthorizationSnapshot;
import com.xinghe.cms.common.contract.CurrentUser;
import com.xinghe.cms.permission.service.AuthorizationQueryService;
import com.xinghe.cms.security.CurrentUserProvider;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * {@code GET /api/auth/permissions} 的 HTTP 契约测试。
 *
 * <p>鉴权链路：真实 {@link CurrentUserProvider}（无身份抛 401）+
 * {@link AuthorizationQueryService} 替身（返回可控权限集合），
 * 用来验证前端登录后获取权限编码的入口。Bearer 过滤器在 MockMvc 下
 * 因 servletPath 为空而跳过，匿名 401 由身份入口产生，与真实链路一致。</p>
 */
@SpringBootTest
class PermissionQueryControllerTests {

    private static final long USER_ID = 990101L;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private StubAuthorizationQueryService stubAuthorizationQueryService;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        stubAuthorizationQueryService.permissionCodes = Set.of();
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void rejectsAnonymousRequestWithUnauthorized() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/auth/permissions")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        JsonNode body = body(result);
        assertThat(body.get("code").asInt()).isEqualTo(401);
        assertThat(body.get("message").asString()).isEqualTo("请先登录");
    }

    @Test
    void returnsPermissionCodesOfAuthenticatedUserInSortedOrder() throws Exception {
        // 快照内部是 Set，迭代顺序不保证；接口按 OpenAPI 约定输出字典升序。
        stubAuthorizationQueryService.permissionCodes = new LinkedHashSet<>(
                List.of("review:list", "article:review-list", "article:approve", "article:read"));

        MvcResult result = mockMvc.perform(get("/api/auth/permissions")
                        .requestAttr(CurrentUserProvider.REQUEST_ATTRIBUTE,
                                new CurrentUser(USER_ID, "reviewer", null)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = body(result);
        assertThat(body.get("code").asInt()).isEqualTo(200);
        JsonNode data = body.get("data");
        assertThat(data.isArray()).isTrue();
        assertThat(data.size()).isEqualTo(4);
        assertThat(stubAuthorizationQueryService.lastUserId).isEqualTo(USER_ID);
        List<String> actual = List.of(data.get(0).asString(), data.get(1).asString(),
                data.get(2).asString(), data.get(3).asString());
        assertThat(actual).containsExactly("article:approve", "article:read", "article:review-list", "review:list");
    }

    @Test
    void returnsEmptyArrayWhenUserHasNoEffectivePermissions() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/auth/permissions")
                        .requestAttr(CurrentUserProvider.REQUEST_ATTRIBUTE,
                                new CurrentUser(USER_ID, "reviewer", null)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = body(result);
        assertThat(body.get("data").isArray()).isTrue();
        assertThat(body.get("data").isEmpty()).isTrue();
    }

    private JsonNode body(MvcResult result) throws Exception {
        return jsonMapper.readTree(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }

    @TestConfiguration
    static class TestConfig {

        @Bean
        @Primary
        StubAuthorizationQueryService stubAuthorizationQueryService() {
            return new StubAuthorizationQueryService();
        }
    }

    static class StubAuthorizationQueryService implements AuthorizationQueryService {

        private Set<String> permissionCodes = Set.of();

        private Long lastUserId;

        @Override
        public AuthorizationSnapshot getForUser(Long userId) {
            lastUserId = userId;
            return new AuthorizationSnapshot(Set.of("REVIEWER"), permissionCodes);
        }
    }
}
