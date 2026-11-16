package com.xinghe.cms.content.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.xinghe.cms.common.contract.TagView;
import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.request.PageQuery;
import com.xinghe.cms.common.response.PageResult;
import com.xinghe.cms.common.response.ResultCode;
import com.xinghe.cms.content.entity.Tag;
import com.xinghe.cms.content.mapper.TagMapper;
import com.xinghe.cms.content.vo.TagVO;
import java.sql.SQLException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TagService {

    private static final int ENABLED = 1;
    private static final int MAX_NAME_LENGTH = 64;

    private final TagMapper mapper;

    public TagService(TagMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public PageResult<TagVO> adminPage(PageQuery query, String keyword, Integer status) {
        if (status != null) {
            validateStatus(status);
        }
        return findPage(query, keyword, status, false);
    }

    @Transactional(readOnly = true)
    public PageResult<TagVO> portalPage(PageQuery query, String keyword) {
        return findPage(query, keyword, null, true);
    }

    @Transactional
    public TagVO create(String tagName) {
        Tag tag = new Tag();
        tag.setTagName(normalizeName(tagName));
        tag.setStatus(ENABLED);
        try {
            mapper.insert(tag);
        } catch (DuplicateKeyException ex) {
            throw duplicateName();
        }
        return toView(mapper.selectById(tag.getId()));
    }

    @Transactional
    public TagVO update(Long id, String tagName) {
        String name = normalizeName(tagName);
        lockTag(id);
        try {
            mapper.update(Wrappers.<Tag>lambdaUpdate()
                    .eq(Tag::getId, id)
                    .set(Tag::getTagName, name));
        } catch (DuplicateKeyException ex) {
            throw duplicateName();
        }
        return toView(mapper.selectById(id));
    }

    @Transactional
    public void setStatus(Long id, int status) {
        validateStatus(status);
        Tag tag = lockTag(id);
        if (tag.getStatus() != status) {
            mapper.update(Wrappers.<Tag>lambdaUpdate()
                    .eq(Tag::getId, id)
                    .set(Tag::getStatus, status));
        }
    }

    @Transactional
    public void delete(Long id) {
        lockTag(id);
        if (mapper.hasArticleReference(id)) {
            throw tagInUse();
        }
        try {
            mapper.deleteById(id);
        } catch (DataIntegrityViolationException ex) {
            if (ex.getMostSpecificCause() instanceof SQLException sql && sql.getErrorCode() == 1451) {
                throw tagInUse();
            }
            throw ex;
        }
    }

    private PageResult<TagVO> findPage(PageQuery query, String keyword, Integer status, boolean portal) {
        IPage<Tag> page = mapper.findPage(query.toPage(), status, likePattern(keyword), portal);
        return PageResult.from(page.convert(TagService::toView));
    }

    private Tag lockTag(Long id) {
        if (id == null || id <= 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "标签 ID 必须为正整数");
        }
        Tag tag = mapper.selectForUpdate(id);
        if (tag == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "标签不存在");
        }
        return tag;
    }

    private static TagVO toView(Tag tag) {
        return TagVO.from(new TagView(tag.getId(), tag.getTagName(), tag.getStatus(),
                tag.getCreatedAt(), tag.getUpdatedAt()));
    }

    private static String normalizeName(String tagName) {
        if (tagName == null || tagName.isBlank() || tagName.codePointCount(0, tagName.length()) > MAX_NAME_LENGTH) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "标签名称不能为空且长度不能超过 64");
        }
        return tagName.strip();
    }

    private static String likePattern(String keyword) {
        if (keyword == null) {
            return null;
        }
        if (keyword.codePointCount(0, keyword.length()) > MAX_NAME_LENGTH) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "标签搜索关键词长度不能超过 64");
        }
        String trimmed = keyword.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        return "%" + trimmed.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }

    private static void validateStatus(int status) {
        if (status != 0 && status != ENABLED) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "标签状态必须为 0 或 1");
        }
    }

    private static BusinessException duplicateName() {
        return new BusinessException(ResultCode.CONFLICT, "标签名称已存在");
    }

    private static BusinessException tagInUse() {
        return new BusinessException(ResultCode.CONFLICT, "标签仍有关联文章");
    }
}
