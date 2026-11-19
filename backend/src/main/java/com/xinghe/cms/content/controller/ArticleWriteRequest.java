package com.xinghe.cms.content.controller;

import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.request.Ids;
import com.xinghe.cms.common.response.ResultCode;
import com.xinghe.cms.content.dto.ArticleDraft;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;

record ArticleWriteRequest(ArticleDraft draft, List<Long> tagIds) {

    private static final Set<String> CREATE_FIELDS = Set.of("title", "summary", "content", "categoryId", "tagIds");
    private static final Set<String> UPDATE_FIELDS =
            Set.of("title", "summary", "content", "categoryId", "tagIds", "version");

    static ArticleWriteRequest create(JsonNode body) {
        requireFields(body, CREATE_FIELDS);
        return new ArticleWriteRequest(draft(body), body.has("tagIds") ? tagIds(body.get("tagIds")) : List.of());
    }

    static ArticleWriteRequest update(JsonNode body) {
        requireFields(body, UPDATE_FIELDS);
        if (!body.has("summary")) {
            throw badRequest("summary 必填，清空摘要请传 null");
        }
        return new ArticleWriteRequest(draft(body), tagIds(body.get("tagIds")));
    }

    static int version(JsonNode body) {
        JsonNode version = body.get("version");
        if (version == null || !version.isIntegralNumber() || !version.canConvertToInt()
                || version.intValue() < 0) {
            throw badRequest("version 必须为非负整数且不超过 2147483647");
        }
        return version.intValue();
    }

    static void requireFields(JsonNode body, Set<String> allowedFields) {
        if (!body.isObject()) {
            throw badRequest("请求体必须为 JSON 对象");
        }
        for (var field : body.properties()) {
            if (!allowedFields.contains(field.getKey())) {
                throw badRequest("未知请求字段: " + field.getKey());
            }
        }
    }

    private static ArticleDraft draft(JsonNode body) {
        JsonNode summary = body.get("summary");
        return new ArticleDraft(text(body.get("title"), "title"),
                summary == null || summary.isNull() ? null : text(summary, "summary"),
                text(body.get("content"), "content"), Ids.parse(text(body.get("categoryId"), "categoryId")));
    }

    private static List<Long> tagIds(JsonNode node) {
        if (node == null || !node.isArray() || node.size() > 100) {
            throw badRequest("tagIds 必须为至多 100 项的数组");
        }
        Set<Long> ids = new LinkedHashSet<>();
        for (JsonNode item : node) {
            if (!ids.add(Ids.parse(text(item, "tagIds 元素")))) {
                throw badRequest("tagIds 不允许重复 ID");
            }
        }
        return List.copyOf(ids);
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.isString()) {
            throw badRequest(field + " 必须为字符串");
        }
        return node.stringValue();
    }

    private static BusinessException badRequest(String message) {
        return new BusinessException(ResultCode.BAD_REQUEST, message);
    }
}
