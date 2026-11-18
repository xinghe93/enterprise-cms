-- =====================================================================
-- EnterpriseCMS 004：审核与操作日志演示种子（D 模块，课程演示用）
-- 依赖：
--   1) 001_init.sql（建表，必须已执行）；
--   2) 002_seed_permission_baseline.sql（角色与权限基线，必须已执行）；
--   3) 003_seed_user_baseline.sql（admin / editor / staff 账号，必须已执行）。
-- 用途：
--   为审核工作台、审核历史、发布/下架与操作日志查询准备可重复的演示数据，
--   便于课程设计演示、Apifox 联调与真实登录态端到端验收。
-- 特性：
--   1) 可重复执行：全部按业务键（category_name / tag_name / title / 审核历史指纹）
--      定位，第二遍执行 0 行变化；
--   2) 只增不改：不 UPDATE / DELETE / TRUNCATE 任何业务数据，不写任何密码；
--   3) 作者取 sys_user.username='editor'，审核人取 username='admin'；
--      账号或分类缺失时跳过对应插入，不报错（可先执行 003 后重跑本脚本）；
--   4) 不写 sys_operation_log 的 SUCCESS 记录：审计记录必须由真实接口调用产生，
--      否则无法证明审计链路有效；
--   5) 安全边界：仅用于本地/课程开发环境，禁止在生产或任何公网可达环境执行。
-- 执行：
--   mysql --default-character-set=utf8mb4 -h127.0.0.1 -uroot -p enterprise_cms
--          < 004_seed_review_log_demo.sql
-- =====================================================================

USE enterprise_cms;

-- ---------------------------------------------------------------------
-- 0. 演示账号：作者 editor、审核人 admin（缺少任一账号则后续插入自动跳过）
-- ---------------------------------------------------------------------
SET @demo_editor_id = (SELECT id FROM sys_user WHERE username = 'editor' LIMIT 1);
SET @demo_admin_id = (SELECT id FROM sys_user WHERE username = 'admin' LIMIT 1);

-- ---------------------------------------------------------------------
-- 1. 演示分类（根分类，启用）
-- ---------------------------------------------------------------------
INSERT INTO cms_category (parent_id, category_name, sort_order, status)
SELECT NULL, '课程设计演示分类', 100, 1
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM cms_category WHERE category_name = '课程设计演示分类'
);

SET @demo_category_id = (
    SELECT id FROM cms_category WHERE category_name = '课程设计演示分类' LIMIT 1
);

-- ---------------------------------------------------------------------
-- 2. 演示标签（启用）
-- ---------------------------------------------------------------------
INSERT INTO cms_tag (tag_name, status)
SELECT '演示标签A', 1
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM cms_tag WHERE tag_name = '演示标签A');

INSERT INTO cms_tag (tag_name, status)
SELECT '演示标签B', 1
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM cms_tag WHERE tag_name = '演示标签B');

-- ---------------------------------------------------------------------
-- 3. 演示文章（4 篇，覆盖待审 / 已通过 / 已发布三种状态）
--    注意：作者是 editor，审核人是 admin，便于验证"禁止自审"规则不误伤演示流程
-- ---------------------------------------------------------------------
INSERT INTO cms_article
    (title, summary, content, category_id, author_id, status, visibility, version)
SELECT '演示待审文章：审核通过',
       '演示摘要：用于验证审核通过流程与审计日志 SUCCESS 记录',
       '演示正文：这篇文章用于验证审核通过接口，作者为 editor，审核人为 admin。',
       @demo_category_id, @demo_editor_id, 'PENDING_REVIEW', 'ALL', 0
FROM DUAL
WHERE @demo_editor_id IS NOT NULL
  AND @demo_category_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM cms_article WHERE title = '演示待审文章：审核通过');

INSERT INTO cms_article
    (title, summary, content, category_id, author_id, status, visibility, version)
SELECT '演示待审文章：审核驳回',
       '演示摘要：用于验证驳回流程与审计日志 FAILURE 记录',
       '演示正文：这篇文章用于验证驳回接口，驳回意见必须由请求体提供。',
       @demo_category_id, @demo_editor_id, 'PENDING_REVIEW', 'ALL', 0
