-- =====================================================================
-- EnterpriseCMS 005：审核与操作日志演示种子（D 模块，课程演示用）
-- 依赖：
--   1) 001_init.sql（建表，必须已执行）；
--   2) 002_seed_permission_baseline.sql（角色与权限基线，必须已执行）；
--   3) 003_seed_user_baseline.sql（admin / editor / staff 账号，必须已执行）。
-- 目标库：
--   本脚本不含 USE 语句，写入调用方当前选中的数据库。请显式指定目标库，
--   例如隔离库 enterprise_cms_seedtest：
--     mysql --default-character-set=utf8mb4 -h127.0.0.1 -uroot -p \
--           enterprise_cms_seedtest < 005_seed_review_log_demo.sql
--   不要依赖“默认库”或客户端上一次选中的库，否则可能写进 enterprise_cms。
-- 用途：
--   为审核工作台、审核历史、发布/下架与操作日志查询准备可重复的演示数据
--   （演示分类 1、标签 2、文章 5、审核历史 2、标签关联 2），
--   便于课程设计演示、Apifox 联调与真实登录态端到端验收。
-- 身份与幂等（本轮修复）：
--   1) 演示数据使用固定 ID：分类/文章/审核记录均有稳定主键，
--      关联数据只引用“ID + 标题 + 作者 + 未删除”全部匹配的演示文章，
--      不会因为同标题的真实文章存在而把审核历史/标签挂到别的文章上；
--   2) 每一条 INSERT 都带 NOT EXISTS 守卫，连续执行两遍不新增任何行；
--   3) 若固定 ID 已被其他数据占用，或同名但身份不匹配，则跳过该条插入及
--      其关联数据（脚本不报错，末尾会输出“同名非演示文章”计数供核对）；
--   4) 只增不改：不 UPDATE / DELETE / TRUNCATE 任何业务数据，不写任何密码；
--   5) 作者取 sys_user.username='editor'，审核人取 username='admin'；
--      账号或分类缺失时跳过对应插入，不报错；
--   6) 不写 sys_operation_log 的 SUCCESS 记录：审计记录必须由真实接口调用产生，
--      否则无法证明审计链路有效；
--   7) 安全边界：仅用于本地/课程开发环境，禁止在生产或任何公网可达环境执行。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 0. 演示账号：作者 editor、审核人 admin（缺少任一账号则后续插入自动跳过）
-- ---------------------------------------------------------------------
SET @demo_editor_id = (SELECT id FROM sys_user WHERE username = 'editor' LIMIT 1);
SET @demo_admin_id = (SELECT id FROM sys_user WHERE username = 'admin' LIMIT 1);

-- ---------------------------------------------------------------------
-- 1. 演示分类（根分类，启用；固定 ID 990101）
--    若名称已存在但 ID 不同，则复用已有分类，避免关联到错误分类。
-- ---------------------------------------------------------------------
INSERT INTO cms_category (id, parent_id, category_name, sort_order, status)
SELECT 990101, NULL, '课程设计演示分类', 100, 1
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM cms_category WHERE id = 990101)
  AND NOT EXISTS (SELECT 1 FROM cms_category WHERE category_name = '课程设计演示分类');

SET @demo_category_id = (
    SELECT id FROM cms_category
    WHERE category_name = '课程设计演示分类'
    ORDER BY (id = 990101) DESC
    LIMIT 1
);

-- ---------------------------------------------------------------------
-- 2. 演示标签（启用；tag_name 有唯一约束，按名称幂等）
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
-- 3. 演示文章（5 篇，固定 ID 990301–990305，覆盖待审 2 / 已通过 / 已发布 / 已驳回）
--    注意：作者是 editor，审核人是 admin，便于验证"禁止自审"规则不误伤演示流程。
--    标题没有唯一约束，因此同时用“固定 ID 未被占用 + 同标题不存在”双守卫：
--    同标题的真实文章存在时跳过插入，随后关联数据也不会写（见第 5、6 节的身份检查）。
-- ---------------------------------------------------------------------
INSERT INTO cms_article
    (id, title, summary, content, category_id, author_id, status, visibility, version)
SELECT 990301, '演示待审文章：审核通过',
       '演示摘要：用于验证审核通过流程与审计日志 SUCCESS 记录',
       '演示正文：这篇文章用于验证审核通过接口，作者为 editor，审核人为 admin。',
       @demo_category_id, @demo_editor_id, 'PENDING_REVIEW', 'ALL', 0
FROM DUAL
WHERE @demo_editor_id IS NOT NULL
  AND @demo_category_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM cms_article WHERE id = 990301)
  AND NOT EXISTS (SELECT 1 FROM cms_article WHERE title = '演示待审文章：审核通过');

INSERT INTO cms_article
    (id, title, summary, content, category_id, author_id, status, visibility, version)
SELECT 990302, '演示待审文章：审核驳回',
       '演示摘要：用于验证驳回流程与审计日志 FAILURE 记录',
       '演示正文：这篇文章用于验证驳回接口，驳回意见必须由请求体提供。',
       @demo_category_id, @demo_editor_id, 'PENDING_REVIEW', 'ALL', 0
FROM DUAL
WHERE @demo_editor_id IS NOT NULL
  AND @demo_category_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM cms_article WHERE id = 990302)
  AND NOT EXISTS (SELECT 1 FROM cms_article WHERE title = '演示待审文章：审核驳回');

INSERT INTO cms_article
    (id, title, summary, content, category_id, author_id, status, visibility, version)
SELECT 990303, '演示已通过文章：发布',
       '演示摘要：用于验证发布接口与 publishedAt 写入',
       '演示正文：这篇文章已是 APPROVED 状态，可直接调用发布接口。',
       @demo_category_id, @demo_editor_id, 'APPROVED', 'ALL', 1
