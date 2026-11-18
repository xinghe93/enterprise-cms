package com.xinghe.cms.content.controller;

import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.request.Ids;
import com.xinghe.cms.common.request.QueryParams;
import com.xinghe.cms.common.response.ApiResponse;
import com.xinghe.cms.common.response.ResultCode;
import com.xinghe.cms.content.service.CategoryWriteService;
import com.xinghe.cms.content.vo.CategoryVO;
import com.xinghe.cms.security.annotation.RequirePermission;
import jakarta.servlet.http.HttpServletRequest;
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
@RequestMapping("/api/admin/categories")
public class CategoryWriteController {

    private static final Set<String> CATEGORY_FIELDS = Set.of("parentId", "categoryName", "sortOrder");
    private static final Set<String> STATUS_FIELDS = Set.of("status");

    private final CategoryWriteService categories;

    public CategoryWriteController(CategoryWriteService categories) {
        this.categories = categories;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("category:create")
    public ApiResponse<CategoryVO> create(@RequestBody JsonNode body, HttpServletRequest request) {
        validateBody(request, body, CATEGORY_FIELDS);
        JsonNode sortOrder = body.get("sortOrder");
        return ApiResponse.success(categories.create(parentId(body.get("parentId")), categoryName(body),
                sortOrder == null ? null : integerInRange(sortOrder, "sortOrder", Integer.MAX_VALUE)));
    }

    @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("category:update")
    public ApiResponse<CategoryVO> update(@PathVariable String id, @RequestBody JsonNode body,
            HttpServletRequest request) {
        validateBody(request, body, CATEGORY_FIELDS);
        JsonNode parent = body.get("parentId");
        if (parent == null) {
            throw badRequest("parentId 必填，根分类请传 null");
        }
        return ApiResponse.success(categories.update(Ids.parse(id), parentId(parent), categoryName(body),
                integerInRange(body.get("sortOrder"), "sortOrder", Integer.MAX_VALUE)));
    }

    @PutMapping(path = "/{id}/status", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("category:status")
    public ApiResponse<Void> setStatus(@PathVariable String id, @RequestBody JsonNode body,
            HttpServletRequest request) {
        validateBody(request, body, STATUS_FIELDS);
        categories.setStatus(Ids.parse(id), integerInRange(body.get("status"), "status", 1));
        return ApiResponse.success();
    }

    @DeleteMapping("/{id}")
    @RequirePermission("category:delete")
    public ApiResponse<Void> delete(@PathVariable String id, HttpServletRequest request) {
        QueryParams.requireAllowedKeys(request.getParameterMap(), Set.of());
        categories.delete(Ids.parse(id));
        return ApiResponse.success();
    }

    private static void validateBody(HttpServletRequest request, JsonNode body, Set<String> allowedFields) {
        QueryParams.requireAllowedKeys(request.getParameterMap(), Set.of());
        if (!body.isObject()) {
            throw badRequest("请求体必须为 JSON 对象");
        }
        for (var field : body.properties()) {
            if (!allowedFields.contains(field.getKey())) {
                throw badRequest("未知请求字段: " + field.getKey());
            }
        }
    }

    private static Long parentId(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isString()) {
            throw badRequest("parentId 必须为十进制字符串或 null");
        }
        return Ids.parse(node.stringValue());
    }

    private static String categoryName(JsonNode body) {
        JsonNode name = body.get("categoryName");
        if (name == null || !name.isString()) {
            throw badRequest("categoryName 必须为字符串");
        }
        return name.stringValue();
    }

    private static int integerInRange(JsonNode node, String field, int max) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToInt()) {
            throw badRequest(field + " 必须为整数");
        }
        int value = node.intValue();
        if (value < 0 || value > max) {
            throw badRequest(field + " 超出允许范围");
        }
        return value;
    }

    private static BusinessException badRequest(String message) {
        return new BusinessException(ResultCode.BAD_REQUEST, message);
    }
}