FROM DUAL
WHERE @demo_editor_id IS NOT NULL
  AND @demo_category_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM cms_article WHERE title = '演示待审文章：审核驳回');

INSERT INTO cms_article
    (title, summary, content, category_id, author_id, status, visibility, version)
SELECT '演示已通过文章：发布',
       '演示摘要：用于验证发布接口与 publishedAt 写入',
       '演示正文：这篇文章已是 APPROVED 状态，可直接调用发布接口。',
       @demo_category_id, @demo_editor_id, 'APPROVED', 'ALL', 1
FROM DUAL
WHERE @demo_editor_id IS NOT NULL
  AND @demo_category_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM cms_article WHERE title = '演示已通过文章：发布');

INSERT INTO cms_article
    (title, summary, content, category_id, author_id, status, visibility, version, published_at)
SELECT '演示已发布文章：下架',
       '演示摘要：用于验证下架接口与发布时间保留',
       '演示正文：这篇文章已是 PUBLISHED 状态，可直接调用下架接口。',
       @demo_category_id, @demo_editor_id, 'PUBLISHED', 'ALL', 2,
       '2026-11-01 10:00:00.000'
FROM DUAL
WHERE @demo_editor_id IS NOT NULL
  AND @demo_category_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM cms_article WHERE title = '演示已发布文章：下架');

-- 3 篇演示文章已有审核历史：已通过文章（APPROVED）、待驳回文章（REJECTED）
SET @demo_approved_article_id = (
    SELECT id FROM cms_article WHERE title = '演示已通过文章：发布' LIMIT 1
);
SET @demo_rejected_article_id = (
    SELECT id FROM cms_article WHERE title = '演示待审文章：审核驳回' LIMIT 1
);

-- ---------------------------------------------------------------------
-- 4. 审核历史（用于多表 JOIN 查询演示）
-- ---------------------------------------------------------------------
INSERT INTO cms_review (article_id, reviewer_id, result, comment, reviewed_at)
SELECT @demo_approved_article_id, @demo_admin_id, 'APPROVED', NULL, '2026-11-02 09:30:00.000'
FROM DUAL
WHERE @demo_approved_article_id IS NOT NULL
  AND @demo_admin_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM cms_review
      WHERE article_id = @demo_approved_article_id AND result = 'APPROVED'
  );

INSERT INTO cms_review (article_id, reviewer_id, result, comment, reviewed_at)
SELECT @demo_rejected_article_id, @demo_admin_id, 'REJECTED',
       '演示驳回：内容需补充来源说明', '2026-11-02 09:35:00.000'
FROM DUAL
WHERE @demo_rejected_article_id IS NOT NULL
  AND @demo_admin_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM cms_review
      WHERE article_id = @demo_rejected_article_id AND result = 'REJECTED'
  );

-- ---------------------------------------------------------------------
-- 5. 标签关联（已通过文章绑定演示标签A/B，用于标签展示与查询演示）
-- ---------------------------------------------------------------------
INSERT INTO cms_article_tag (article_id, tag_id)
SELECT @demo_approved_article_id, t.id
FROM cms_tag t
WHERE @demo_approved_article_id IS NOT NULL
  AND t.tag_name IN ('演示标签A', '演示标签B')
  AND NOT EXISTS (
      SELECT 1 FROM cms_article_tag x
      WHERE x.article_id = @demo_approved_article_id AND x.tag_id = t.id
  );

-- ---------------------------------------------------------------------
-- 6. 执行结果（便于确认幂等：第二遍执行数量不变）
-- ---------------------------------------------------------------------
SELECT '演示分类' AS item, COUNT(*) AS rows_count
FROM cms_category WHERE category_name = '课程设计演示分类'
UNION ALL
SELECT '演示标签', COUNT(*) FROM cms_tag WHERE tag_name IN ('演示标签A', '演示标签B')
UNION ALL
SELECT '演示文章', COUNT(*) FROM cms_article WHERE title LIKE '演示%'
UNION ALL
SELECT '演示审核历史', COUNT(*)
FROM cms_review
WHERE article_id IN (@demo_approved_article_id, @demo_rejected_article_id)
UNION ALL
SELECT '演示标签关联', COUNT(*)
FROM cms_article_tag WHERE article_id = @demo_approved_article_id;
