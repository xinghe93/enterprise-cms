package com.xinghe.cms.review.service;

import com.xinghe.cms.review.dto.ArticleVersionRequest;
import com.xinghe.cms.review.dto.BatchArticlesRequest;
import com.xinghe.cms.review.vo.ArticleStateVO;

import java.util.List;

/**
 * 文章发布/下架命令（D 模块）。
 *
 * <p>状态流转、权限、版本冲突、行锁与 publishedAt 写入全部委托 C 的
 * {@link com.xinghe.cms.content.service.ArticleWorkflowService#transition}；
 * 本服务只做参数校验、批量编排与结果映射，不直接更新 cms_article。</p>
 */
public interface PublishCommandService {

    /** 单个发布：仅 APPROVED → PUBLISHED（权限由 C 的 transition 内部校验）。 */
    ArticleStateVO publish(long articleId, ArticleVersionRequest request);

    /** 单个下架：仅 PUBLISHED → OFFLINE，保留最近发布时间。 */
    ArticleStateVO offline(long articleId, ArticleVersionRequest request);

    /** 批量发布：先校验全部参数，再按文章 ID 升序逐个 transition，结果按请求顺序返回。 */
    List<ArticleStateVO> publishBatch(BatchArticlesRequest request);

    /** 批量下架：语义同 {@link #publishBatch}，动作换为 BATCH_OFFLINE。 */
    List<ArticleStateVO> offlineBatch(BatchArticlesRequest request);
}
