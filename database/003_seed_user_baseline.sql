-- =====================================================================
-- EnterpriseCMS 003：用户组织基线种子数据（A 模块维护）
-- 依赖：
--   1) 001_init.sql（建表，必须已执行）；
--   2) 002_seed_permission_baseline.sql（B 模块维护，角色/权限基线）。
--      若尚未执行 002，本脚本仍会创建部门与账号，但会跳过角色关联
--      （账号可登录，roles 返回 []），待 002 执行后重跑本脚本即可补齐。
-- 数据：
--   部门 2 个：总部（根，parent_id=NULL）、技术部（总部子部门）
--   账号 3 个：admin / editor / staff，分别关联 ADMIN / EDITOR / EMPLOYEE 角色
-- 特性：
--   1) 可重复执行：全部按业务键（dept_code / username / role_code）定位，
--      指纹未变时只补充缺失行，第二遍执行 0 行变化；
--   2) 只增不改：不 UPDATE 已有账号的密码哈希或姓名，不覆盖团队手工调整的数据；
--   3) 无 DROP / DELETE / TRUNCATE / UPDATE 业务数据；仅清理会话级临时表；
--   4) 角色及角色权限由 B 的 002 负责，本脚本不写入 sys_role / sys_permission；
--   5) 语句写法兼容旧版 MySQL/MariaDB：使用 SELECT ... UNION ALL 派生表，
--      不用 MySQL 8.0.19 才支持的 FROM (VALUES ROW(...)) 语法；
--      注意 001_init.sql 已使用 utf8mb4_0900_ai_ci，实际建库仍需 MySQL 8+；
--   6) 同名账号保护（PR #20 审查修复）：角色关联只认「本脚本写入的账号」，
--      按 password_hash + real_name 指纹确认，绝不按用户名给已有账号补 ADMIN。
--      发现同名但指纹不符的账号时，本次全部写入跳过（0 行变化），并在结果集中
--      列出冲突账号；不会提权，也不会覆盖已有账号。
-- 重复运行说明：
--   按安全要求首次登录后修改了初始密码的账号，指纹将不再匹配，重跑本脚本会
--   报告冲突并跳过写入。此时角色关联已在首次执行时建立，不需要重跑 003；
--   如需补角色，请在 002 就绪后、改密前重跑，或使用正式的账号角色分配接口。
-- 安全边界（务必遵守）：
--   * 本脚本仅用于本地/课程开发环境初始化可登录账号，
--     禁止在生产或任何公网可达环境执行；
--   * 三个账号共用同一初始密码，由仓库负责人在团队内线下同步，不写入仓库；
--     首次登录后必须立即修改，未改密前不得接入任何真实数据；
--   * password_hash 是创建可登录账号的必需字段，必须写在脚本中；
--     它由 security.PasswordHasher 生成（PBKDF2WithHmacSHA256 / 210000 次 /
--     16B 随机盐 / 256 位，base64url），不是明文、不能反推，但仍是弱初始凭据。
-- 执行：mysql --default-character-set=utf8mb4 -h127.0.0.1 -uroot -p < 003_seed_user_baseline.sql
-- =====================================================================

USE enterprise_cms;

-- ---------------------------------------------------------------------
-- 会话级临时表：账号指纹只写一次，供冲突预检、账号插入和角色关联复用。
-- CREATE/DROP TEMPORARY TABLE 不产生隐式提交，也不改动任何业务表结构。
-- ---------------------------------------------------------------------
DROP TEMPORARY TABLE IF EXISTS cms_seed_accounts;
-- 与 001_init.sql 的业务表保持同一排序规则，避免 JOIN 出现
-- "Illegal mix of collations"（业务表显式使用 utf8mb4_0900_ai_ci）。
CREATE TEMPORARY TABLE cms_seed_accounts (
    username      VARCHAR(64)  NOT NULL PRIMARY KEY,
    password_hash VARCHAR(255) NOT NULL,
    real_name     VARCHAR(100) NOT NULL,
    dept_code     VARCHAR(64)  NULL,
    role_code     VARCHAR(64)  NOT NULL
) DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

INSERT INTO cms_seed_accounts
    (username, password_hash, real_name, dept_code, role_code)
VALUES
    ('admin',
     'pbkdf2-sha256$210000$g4d7cXQUljtH34Wvyt483A$_an56CsO4bIHt5YWTbWaBbuWbkr7hAaZ2-OLjwOW53M',
     '系统管理员', 'HQ', 'ADMIN'),
    ('editor',
     'pbkdf2-sha256$210000$xDwhtX27UuRvk_HywMthtQ$Z9ysuktyu9csHMO_Zkvc_Pjs9k0iQ8FiShc5RuI5ovw',
     '内容编辑', 'TECH', 'EDITOR'),
    ('staff',
     'pbkdf2-sha256$210000$pFaQvfUUyX7jskvs9g-7GA$EZo63FQsksGbFDFzkVumCPLrdOey8nHJQrcxGilnNfc',
     '普通员工', 'TECH', 'EMPLOYEE');

