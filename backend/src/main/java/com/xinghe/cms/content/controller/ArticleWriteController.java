package com.xinghe.cms.content.controller;

import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.request.Ids;
import com.xinghe.cms.common.request.QueryParams;
import com.xinghe.cms.common.response.ApiResponse;
import com.xinghe.cms.common.response.ResultCode;
import com.xinghe.cms.content.service.ArticleWriteService;
import com.xinghe.cms.content.vo.ArticleDetailVO;
import com.xinghe.cms.content.vo.ArticleStateVO;
import com.xinghe.cms.security.annotation.RequirePermission;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/admin/articles")
public class ArticleWriteController {

    private final ArticleWriteService articles;

    public ArticleWriteController(ArticleWriteService articles) {
        this.articles = articles;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("article:create")
    public ApiResponse<ArticleDetailVO> create(@RequestBody JsonNode body, HttpServletRequest request) {
        rejectQueryParams(request);
        ArticleWriteRequest draft = ArticleWriteRequest.create(body);
        return ApiResponse.success(articles.create(draft.draft(), draft.tagIds()));
    }

    @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("article:update")
    public ApiResponse<ArticleDetailVO> save(@PathVariable String id, @RequestBody JsonNode body,
            HttpServletRequest request) {
        rejectQueryParams(request);
        long articleId = Ids.parse(id);
        ArticleWriteRequest draft = ArticleWriteRequest.update(body);
        return ApiResponse.success(articles.save(articleId, ArticleWriteRequest.version(body),
                draft.draft(), draft.tagIds()));
    }

    @DeleteMapping("/{id}")
    @RequirePermission("article:delete")
    public ApiResponse<Void> delete(@PathVariable String id, HttpServletRequest request) {
        long articleId = Ids.parse(id);
        articles.delete(articleId, deleteVersion(request.getParameterMap()));
        return ApiResponse.success();
    }

    @PostMapping(path = "/{id}/submit", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("article:submit")
    public ApiResponse<ArticleStateVO> submit(@PathVariable String id, @RequestBody JsonNode body,
            HttpServletRequest request) {
        rejectQueryParams(request);
        ArticleWriteRequest.requireFields(body, Set.of("version"));
        return ApiResponse.success(ArticleStateVO.from(articles.submit(Ids.parse(id),
                ArticleWriteRequest.version(body))));
    }

    private static void rejectQueryParams(HttpServletRequest request) {
        QueryParams.requireAllowedKeys(request.getParameterMap(), Set.of());
    }

    private static int deleteVersion(Map<String, String[]> parameters) {
        QueryParams.requireAllowedKeys(parameters, Set.of("version"));
        QueryParams.requireSingleValueForEachPresentKey(parameters);
        String[] version = parameters.get("version");
        if (version == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "version 必填");
        }
        if ("0".equals(version[0])) {
            return 0;
        }
        QueryParams.requireStrictDecimalValue(parameters, "version");
        long value = QueryParams.parseStrictDecimal(parameters, "version");
        if (value > Integer.MAX_VALUE) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "version 超出允许范围");
        }
        return (int) value;
    }
}
