package com.xinghe.cms.review.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.util.Collection;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 待审文章分页查询（D 模块）。
 */
@Mapper
public interface PendingReviewMapper {

    /**
     * 分页查询待审核文章：固定 status = PENDING_REVIEW、is_deleted = 0，排除审核人自己的文章，
     * 按 updated_at ASC, id ASC 排序；分页与 count 由 MyBatis-Plus 分页插件追加。
     *
     * @param page        分页对象（会被分页插件改写为当前页结果）
     * @param reviewerId  当前审核人 ID，用于排除自己的文章
     * @param categoryIds 分类及其全部后代 ID；null 表示不按分类筛选
     * @param authorId    作者 ID；null 表示不按作者筛选
     * @param keyword     已按 {@code ESCAPE '!'} 转义并两侧加 % 的关键词；null 表示不筛选
     */
    Page<PendingReviewRow> selectPendingPage(Page<PendingReviewRow> page,
            @Param("reviewerId") long reviewerId,
            @Param("categoryIds") Collection<Long> categoryIds,
            @Param("authorId") Long authorId,
            @Param("keyword") String keyword);
}