FROM DUAL
WHERE @demo_editor_id IS NOT NULL
  AND @demo_category_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM cms_article WHERE id = 990303)
  AND NOT EXISTS (SELECT 1 FROM cms_article WHERE title = '演示已通过文章：发布');

INSERT INTO cms_article
    (id, title, summary, content, category_id, author_id, status, visibility, version, published_at)
SELECT 990304, '演示已发布文章：下架',
       '演示摘要：用于验证下架接口与发布时间保留',
       '演示正文：这篇文章已是 PUBLISHED 状态，可直接调用下架接口。',
       @demo_category_id, @demo_editor_id, 'PUBLISHED', 'ALL', 2,
       '2026-11-01 10:00:00.000'
FROM DUAL
WHERE @demo_editor_id IS NOT NULL
  AND @demo_category_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM cms_article WHERE id = 990304)
  AND NOT EXISTS (SELECT 1 FROM cms_article WHERE title = '演示已发布文章：下架');

INSERT INTO cms_article
    (id, title, summary, content, category_id, author_id, status, visibility, version)
SELECT 990305, '演示已驳回文章：历史',
       '演示摘要：用于展示审核历史中 REJECTED 记录与文章最终状态的一致性',
       '演示正文：这篇文章状态为 REJECTED，配套一条 REJECTED 审核历史，保证状态与历史一致。',
       @demo_category_id, @demo_editor_id, 'REJECTED', 'ALL', 1
FROM DUAL
WHERE @demo_editor_id IS NOT NULL
  AND @demo_category_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM cms_article WHERE id = 990305)
  AND NOT EXISTS (SELECT 1 FROM cms_article WHERE title = '演示已驳回文章：历史');

-- ---------------------------------------------------------------------
-- 4. 演示文章身份解析：ID、标题、作者、未删除四项全部匹配才算演示文章。
--    任一项不匹配（ID 被占用 / 同名真实文章 / 被逻辑删除）→ 变量为 NULL，
--    后续审核历史与标签关联会整体跳过，不会写入错误文章。
-- ---------------------------------------------------------------------
SET @demo_approved_article_id = (
    SELECT id FROM cms_article
    WHERE id = 990303
      AND title = '演示已通过文章：发布'
      AND author_id = @demo_editor_id
      AND is_deleted = 0
    LIMIT 1
);
SET @demo_rejected_article_id = (
    SELECT id FROM cms_article
    WHERE id = 990305
      AND title = '演示已驳回文章：历史'
      AND author_id = @demo_editor_id
      AND is_deleted = 0
    LIMIT 1
);

-- ---------------------------------------------------------------------
-- 5. 审核历史（固定 ID 990601/990602，用于多表 JOIN 查询演示）
--    身份不匹配时 article_id 为 NULL，插入自动跳过。
-- ---------------------------------------------------------------------
INSERT INTO cms_review (id, article_id, reviewer_id, result, comment, reviewed_at)
SELECT 990601, @demo_approved_article_id, @demo_admin_id, 'APPROVED', NULL,
       '2026-11-02 09:30:00.000'
FROM DUAL
WHERE @demo_approved_article_id IS NOT NULL
  AND @demo_admin_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM cms_review WHERE id = 990601)
  AND NOT EXISTS (
      SELECT 1 FROM cms_review
      WHERE article_id = @demo_approved_article_id AND result = 'APPROVED'
  );

INSERT INTO cms_review (id, article_id, reviewer_id, result, comment, reviewed_at)
SELECT 990602, @demo_rejected_article_id, @demo_admin_id, 'REJECTED',
       '演示驳回：内容需补充来源说明', '2026-11-02 09:35:00.000'
FROM DUAL
WHERE @demo_rejected_article_id IS NOT NULL
  AND @demo_admin_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM cms_review WHERE id = 990602)
  AND NOT EXISTS (
      SELECT 1 FROM cms_review
      WHERE article_id = @demo_rejected_article_id AND result = 'REJECTED'
  );

-- ---------------------------------------------------------------------
-- 6. 标签关联（仅绑定身份确认的演示文章 990303 到演示标签A/B）
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
-- 7. 执行结果（便于确认幂等：第二遍执行数量不变）
-- ---------------------------------------------------------------------
SELECT '演示分类' AS item, COUNT(*) AS rows_count
FROM cms_category
WHERE category_name = '课程设计演示分类'
UNION ALL
SELECT '演示标签', COUNT(*) FROM cms_tag WHERE tag_name IN ('演示标签A', '演示标签B')
UNION ALL
SELECT '演示文章', COUNT(*) FROM cms_article
 WHERE id IN (990301, 990302, 990303, 990304, 990305)
   AND title IN ('演示待审文章：审核通过', '演示待审文章：审核驳回',
                 '演示已通过文章：发布', '演示已发布文章：下架', '演示已驳回文章：历史')
   AND author_id = @demo_editor_id
   AND is_deleted = 0
UNION ALL
SELECT '演示审核历史', COUNT(*) FROM cms_review
WHERE id IN (990601, 990602)
UNION ALL
SELECT '演示标签关联', COUNT(*)
FROM cms_article_tag WHERE article_id = @demo_approved_article_id;

-- 同名但身份不匹配的文章数量（>0 表示本次有演示文章被跳过、关联数据未写入）
SELECT '同名非演示文章（已跳过）' AS item,
       COUNT(*) AS rows_count
FROM cms_article
WHERE title IN ('演示待审文章：审核通过', '演示待审文章：审核驳回',
                '演示已通过文章：发布', '演示已发布文章：下架', '演示已驳回文章：历史')
  AND (id NOT IN (990301, 990302, 990303, 990304, 990305)
       OR author_id <> @demo_editor_id
       OR is_deleted = 1);
