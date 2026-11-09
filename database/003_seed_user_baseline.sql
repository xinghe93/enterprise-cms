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
--      只补充缺失行，第二遍执行 0 行变化；
--   2) 只增不改：不 UPDATE 已有账号的密码哈希或姓名，不覆盖团队手工调整的数据；
--   3) 无 DROP / DELETE / TRUNCATE / UPDATE，对已有数据安全；
--   4) 角色及角色权限由 B 的 002 负责，本脚本不写入 sys_role / sys_permission。
-- 密码：
--   三个账号的初始密码相同，由仓库负责人在团队内线下同步，不写入仓库；
--   password_hash 由 security.PasswordHasher 生成
--   （PBKDF2WithHmacSHA256 / 210000 次 / 16B 随机盐 / 256 位，base64url）。
-- 执行：mysql -h127.0.0.1 -uroot -p enterprise_cms < 003_seed_user_baseline.sql
-- =====================================================================

USE enterprise_cms;

START TRANSACTION;

-- ---------------------------------------------------------------------
-- 1. 部门（缺则补，不改已存在的）
-- ---------------------------------------------------------------------
INSERT INTO sys_department (dept_code, dept_name, parent_id, sort_order)
SELECT v.dept_code, v.dept_name, NULL, v.sort_order
FROM (VALUES
    ROW('HQ', '总部', 0)
) AS v(dept_code, dept_name, sort_order)
WHERE NOT EXISTS (SELECT 1 FROM sys_department d WHERE d.dept_code = v.dept_code);

INSERT INTO sys_department (dept_code, dept_name, parent_id, sort_order)
SELECT v.dept_code, v.dept_name, parent.id, v.sort_order
FROM (VALUES
    ROW('TECH', '技术部', 'HQ', 10)
) AS v(dept_code, dept_name, parent_code, sort_order)
JOIN sys_department parent ON parent.dept_code = v.parent_code
WHERE NOT EXISTS (SELECT 1 FROM sys_department d WHERE d.dept_code = v.dept_code);

-- ---------------------------------------------------------------------
-- 2. 员工账号（缺则补，不改已存在的；默认启用 status=1）
--    department_id 通过 dept_code 关联，部门缺失时该账号 department_id=NULL，
--    不阻断账号创建。
-- ---------------------------------------------------------------------
INSERT INTO sys_user (username, password_hash, real_name, department_id)
SELECT v.username, v.password_hash, v.real_name, d.id
FROM (VALUES
    ROW('admin',  'pbkdf2-sha256$210000$g4d7cXQUljtH34Wvyt483A$_an56CsO4bIHt5YWTbWaBbuWbkr7hAaZ2-OLjwOW53M', '系统管理员', 'HQ'),
    ROW('editor', 'pbkdf2-sha256$210000$xDwhtX27UuRvk_HywMthtQ$Z9ysuktyu9csHMO_Zkvc_Pjs9k0iQ8FiShc5RuI5ovw', '内容编辑',   'TECH'),
    ROW('staff',  'pbkdf2-sha256$210000$pFaQvfUUyX7jskvs9g-7GA$EZo63FQsksGbFDFzkVumCPLrdOey8nHJQrcxGilnNfc', '普通员工',   'TECH')
) AS v(username, password_hash, real_name, dept_code)
LEFT JOIN sys_department d ON d.dept_code = v.dept_code
WHERE NOT EXISTS (SELECT 1 FROM sys_user u WHERE u.username = v.username);

-- ---------------------------------------------------------------------
-- 3. 账号角色关联（仅当 B 的 002 已建立角色时补齐；不删除已有关联）
-- ---------------------------------------------------------------------
INSERT INTO sys_user_role (user_id, role_id)
SELECT u.id, r.id
FROM (VALUES
    ROW('admin',  'ADMIN'),
    ROW('editor', 'EDITOR'),
    ROW('staff',  'EMPLOYEE')
) AS v(username, role_code)
JOIN sys_user u ON u.username = v.username
JOIN sys_role r ON r.role_code = v.role_code
WHERE NOT EXISTS (SELECT 1
                  FROM sys_user_role ur
                  WHERE ur.user_id = u.id AND ur.role_id = r.id);

COMMIT;

-- ---------------------------------------------------------------------
-- 4. 执行后自检（手工运行确认基线完整）
--    预期：departments=2，users=3；已执行 002 时 user_roles=3
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
