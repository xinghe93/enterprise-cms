package com.xinghe.cms.user.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.response.ResultCode;
import com.xinghe.cms.security.AuthSessionService;
import com.xinghe.cms.security.BearerTokenAuthenticationFilter;
import com.xinghe.cms.user.dto.DepartmentCreateRequest;
import com.xinghe.cms.user.dto.DepartmentUpdateRequest;
import com.xinghe.cms.user.mapper.UserAuthMapper;
import com.xinghe.cms.user.mapper.row.CurrentUserStatusRow;
import com.xinghe.cms.user.service.DepartmentAdminService;
import com.xinghe.cms.user.vo.DepartmentVO;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * 部门 5 个接口的 HTTP 层回归：真实 DispatcherServlet、Boot 的 Jackson 配置、
 * 公共异常处理器与认证过滤器；业务服务打桩，无需真实 MySQL/Redis。
 */
@SpringBootTest
class DepartmentAdminControllerHttpTests {

    private static final String TOKEN = "good-token";
    private static final String DEPARTMENTS = "/api/admin/departments";

    @Autowired
    private WebApplicationContext context;

    @MockitoBean
    private DepartmentAdminService departmentService;
    @MockitoBean
    private AuthSessionService sessions;
    @MockitoBean
    private UserAuthMapper userAuthMapper;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        when(sessions.resolveIdentity(TOKEN))
                .thenReturn(new AuthSessionService.SessionIdentity(9L, "epoch"));
        when(userAuthMapper.findStatusById(9L))
                .thenReturn(new CurrentUserStatusRow("admin", null, 1));
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean(BearerTokenAuthenticationFilter.class))
                .build();
    }

    private DepartmentVO node() {
        return new DepartmentVO("5", null, "DEV", "研发部门", 0, 1, List.of(),
                "2026-11-02T16:00:00.000", "2026-11-02T16:00:00.000");
    }

    private ResultActions perform(MockHttpServletRequestBuilder request, String path)
            throws Exception {
        return mockMvc.perform(request.servletPath(path)
                .header("Authorization", "Bearer " + TOKEN));
    }

    @Test
    void treeRejectsUnknownAndInvalidQueryParams() throws Exception {
        for (String[] param : new String[][]{
                {"foo", "1"}, {"status", "2"}, {"status", "01"}, {"status", "true"}}) {
            perform(get(DEPARTMENTS).param(param[0], param[1]), DEPARTMENTS)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(400));
        }
        verifyNoInteractions(departmentService);
    }

    @Test
    void treePassesStatusFilterAndReturnsNodes() throws Exception {
        when(departmentService.getTree(0)).thenReturn(List.of(node()));

        perform(get(DEPARTMENTS).param("status", "0"), DEPARTMENTS)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].id").value("5"))
                .andExpect(jsonPath("$.data[0].children").isArray());

        verify(departmentService).getTree(0);
    }

    @Test
    void treeWithoutStatusReturnsAllNodes() throws Exception {
        when(departmentService.getTree(null)).thenReturn(List.of(node()));

        perform(get(DEPARTMENTS), DEPARTMENTS)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].deptCode").value("DEV"));

        verify(departmentService).getTree(null);
    }

    @Test
    void createRejectsInvalidBodiesBeforeEnteringService() throws Exception {
        List<String> invalid = List.of(
                "{}",
                "[]",
                "null",
                "{\"deptName\":\"研发部门\"}",
                "{\"deptCode\":\"DEV\"}",
                "{\"deptCode\":1,\"deptName\":\"研发部门\"}",
                "{\"deptCode\":\"DEV\",\"deptName\":null}",
                "{\"deptCode\":\"  \",\"deptName\":\"研发部门\"}",
                "{\"deptCode\":\"DEV\",\"deptName\":\"研发部门\",\"status\":1}",
                "{\"deptCode\":\"DEV\",\"deptName\":\"研发部门\",\"parentId\":1}",
                "{\"deptCode\":\"DEV\",\"deptName\":\"研发部门\",\"parentId\":\"01\"}",
                "{\"deptCode\":\"DEV\",\"deptName\":\"研发部门\",\"sortOrder\":\"0\"}",
                "{\"deptCode\":\"DEV\",\"deptName\":\"研发部门\",\"sortOrder\":-1}",
                "{\"deptCode\":\"DEV\",\"deptName\":\"研发部门\",\"sortOrder\":2147483648}");
        for (String body : invalid) {
            perform(post(DEPARTMENTS).contentType(MediaType.APPLICATION_JSON).content(body),
                    DEPARTMENTS)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(400));
        }
        verifyNoInteractions(departmentService);
    }

    @Test
    void createReturnsLeafNode() throws Exception {
        when(departmentService.create(any(DepartmentCreateRequest.class))).thenReturn(node());

        perform(post(DEPARTMENTS).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deptCode\":\"DEV\",\"deptName\":\"研发部门\"}"), DEPARTMENTS)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("5"))
                .andExpect(jsonPath("$.data.status").value(1))
                .andExpect(jsonPath("$.data.children").isArray());

        verify(departmentService).create(new DepartmentCreateRequest(null, "DEV", "研发部门", 0));
    }

    @Test
    void updateRequiresAllThreeFields() throws Exception {
        String path = DEPARTMENTS + "/5";
        for (String body : List.of(
                "{}",
                "{\"parentId\":null,\"deptName\":\"研发部门\"}",
                "{\"parentId\":null,\"sortOrder\":0}",
                "{\"deptName\":\"研发部门\",\"sortOrder\":0}",
                "{\"parentId\":null,\"deptName\":\"研发部门\",\"sortOrder\":0,\"deptCode\":\"X\"}",
                "{\"parentId\":null,\"deptName\":\"研发部门\",\"sortOrder\":0,\"status\":1}",
                "{\"parentId\":null,\"deptName\":\"研发部门\",\"sortOrder\":null}")) {
            perform(put(path).contentType(MediaType.APPLICATION_JSON).content(body), path)
                    .andExpect(status().isBadRequest());
        }
        when(departmentService.update(anyLong(), any(DepartmentUpdateRequest.class)))
                .thenReturn(node());
        perform(put(path).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":null,\"deptName\":\"研发部门\",\"sortOrder\":0}"),
                path)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("5"));
        verify(departmentService).update(5L, new DepartmentUpdateRequest(null, "研发部门", 0));
    }

    @Test
    void statusBodyMustBeStrictIntegerEnum() throws Exception {
        String path = DEPARTMENTS + "/5/status";
        for (String body : List.of(
                "{}", "[]", "null", "{\"status\":\"0\"}", "{\"status\":true}",
                "{\"status\":1.5}", "{\"status\":2}", "{\"status\":-1}",
                "{\"status\":0,\"extra\":1}")) {
            perform(put(path).contentType(MediaType.APPLICATION_JSON).content(body), path)
                    .andExpect(status().isBadRequest());
        }
        perform(put(path).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":0}"), path)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(nullValue()));
        verify(departmentService).changeStatus(5L, 0);
    }

    @Test
    void invalidIdsReturn400BeforeEnteringService() throws Exception {
        for (String id : List.of("0", "01", "abc", "1.0", "-1", "99999999999999999999")) {
            String detail = DEPARTMENTS + "/" + id;
            perform(delete(detail), detail).andExpect(status().isBadRequest());
            String status = detail + "/status";
            perform(put(status).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"status\":0}"), status).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(departmentService);
    }

    @Test
    void missingTokenReturns401BeforeEnteringService() throws Exception {
        mockMvc.perform(get(DEPARTMENTS).servletPath(DEPARTMENTS))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
        verifyNoInteractions(departmentService);
    }

    @Test
    void serviceErrorsMapTo404And409() throws Exception {
        String path = DEPARTMENTS + "/5";
        doThrow(new BusinessException(ResultCode.NOT_FOUND, "部门不存在"))
                .when(departmentService).delete(5L);
        perform(delete(path), path)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));

        doThrow(new BusinessException(ResultCode.CONFLICT, "部门编码已存在"))
                .when(departmentService).create(any(DepartmentCreateRequest.class));
        perform(post(DEPARTMENTS).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deptCode\":\"DEV\",\"deptName\":\"研发部门\"}"), DEPARTMENTS)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));
    }
}
