package com.xinghe.cms.review.controller;

import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.response.ApiResponse;
import com.xinghe.cms.common.response.ResultCode;
import com.xinghe.cms.review.dto.ArticleVersionRequest;
import com.xinghe.cms.review.dto.BatchArticlesRequest;
import com.xinghe.cms.review.service.PublishCommandService;
import com.xinghe.cms.review.vo.ArticleStateVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 文章发布/下架命令接口（D 模块，4 个）。
 *
 * <p>路由参数 id 以字符串接收并按 OpenAPI Id 定义校验（无前导零的正十进制、
 * 最长 19 位、不超过 {@link Long#MAX_VALUE}），避免 Spring 把 {@code 0x1}
 * 之类文本提前转换成数字。权限与审计依赖尚未合入的 B / PR #19，此处保留 TODO。</p>
 */
@RestController
@RequestMapping("/api/admin/articles")
public class PublishCommandController {

    private static final Pattern ARTICLE_ID_PATTERN = Pattern.compile("[1-9][0-9]*");

    private final PublishCommandService publishCommandService;

    public PublishCommandController(PublishCommandService publishCommandService) {
        this.publishCommandService = publishCommandService;
    }

    // TODO B 交付 @RequirePermission 后补 article:publish
    // TODO PR #19 合入 dev 后补 @AuditLog(operation = "article.publish")
    @PostMapping("/{id}/publish")
    public ApiResponse<ArticleStateVO> publish(@PathVariable("id") String id,
                                               @Valid @RequestBody ArticleVersionRequest request) {
        return ApiResponse.success(publishCommandService.publish(parseArticleId(id), request));
    }

    // TODO B 交付 @RequirePermission 后补 article:offline
    // TODO PR #19 合入 dev 后补 @AuditLog(operation = "article.offline")
    @PostMapping("/{id}/offline")
    public ApiResponse<ArticleStateVO> offline(@PathVariable("id") String id,
                                               @Valid @RequestBody ArticleVersionRequest request) {
        return ApiResponse.success(publishCommandService.offline(parseArticleId(id), request));
    }

    // TODO B 交付 @RequirePermission 后补 article:publish-batch
    // TODO PR #19 合入 dev 后补 @AuditLog(operation = "article.publish-batch")
    @PostMapping("/batch/publish")
    public ApiResponse<List<ArticleStateVO>> publishBatch(@Valid @RequestBody BatchArticlesRequest request) {
        return ApiResponse.success(publishCommandService.publishBatch(request));
    }

    // TODO B 交付 @RequirePermission 后补 article:offline-batch
    // TODO PR #19 合入 dev 后补 @AuditLog(operation = "article.offline-batch")
    @PostMapping("/batch/offline")
    public ApiResponse<List<ArticleStateVO>> offlineBatch(@Valid @RequestBody BatchArticlesRequest request) {
        return ApiResponse.success(publishCommandService.offlineBatch(request));
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
