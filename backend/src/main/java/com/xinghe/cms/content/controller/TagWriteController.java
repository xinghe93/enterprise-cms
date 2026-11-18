package com.xinghe.cms.content.controller;

import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.json.StrictStringDeserializer;
import com.xinghe.cms.common.request.Ids;
import com.xinghe.cms.common.request.QueryParams;
import com.xinghe.cms.common.response.ApiResponse;
import com.xinghe.cms.common.response.ResultCode;
import com.xinghe.cms.content.service.TagWriteService;
import com.xinghe.cms.content.vo.TagVO;
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
import tools.jackson.databind.annotation.JsonDeserialize;

@RestController
@RequestMapping("/api/admin/tags")
public class TagWriteController {

    private final TagWriteService tags;

    public TagWriteController(TagWriteService tags) {
        this.tags = tags;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("tag:create")
    public ApiResponse<TagVO> create(@RequestBody TagNameRequest body, HttpServletRequest request) {
        rejectQueryParams(request);
        return ApiResponse.success(tags.create(requireName(body)));
    }

    @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("tag:update")
    public ApiResponse<TagVO> update(@PathVariable String id, @RequestBody TagNameRequest body,
            HttpServletRequest request) {
        rejectQueryParams(request);
        return ApiResponse.success(tags.update(Ids.parse(id), requireName(body)));
    }

    @PutMapping(path = "/{id}/status", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("tag:status")
    public ApiResponse<Void> setStatus(@PathVariable String id, @RequestBody StatusRequest body,
            HttpServletRequest request) {
        rejectQueryParams(request);
        tags.setStatus(Ids.parse(id), requireStatus(body.status()));
        return ApiResponse.success();
    }

    @DeleteMapping("/{id}")
    @RequirePermission("tag:delete")
    public ApiResponse<Void> delete(@PathVariable String id, HttpServletRequest request) {
        rejectQueryParams(request);
        tags.delete(Ids.parse(id));
        return ApiResponse.success();
    }

    private static void rejectQueryParams(HttpServletRequest request) {
        QueryParams.requireAllowedKeys(request.getParameterMap(), Set.of());
    }

    private static String requireName(TagNameRequest body) {
        if (body.tagName() == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "tagName 必填");
        }
        return body.tagName();
    }

    private static int requireStatus(JsonNode status) {
        if (status == null || !status.isIntegralNumber() || !status.canConvertToInt()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "status 必须为整数 0 或 1");
        }
        int value = status.intValue();
        if (value != 0 && value != 1) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "status 必须为整数 0 或 1");
        }
        return value;
    }

    public record TagNameRequest(@JsonDeserialize(using = StrictStringDeserializer.class) String tagName) {
    }

    public record StatusRequest(JsonNode status) {
    }
}
