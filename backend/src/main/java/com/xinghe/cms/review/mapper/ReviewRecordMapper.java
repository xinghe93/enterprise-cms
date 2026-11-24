package com.xinghe.cms.review.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xinghe.cms.review.entity.ReviewRecord;
import com.xinghe.cms.review.vo.ReviewRecordVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 审核记录持久层：写入走 BaseMapper，审核历史分页走 XML 里的单条多表 JOIN 查询。
 */
@Mapper
public interface ReviewRecordMapper extends BaseMapper<ReviewRecord> {

    /**
     * 按文章读取审核历史（多表查询：cms_review + sys_user）。
     *
     * <p>排序由 SQL 固定为 reviewed_at DESC, id DESC；分页与 count 由 MyBatis-Plus
     * 分页插件负责，调用方无需手写 LIMIT。调用前必须已通过文章读取范围校验。</p>
     */
    Page<ReviewRecordVO> selectReviewPage(Page<ReviewRecordVO> page, @Param("articleId") Long articleId);
}
