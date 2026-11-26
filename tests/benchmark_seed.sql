-- =====================================================================
-- EnterpriseCMS 阶段 5 压测与 SQL/EXPLAIN 优化对比专用造数脚本
-- 位置：tests/benchmark_seed.sql（独立于正式 database/ 初始化链）
--
-- 前置条件：
--   1) 仅在专用测试/压测库执行（建议新建 enterprise_cms_bench），严禁用于生产库；
--   2) 目标库已执行 001_init.sql、002_seed_permission_baseline.sql、003_seed_user_baseline.sql；
--   3) 如需做「索引优化前后 EXPLAIN 对比」，可分别直接执行末尾 EXPLAIN 语句（命中复合索引）
--      与附加 IGNORE INDEX (...) 执行同一 SQL，保存优化前后执行计划对比。
--
-- 造数规模（幂等，重复执行不翻倍）：
--   - 压测分类：5 个（名称前缀 [BENCH]）
--   - 压测标签：10 个（名称前缀 BENCH_TAG_）
--   - 压测文章：2,000 篇（PUBLISHED 1400 / PENDING_REVIEW 200 / DRAFT 200 / APPROVED 100 / OFFLINE 100）
--   - 文章标签关联：约 4,000 条（每篇压测文章绑定 2 个压测标签）
--   - 操作日志：20,000 条（覆盖 8 类高频操作、SUCCESS/FAILURE 混合与近 30 天分布）
--
-- 执行示例（显式指定专用压测库，本脚本不含 USE 语句）：
--   mysql --default-character-set=utf8mb4 -h127.0.0.1 -uroot -p \
--         enterprise_cms_bench < tests/benchmark_seed.sql
-- =====================================================================

SET SESSION cte_max_recursion_depth = 30000;

SET @bench_editor_id = (SELECT id FROM sys_user WHERE username = 'editor' LIMIT 1);
SET @bench_admin_id  = (SELECT id FROM sys_user WHERE username = 'admin' LIMIT 1);

-- ---------------------------------------------------------------------
-- 1. 压测分类（5 个根分类，按名称幂等）
-- ---------------------------------------------------------------------
INSERT INTO cms_category (parent_id, category_name, sort_order, status)
SELECT NULL, v.category_name, v.sort_order, 1
FROM (
    SELECT '[BENCH] 工程规范' AS category_name, 101 AS sort_order UNION ALL
    SELECT '[BENCH] 架构设计', 102 UNION ALL
    SELECT '[BENCH] 运维手册', 103 UNION ALL
    SELECT '[BENCH] 安全合规', 104 UNION ALL
    SELECT '[BENCH] 新人培训', 105
) AS v
WHERE NOT EXISTS (SELECT 1 FROM cms_category c WHERE c.category_name = v.category_name);

-- ---------------------------------------------------------------------
-- 2. 压测标签（10 个启用标签，按唯一名称幂等）
-- ---------------------------------------------------------------------
INSERT INTO cms_tag (tag_name, status)
WITH RECURSIVE seq(n) AS (
    SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 10
)
SELECT CONCAT('BENCH_TAG_', LPAD(n, 2, '0')), 1
FROM seq
WHERE NOT EXISTS (
    SELECT 1 FROM cms_tag t WHERE t.tag_name = CONCAT('BENCH_TAG_', LPAD(n, 2, '0'))
);

-- ---------------------------------------------------------------------
-- 3. 压测文章（2,000 篇；若已存在 [BENCH-0001] 则跳过整批，确保幂等）
-- ---------------------------------------------------------------------
SET @bench_articles_exist = (
    SELECT COUNT(*) FROM cms_article WHERE title = '[BENCH-0001] 压测知识文档 #1'
);

SET @c1 = (SELECT id FROM cms_category WHERE category_name = '[BENCH] 工程规范' LIMIT 1);
SET @c2 = (SELECT id FROM cms_category WHERE category_name = '[BENCH] 架构设计' LIMIT 1);
SET @c3 = (SELECT id FROM cms_category WHERE category_name = '[BENCH] 运维手册' LIMIT 1);
SET @c4 = (SELECT id FROM cms_category WHERE category_name = '[BENCH] 安全合规' LIMIT 1);
SET @c5 = (SELECT id FROM cms_category WHERE category_name = '[BENCH] 新人培训' LIMIT 1);

INSERT INTO cms_article
    (title, summary, content, category_id, author_id, status, visibility, published_at, version, is_deleted, created_at, updated_at)
WITH RECURSIVE seq(n) AS (
    SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 2000
)
SELECT
    CONCAT('[BENCH-', LPAD(n, 4, '0'), '] 压测知识文档 #', n),
    CONCAT('压测摘要：用于验证分页查询、多表 JOIN、索引优化与 Redis 缓存实验，编号 ', n),
    CONCAT('压测正文内容（编号 ', n, '）：', REPEAT('EnterpriseCMS 内部知识文档压测样本段落。', 8)),
    CASE MOD(n, 5) WHEN 0 THEN @c1 WHEN 1 THEN @c2 WHEN 2 THEN @c3 WHEN 3 THEN @c4 ELSE @c5 END,
    IF(MOD(n, 4) = 0 AND @bench_admin_id IS NOT NULL, @bench_admin_id, @bench_editor_id),
    CASE
        WHEN n <= 1400 THEN 'PUBLISHED'
        WHEN n <= 1600 THEN 'PENDING_REVIEW'
        WHEN n <= 1800 THEN 'DRAFT'
        WHEN n <= 1900 THEN 'APPROVED'
        ELSE 'OFFLINE'
    END,
    'ALL',
    IF(n <= 1400, TIMESTAMPADD(MINUTE, -n * 15, NOW(3)), NULL),
    MOD(n, 5),
    0,
    TIMESTAMPADD(MINUTE, -n * 20, NOW(3)),
    TIMESTAMPADD(MINUTE, -n * 10, NOW(3))
