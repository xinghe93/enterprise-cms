package com.xinghe.cms.review.controller;

import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.request.QueryParams;
import com.xinghe.cms.common.response.ApiResponse;
import com.xinghe.cms.common.response.ResultCode;
import com.xinghe.cms.review.dto.ArticleTagsUpdateRequest;
import com.xinghe.cms.review.service.ArticleTagsCommandService;
import com.xinghe.cms.review.vo.ArticleTagsVO;
import com.xinghe.cms.security.annotation.RequirePermission;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * 文章标签绑定入口（OpenAPI {@code PUT /api/admin/articles/{id}/tags}，D 负责）。
 *
 * <p>权限由 B 的 {@link RequirePermission} 校验；状态/版本/写范围由 C 的
 * {@code ArticleWorkflowService#transition(BIND_TAGS)} 校验并自增版本；
 * 标签存在性、停用状态与重复校验由 {@code ArticleTagService#replaceBindings} 完成。
 * 契约 8.1：本接口不接受任何查询参数。</p>
 */
@RestController
@RequestMapping("/api/admin/articles")
public class ArticleTagsController {

    /** OpenAPI Id 定义：无前导零的正十进制整数，最大值为 {@link Long#MAX_VALUE}。 */
    private static final Pattern ARTICLE_ID_PATTERN = Pattern.compile("[1-9][0-9]*");

    /** 本接口不接受查询参数。 */
    private static final Set<String> ALLOWED_QUERY_PARAMS = Set.of();

    private final ArticleTagsCommandService articleTagsCommandService;

    public ArticleTagsController(ArticleTagsCommandService articleTagsCommandService) {
        this.articleTagsCommandService = articleTagsCommandService;
    }

    @RequirePermission("article:bind-tag")
    @PutMapping("/{id}/tags")
    public ApiResponse<ArticleTagsVO> update(@PathVariable String id,
                                             @Valid @RequestBody ArticleTagsUpdateRequest request,
                                             HttpServletRequest servletRequest) {
        QueryParams.requireAllowedKeys(servletRequest.getParameterMap(), ALLOWED_QUERY_PARAMS);
        return ApiResponse.success(articleTagsCommandService.update(parseArticleId(id), request));
    }

    private static long parseArticleId(String rawId) {
        if (rawId == null || !ARTICLE_ID_PATTERN.matcher(rawId).matches()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "请求参数错误");
        }
        try {
            return Long.parseLong(rawId);
        } catch (NumberFormatException exception) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "请求参数错误");
        }
    }
}
