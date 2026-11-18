package com.xinghe.cms.review.dto;

import com.xinghe.cms.common.request.PageQuery;
import lombok.Getter;
import lombok.Setter;

/**
 * 待审文章分页查询条件（契约：GET /api/admin/reviews/pending）。
 *
 * <p>只包含契约声明的参数：current/size/keyword/categoryId/authorId。
 * 其中 categoryId、authorId 保留原始字符串，由服务层 {@code Ids.parse} 校验，
 * 避免 Spring 把 {@code 0x1} 之类的取值按十六进制宽松解析；keyword 由服务层
 * 去空白、限长并按 {@code ESCAPE '!'} 语义转义。本接口不接收 status。</p>
 */
@Getter
@Setter
public class PendingReviewQuery extends PageQuery {

    /** 关键词：标题或摘要的字面包含匹配，去首尾空白后为空表示不筛选。 */
    private String keyword;

    /** 分类 ID 原始字符串，按分类及其全部后代筛选。 */
    private String categoryId;

    /** 作者 ID 原始字符串，精确匹配，不扩大读取范围。 */
    private String authorId;
}
