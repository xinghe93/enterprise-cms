package com.xinghe.cms.review.service.impl;

import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.request.Ids;
import com.xinghe.cms.common.response.ResultCode;
import com.xinghe.cms.review.audit.ArticleTagsAuditRecorder;
import com.xinghe.cms.review.dto.ArticleTagsUpdateRequest;
import com.xinghe.cms.review.service.ArticleTagsCommandService;
import com.xinghe.cms.review.vo.ArticleTagsVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 标签绑定命令实现：参数校验 + 单一业务事务交给审计接入点。 */
@Service
public class ArticleTagsCommandServiceImpl implements ArticleTagsCommandService {

    private final ArticleTagsAuditRecorder auditRecorder;

    public ArticleTagsCommandServiceImpl(ArticleTagsAuditRecorder auditRecorder) {
        this.auditRecorder = auditRecorder;
    }

    @Override
    @Transactional
    public ArticleTagsVO update(long articleId, ArticleTagsUpdateRequest request) {
        if (articleId <= 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "文章 ID 必须为正整数");
        }
        if (request == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "请求体不能为空");
        }
        Integer version = request.version();
        if (version == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "文章版本不能为空");
        }
        if (version < 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "文章版本不能为负数");
        }
        List<String> rawTagIds = request.tagIds();
        if (rawTagIds == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "标签 ID 列表不能为空");
        }
        List<Long> tagIds = new ArrayList<>(rawTagIds.size());
        for (String rawTagId : rawTagIds) {
            tagIds.add(Ids.parse(rawTagId));
        }
        // 契约 §8.3：关联集合替换必须在进入文章写流程前完成全部参数校验。
        // 重复 ID 若留到 replaceBindings 才拒绝，文章版本匹配时已先自增版本再回滚，
        // 版本过期时又会被 transition 提前返回 409——同一种参数错误出现两种状态码。
        if (Set.copyOf(tagIds).size() != tagIds.size()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "标签 ID 不能重复");
        }
        return auditRecorder.bind(articleId, version, tagIds);
    }
}