-- ---------------------------------------------------------------------
-- 0. 同名账号冲突预检：联调库里若已有同名的 admin / editor / staff 账号，
--    但不是本脚本写入的（哈希或姓名不同），一律视为冲突。此时脚本仍会
--    执行完，但所有 INSERT 均带 @seed_account_conflicts = 0 条件，
--    因此 0 行写入；结果集会列出冲突账号供人工核对。
-- ---------------------------------------------------------------------
SET @seed_account_conflicts = (
    SELECT COUNT(*)
    FROM sys_user u
    JOIN cms_seed_accounts s ON s.username = u.username
    WHERE u.password_hash <> s.password_hash OR u.real_name <> s.real_name
);

SELECT u.username,
       '同名账号并非 003 创建：本次跳过全部写入，请勿用本脚本给已有账号补角色' AS action
FROM sys_user u
JOIN cms_seed_accounts s ON s.username = u.username
WHERE u.password_hash <> s.password_hash OR u.real_name <> s.real_name;

START TRANSACTION;

-- ---------------------------------------------------------------------
-- 1. 部门（缺则补，不改已存在的）
-- ---------------------------------------------------------------------
INSERT INTO sys_department (dept_code, dept_name, parent_id, sort_order)
SELECT v.dept_code, v.dept_name, NULL, v.sort_order
FROM (
    SELECT 'HQ' AS dept_code, '总部' AS dept_name, 0 AS sort_order
) AS v
WHERE @seed_account_conflicts = 0
  AND NOT EXISTS (SELECT 1 FROM sys_department d WHERE d.dept_code = v.dept_code);

INSERT INTO sys_department (dept_code, dept_name, parent_id, sort_order)
SELECT v.dept_code, v.dept_name, parent.id, v.sort_order
FROM (
    SELECT 'TECH' AS dept_code, '技术部' AS dept_name, 'HQ' AS parent_code, 10 AS sort_order
) AS v
JOIN sys_department parent ON parent.dept_code = v.parent_code
WHERE @seed_account_conflicts = 0
  AND NOT EXISTS (SELECT 1 FROM sys_department d WHERE d.dept_code = v.dept_code);

-- ---------------------------------------------------------------------
-- 2. 员工账号（缺则补，不改已存在的；默认启用 status=1）
--    department_id 通过 dept_code 关联，部门缺失时该账号 department_id=NULL，
--    不阻断账号创建。
-- ---------------------------------------------------------------------
INSERT INTO sys_user (username, password_hash, real_name, department_id)
SELECT s.username, s.password_hash, s.real_name, d.id
FROM cms_seed_accounts s
LEFT JOIN sys_department d ON d.dept_code = s.dept_code
WHERE @seed_account_conflicts = 0
  AND NOT EXISTS (SELECT 1 FROM sys_user u WHERE u.username = s.username);

-- ---------------------------------------------------------------------
-- 3. 账号角色关联（仅当 B 的 002 已建立角色时补齐；不删除已有关联）
--    关键安全约束：必须按指纹确认账号确实是本脚本创建的，
--    不能只靠 username 关联，否则已有的同名 admin 会被补上 ADMIN 提权。
-- ---------------------------------------------------------------------
INSERT INTO sys_user_role (user_id, role_id)
SELECT u.id, r.id
FROM cms_seed_accounts s
JOIN sys_user u ON u.username = s.username
JOIN sys_role r ON r.role_code = s.role_code
WHERE @seed_account_conflicts = 0
  AND u.password_hash = s.password_hash
  AND u.real_name = s.real_name
  AND NOT EXISTS (SELECT 1
                  FROM sys_user_role ur
                  WHERE ur.user_id = u.id AND ur.role_id = r.id);

COMMIT;

SELECT @seed_account_conflicts AS conflicting_accounts,
       CASE WHEN @seed_account_conflicts = 0
            THEN '003 执行完毕：按业务键补齐缺失数据，无冲突'
            ELSE '003 未写入任何数据：存在同名但非本脚本创建的账号，请人工核对'
       END AS result;

DROP TEMPORARY TABLE IF EXISTS cms_seed_accounts;

-- ---------------------------------------------------------------------
-- 4. 执行后自检（手工运行确认基线完整）
--    预期：conflicting_accounts=0，departments=2，users=3；已执行 002 时 user_roles=3
-- ---------------------------------------------------------------------
-- SELECT (SELECT COUNT(*) FROM sys_department) AS departments,
--        (SELECT COUNT(*) FROM sys_user)       AS users,
--        (SELECT COUNT(*) FROM sys_user_role)  AS user_roles;
-- SELECT u.username, u.real_name, d.dept_name, r.role_code
-- FROM sys_user u
-- LEFT JOIN sys_department d ON d.id = u.department_id
-- LEFT JOIN sys_user_role ur ON ur.user_id = u.id
-- LEFT JOIN sys_role r       ON r.id = ur.role_id
-- ORDER BY u.id, r.id;
