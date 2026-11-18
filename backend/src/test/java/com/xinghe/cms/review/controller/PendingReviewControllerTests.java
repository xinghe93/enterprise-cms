package com.xinghe.cms.review.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xinghe.cms.common.enums.ArticleStatus;
import com.xinghe.cms.common.exception.GlobalExceptionHandler;
import com.xinghe.cms.common.response.PageResult;
import com.xinghe.cms.review.service.PendingReviewService;
import com.xinghe.cms.review.vo.ArticleSummaryVO;
import com.xinghe.cms.review.vo.ArticleTagVO;
import com.xinghe.cms.security.annotation.RequirePermission;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 待审分页接口的查询参数契约与响应格式测试。
 *
 * <p>使用 standalone MockMvc 装配真实 Controller 与 {@link GlobalExceptionHandler}；
 * Service 用可返回固定分页的替身隔离数据库。权限注解由 A 的切面在真实请求链路上校验，
 * 本类只反射确认注解存在（401/403/200 的验收在真实库联调阶段完成）。</p>
 */
class PendingReviewControllerTests {

    private PendingReviewService stubService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        stubService = query -> new PageResult<>(List.of(sampleSummary()), 5, 1, 10);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new PendingReviewController(stubService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void returnsArticleSummaryPageWithStringIdsAndMillisecondTimes() throws Exception {
        mockMvc.perform(get("/api/admin/reviews/pending"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(5))
                .andExpect(jsonPath("$.data.current").value(1))
                .andExpect(jsonPath("$.data.size").value(10))
                .andExpect(jsonPath("$.data.records[0].id").value("990201"))
                .andExpect(jsonPath("$.data.records[0].categoryId").value("991101"))
                .andExpect(jsonPath("$.data.records[0].authorId").value("991201"))
                .andExpect(jsonPath("$.data.records[0].status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.data.records[0].visibility").value("ALL"))
                .andExpect(jsonPath("$.data.records[0].version").value(3))
                .andExpect(jsonPath("$.data.records[0].tags[0].id").value("7"))
                .andExpect(jsonPath("$.data.records[0].tags[0].status").value(0))
                .andExpect(jsonPath("$.data.records[0].updatedAt").value("2026-11-15T10:00:00.120"));
    }

    @Test
    void rejectsUnknownQueryParameter() throws Exception {
        mockMvc.perform(get("/api/admin/reviews/pending").param("status", "PENDING_REVIEW"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("未知查询参数: status"));
    }

    @Test
    void rejectsDuplicatedQueryParameter() throws Exception {
        mockMvc.perform(get("/api/admin/reviews/pending")
                        .param("current", "1")
                        .param("current", "2"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("查询参数重复传值: current"));
    }

    @Test
    void rejectsZeroPageNumber() throws Exception {
        mockMvc.perform(get("/api/admin/reviews/pending").param("current", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("查询参数取值非法: current"));
    }

    @Test
    void rejectsHexadecimalCategoryId() throws Exception {
        mockMvc.perform(get("/api/admin/reviews/pending").param("categoryId", "0x1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("查询参数取值非法: categoryId"));
    }

    @Test
    void rejectsIdExceedingLongRange() throws Exception {
        mockMvc.perform(get("/api/admin/reviews/pending").param("authorId", "9999999999999999999"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("资源 ID 格式不正确"));
    }

    @Test
    void exposesReviewListPermissionAnnotation() throws Exception {
        RequirePermission annotation = PendingReviewController.class
                .getMethod("pending", HttpServletRequest.class)
                .getAnnotation(RequirePermission.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.value()).containsExactly("article:review-list");
    }

    private static ArticleSummaryVO sampleSummary() {
        ArticleTagVO tag = new ArticleTagVO();
        tag.setId(7L);
        tag.setTagName("停用标签");
        tag.setStatus(0);
        tag.setCreatedAt(LocalDateTime.of(2026, 11, 1, 9, 0));
        tag.setUpdatedAt(LocalDateTime.of(2026, 11, 1, 9, 0));

        ArticleSummaryVO summary = new ArticleSummaryVO();
        summary.setId(990201L);
        summary.setTitle("待审文章");
        summary.setSummary("摘要");
        summary.setCategoryId(991101L);
        summary.setCategoryName("IT分类");
        summary.setAuthorId(991201L);
        summary.setAuthorName("IT作者");
        summary.setStatus(ArticleStatus.PENDING_REVIEW);
        summary.setVisibility("ALL");
        summary.setVersion(3);
        summary.setTags(List.of(tag));
        summary.setCreatedAt(LocalDateTime.of(2026, 11, 14, 9, 0));
        summary.setUpdatedAt(LocalDateTime.of(2026, 11, 15, 10, 0, 0, 120_000_000));
        return summary;
    }
}
