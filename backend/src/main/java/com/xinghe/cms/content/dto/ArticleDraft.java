package com.xinghe.cms.content.dto;

import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.response.ResultCode;

/** 文章正文写入的数据，标签关联在外层事务中单独编排。 */
public record ArticleDraft(String title, String summary, String content, Long categoryId) {

    public ArticleDraft {
        if (title == null || title.isBlank() || title.codePointCount(0, title.length()) > 255) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "文章标题不能为空且最多 255 个字符");
        }
        if (summary != null && summary.codePointCount(0, summary.length()) > 1000) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "文章摘要最多 1000 个字符");
        }
        if (content == null || content.isBlank()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "文章正文不能为空");
        }
        if (categoryId == null || categoryId <= 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "分类 ID 不合法");
        }
        title = title.strip();
    }
}
