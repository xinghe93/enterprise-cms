package com.xinghe.cms.review.service;

import java.util.List;
import java.util.Map;

import com.xinghe.cms.common.contract.TagView;

/**
 * 文章与标签的关联服务（D 模块对外提供给 C 的共享服务，见跨模块服务接口 V1.0 §4）。
 *
 * <p>本服务只维护 {@code cms_article_tag} 这张关联表，不修改文章正文、状态与版本号，也不写审计日志。</p>
 */
public interface ArticleTagService {

    /**
     * 整体替换一篇文章的标签绑定：请求集合之外的原绑定会被删除，新增的绑定会被插入，交集保留。
     *
     * <p><strong>事务语义：MANDATORY。</strong>调用方（C 创建/保存文章、或 D 的标签绑定接口）必须已经开启事务，
     * 并在调用前完成文章权限/状态校验、持有文章行锁；本方法加入同一个事务，失败时由外层整体回滚，
     * 不使用 REQUIRES_NEW 拆分。</p>
     *
     * <p>校验规则：最多 100 个唯一标签；tagIds 为 null、元素为 null/非正、重复、超过 100 个 → 400；
     * 空列表表示清空该文章全部绑定。相对原绑定新增的标签会按 ID 升序加共享锁校验：不存在 → 404，
     * 已停用（status != 1）→ 409；原本已绑定的停用标签允许保留。全部校验通过后才执行删除与插入。</p>
     *
     * @param articleId 文章 ID，必须为正整数
     * @param tagIds    目标标签 ID 集合；允许空列表（清空绑定），不允许 null
     */
    void replaceBindings(Long articleId, List<Long> tagIds);

    /**
     * 批量查询多篇文章的标签视图，供列表/详情组装 VO 使用（一条 JOIN SQL，禁止循环单条查询）。
     *
     * <p>articleIds 为 null 或空时直接返回空 Map，不执行 SQL；返回的 Map 一定包含每个请求的文章 ID
     * （没有标签的文章值为空列表），每个列表内按标签 ID 升序。只应传入已经过访问校验的主表结果。</p>
     *
     * @param articleIds 文章 ID 列表
     * @return 文章 ID → 标签视图列表（按标签 ID 升序）
     */
    Map<Long, List<TagView>> findByArticleIds(List<Long> articleIds);
}