FROM seq
WHERE @bench_articles_exist = 0
  AND @bench_editor_id IS NOT NULL
  AND @c1 IS NOT NULL;

-- ---------------------------------------------------------------------
-- 4. 压测文章-标签关联（每篇压测文章绑定 2 个压测标签，按 NOT EXISTS 幂等）
-- ---------------------------------------------------------------------
INSERT INTO cms_article_tag (article_id, tag_id)
SELECT a.id, t.id
FROM cms_article a
JOIN cms_tag t
  ON t.tag_name IN (
      CONCAT('BENCH_TAG_', LPAD(MOD(a.id, 10) + 1, 2, '0')),
      CONCAT('BENCH_TAG_', LPAD(MOD(a.id + 3, 10) + 1, 2, '0'))
  )
WHERE a.title LIKE '[BENCH-%'
  AND NOT EXISTS (
      SELECT 1 FROM cms_article_tag x
      WHERE x.article_id = a.id AND x.tag_id = t.id
  );

-- ---------------------------------------------------------------------
-- 5. 压测操作日志（20,000 条；按特殊压测路径 /api/admin/bench/seed 判幂等）
-- ---------------------------------------------------------------------
SET @bench_logs_exist = (
    SELECT COUNT(*) FROM sys_operation_log WHERE request_uri = '/api/admin/bench/seed'
);

INSERT INTO sys_operation_log
    (user_id, operation, request_uri, http_method, result, duration_ms, created_at)
WITH RECURSIVE seq(n) AS (
    SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 20000
)
SELECT
    IF(MOD(n, 3) = 0, @bench_editor_id, @bench_admin_id),
    CASE MOD(n, 8)
        WHEN 0 THEN 'article.create'
        WHEN 1 THEN 'article.update'
        WHEN 2 THEN 'article.submit'
        WHEN 3 THEN 'article.approve'
        WHEN 4 THEN 'article.reject'
        WHEN 5 THEN 'article.publish'
        WHEN 6 THEN 'role.assign-permission'
        ELSE 'user.status'
    END,
    IF(n = 1, '/api/admin/bench/seed', CONCAT('/api/admin/articles/', MOD(n, 2000) + 1)),
    IF(MOD(n, 8) IN (1, 6, 7), 'PUT', 'POST'),
    IF(MOD(n, 10) = 0, 'FAILURE', 'SUCCESS'),
    5 + MOD(n * 17, 120),
    TIMESTAMPADD(SECOND, -n * 120, NOW(3))
FROM seq
WHERE @bench_logs_exist = 0
  AND @bench_admin_id IS NOT NULL;

-- ---------------------------------------------------------------------
-- 6. 造数规模汇总
-- ---------------------------------------------------------------------
SELECT '压测分类' AS item, COUNT(*) AS rows_count FROM cms_category WHERE category_name LIKE '[BENCH]%'
UNION ALL
SELECT '压测标签', COUNT(*) FROM cms_tag WHERE tag_name LIKE 'BENCH_TAG_%'
UNION ALL
SELECT '压测文章', COUNT(*) FROM cms_article WHERE title LIKE '[BENCH-%'
UNION ALL
SELECT '压测标签关联', COUNT(*) FROM cms_article_tag at
  JOIN cms_article a ON a.id = at.article_id WHERE a.title LIKE '[BENCH-%'
UNION ALL
SELECT '操作日志总计', COUNT(*) FROM sys_operation_log;

-- ---------------------------------------------------------------------
-- 7. 阶段 5「SQL 优化前后 EXPLAIN 对比」建议语句（同一数据集分别使用默认计划和第 8–9 行的 IGNORE INDEX 对照）
-- ---------------------------------------------------------------------
-- (A) 待审核队列分页（是否消除 Using filesort）
-- EXPLAIN SELECT a.id, a.title, c.category_name, u.real_name
-- FROM cms_article a
-- JOIN cms_category c ON c.id = a.category_id
-- JOIN sys_user u ON u.id = a.author_id
-- WHERE a.is_deleted = 0 AND a.status = 'PENDING_REVIEW' AND a.author_id <> 1
-- ORDER BY a.updated_at ASC, a.id ASC LIMIT 10;
--
-- (B) 后台文章全量分页（idx_article_deleted_updated）
-- EXPLAIN SELECT a.id, a.title, a.status, a.updated_at
-- FROM cms_article a
-- WHERE a.is_deleted = 0
-- ORDER BY a.updated_at DESC, a.id DESC LIMIT 10;
--
-- (C) 操作日志按操作类型筛选（idx_operation_log_op_time）
-- EXPLAIN SELECT l.id, l.user_id, l.operation, l.result, l.created_at
-- FROM sys_operation_log l
-- WHERE l.operation = 'article.approve'
-- ORDER BY l.created_at DESC, l.id DESC LIMIT 10;
--
-- (D) 操作日志按失败结果筛选（idx_operation_log_result_time）
-- EXPLAIN SELECT l.id, l.user_id, l.operation, l.result, l.created_at
-- FROM sys_operation_log l
-- WHERE l.result = 'FAILURE'
-- ORDER BY l.created_at DESC, l.id DESC LIMIT 10;
