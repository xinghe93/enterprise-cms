-- =====================================================================
-- EnterpriseCMS 002：权限基线种子数据（B 模块维护）
-- 来源：docs/api/enterprise-cms.openapi.json 的 x-permission-catalog
--       （53 个 API 权限码 + defaultRoles 角色基线）
-- 角色：4 个（EMPLOYEE / EDITOR / REVIEWER / ADMIN）
-- 特性：
--   1) 可重复执行：全部按业务键（role_code / permission_code）定位，
--      只补充缺失行，第二遍执行 0 行变化；
--   2) 只增不改：已存在的角色名/权限名/路径等配置不做 UPDATE，不覆盖
--      团队手工调整过的数据；
--   3) 无 DROP / DELETE / TRUNCATE，对已有数据安全；
--   4) 角色权限关联 = 目录中各权限 defaultRoles 的并集，共 95 条。
-- 执行：mysql -h127.0.0.1 -uroot -p enterprise_cms < 002_seed_permission_baseline.sql
-- =====================================================================

USE enterprise_cms;

START TRANSACTION;

-- ---------------------------------------------------------------------
-- 1. 角色（缺则补，不改已存在的）
-- ---------------------------------------------------------------------
INSERT INTO sys_role (role_code, role_name)
SELECT v.role_code, v.role_name
FROM (VALUES

    ROW('EMPLOYEE', '普通员工'),
    ROW('EDITOR', '编辑'),
    ROW('REVIEWER', '审核员'),
    ROW('ADMIN', '系统管理员')
) AS v(role_code, role_name)
WHERE NOT EXISTS (SELECT 1 FROM sys_role r WHERE r.role_code = v.role_code);

-- ---------------------------------------------------------------------
-- 2. 权限项（缺则补，不改已存在的；type 统一 API，path/method 取自 OpenAPI）
-- ---------------------------------------------------------------------
INSERT INTO sys_permission (permission_code, permission_name, permission_type, path, method)
SELECT v.permission_code, v.permission_name, v.permission_type, v.path, v.method
FROM (VALUES

    ROW('user:list', '员工分页', 'API', '/api/admin/users', 'GET'),
    ROW('user:create', '创建员工', 'API', '/api/admin/users', 'POST'),
    ROW('user:read', '员工详情', 'API', '/api/admin/users/{id}', 'GET'),
    ROW('user:update', '修改员工', 'API', '/api/admin/users/{id}', 'PUT'),
    ROW('user:status', '启停员工', 'API', '/api/admin/users/{id}/status', 'PUT'),
    ROW('user:assign-role', '替换员工角色', 'API', '/api/admin/users/{id}/roles', 'PUT'),
    ROW('department:tree', '部门树', 'API', '/api/admin/departments', 'GET'),
    ROW('department:create', '创建部门', 'API', '/api/admin/departments', 'POST'),
    ROW('department:update', '修改部门', 'API', '/api/admin/departments/{id}', 'PUT'),
    ROW('department:status', '启停部门', 'API', '/api/admin/departments/{id}/status', 'PUT'),
    ROW('department:delete', '删除空部门', 'API', '/api/admin/departments/{id}', 'DELETE'),
    ROW('role:list', '角色分页', 'API', '/api/admin/roles', 'GET'),
    ROW('role:create', '创建角色', 'API', '/api/admin/roles', 'POST'),
    ROW('role:read', '角色详情', 'API', '/api/admin/roles/{id}', 'GET'),
    ROW('role:update', '修改角色', 'API', '/api/admin/roles/{id}', 'PUT'),
    ROW('role:status', '启停角色', 'API', '/api/admin/roles/{id}/status', 'PUT'),
    ROW('role:read-permission', '角色已分配权限', 'API', '/api/admin/roles/{id}/permissions', 'GET'),
    ROW('role:assign-permission', '替换角色权限', 'API', '/api/admin/roles/{id}/permissions', 'PUT'),
    ROW('permission:list', '权限项分页', 'API', '/api/admin/permissions', 'GET'),
    ROW('permission:create', '创建权限项', 'API', '/api/admin/permissions', 'POST'),
    ROW('permission:update', '修改权限项', 'API', '/api/admin/permissions/{id}', 'PUT'),
    ROW('permission:status', '启停权限项', 'API', '/api/admin/permissions/{id}/status', 'PUT'),
    ROW('article:list', '后台文章分页', 'API', '/api/admin/articles', 'GET'),
    ROW('article:create', '创建文章草稿', 'API', '/api/admin/articles', 'POST'),
    ROW('article:read', '后台文章详情', 'API', '/api/admin/articles/{id}', 'GET'),
    ROW('article:update', '保存文章草稿', 'API', '/api/admin/articles/{id}', 'PUT'),
    ROW('article:delete', '逻辑删除文章', 'API', '/api/admin/articles/{id}', 'DELETE'),
    ROW('article:submit', '提交审核', 'API', '/api/admin/articles/{id}/submit', 'POST'),
    ROW('category:tree', '后台分类树', 'API', '/api/admin/categories', 'GET'),
    ROW('category:create', '创建分类', 'API', '/api/admin/categories', 'POST'),
    ROW('category:update', '修改分类', 'API', '/api/admin/categories/{id}', 'PUT'),
    ROW('category:status', '启停分类', 'API', '/api/admin/categories/{id}/status', 'PUT'),
    ROW('category:delete', '删除空分类', 'API', '/api/admin/categories/{id}', 'DELETE'),
    ROW('tag:list', '标签分页', 'API', '/api/admin/tags', 'GET'),
    ROW('tag:create', '创建标签', 'API', '/api/admin/tags', 'POST'),
    ROW('tag:update', '修改标签', 'API', '/api/admin/tags/{id}', 'PUT'),
    ROW('tag:status', '启停标签', 'API', '/api/admin/tags/{id}/status', 'PUT'),
    ROW('tag:delete', '删除未绑定标签', 'API', '/api/admin/tags/{id}', 'DELETE'),
    ROW('article:review-list', '待审核文章分页', 'API', '/api/admin/reviews/pending', 'GET'),
    ROW('article:approve', '审核通过', 'API', '/api/admin/articles/{id}/approve', 'POST'),
    ROW('article:reject', '驳回文章', 'API', '/api/admin/articles/{id}/reject', 'POST'),
    ROW('article:publish', '发布文章', 'API', '/api/admin/articles/{id}/publish', 'POST'),
    ROW('article:offline', '下架文章', 'API', '/api/admin/articles/{id}/offline', 'POST'),
    ROW('article:publish-batch', '批量发布文章', 'API', '/api/admin/articles/batch/publish', 'POST'),
    ROW('article:offline-batch', '批量下架文章', 'API', '/api/admin/articles/batch/offline', 'POST'),
    ROW('article:bind-tag', '替换文章标签', 'API', '/api/admin/articles/{id}/tags', 'PUT'),
    ROW('review:list', '文章审核历史分页', 'API', '/api/admin/articles/{id}/reviews', 'GET'),
    ROW('log:list', '操作日志分页', 'API', '/api/admin/logs', 'GET'),
    ROW('log:read', '操作日志详情', 'API', '/api/admin/logs/{id}', 'GET'),
    ROW('portal:article:list', '门户文章分页', 'API', '/api/portal/articles', 'GET'),
    ROW('portal:article:read', '门户文章详情', 'API', '/api/portal/articles/{id}', 'GET'),
    ROW('portal:category:tree', '门户分类筛选树', 'API', '/api/portal/categories', 'GET'),
    ROW('portal:tag:list', '门户标签筛选分页', 'API', '/api/portal/tags', 'GET')
) AS v(permission_code, permission_name, permission_type, path, method)
WHERE NOT EXISTS (SELECT 1 FROM sys_permission p WHERE p.permission_code = v.permission_code);

-- ---------------------------------------------------------------------
-- 3. 角色权限关联（按目录 defaultRoles 补齐缺失关联；不删除已有关联）
-- ---------------------------------------------------------------------
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM (VALUES

    ROW('ADMIN', 'user:list'),
    ROW('ADMIN', 'user:create'),
    ROW('ADMIN', 'user:read'),
    ROW('ADMIN', 'user:update'),
    ROW('ADMIN', 'user:status'),
    ROW('ADMIN', 'user:assign-role'),
    ROW('ADMIN', 'department:tree'),
    ROW('ADMIN', 'department:create'),
    ROW('ADMIN', 'department:update'),
    ROW('ADMIN', 'department:status'),
    ROW('ADMIN', 'department:delete'),
    ROW('ADMIN', 'role:list'),
    ROW('ADMIN', 'role:create'),
    ROW('ADMIN', 'role:read'),
    ROW('ADMIN', 'role:update'),
    ROW('ADMIN', 'role:status'),
    ROW('ADMIN', 'role:read-permission'),
    ROW('ADMIN', 'role:assign-permission'),
    ROW('ADMIN', 'permission:list'),
    ROW('ADMIN', 'permission:create'),
    ROW('ADMIN', 'permission:update'),
    ROW('ADMIN', 'permission:status'),
    ROW('EDITOR', 'article:list'),
    ROW('REVIEWER', 'article:list'),
    ROW('ADMIN', 'article:list'),
    ROW('EDITOR', 'article:create'),
    ROW('ADMIN', 'article:create'),
    ROW('EDITOR', 'article:read'),
    ROW('REVIEWER', 'article:read'),
    ROW('ADMIN', 'article:read'),
    ROW('EDITOR', 'article:update'),
    ROW('ADMIN', 'article:update'),
    ROW('EDITOR', 'article:delete'),
    ROW('ADMIN', 'article:delete'),
    ROW('EDITOR', 'article:submit'),
    ROW('ADMIN', 'article:submit'),
    ROW('EDITOR', 'category:tree'),
    ROW('REVIEWER', 'category:tree'),
    ROW('ADMIN', 'category:tree'),
    ROW('EDITOR', 'category:create'),
    ROW('ADMIN', 'category:create'),
    ROW('EDITOR', 'category:update'),
    ROW('ADMIN', 'category:update'),
    ROW('EDITOR', 'category:status'),
    ROW('ADMIN', 'category:status'),
    ROW('EDITOR', 'category:delete'),
    ROW('ADMIN', 'category:delete'),
    ROW('EDITOR', 'tag:list'),
    ROW('REVIEWER', 'tag:list'),
    ROW('ADMIN', 'tag:list'),
    ROW('EDITOR', 'tag:create'),
    ROW('ADMIN', 'tag:create'),
    ROW('EDITOR', 'tag:update'),
    ROW('ADMIN', 'tag:update'),
    ROW('EDITOR', 'tag:status'),
    ROW('ADMIN', 'tag:status'),
    ROW('EDITOR', 'tag:delete'),
    ROW('ADMIN', 'tag:delete'),
    ROW('REVIEWER', 'article:review-list'),
    ROW('ADMIN', 'article:review-list'),
    ROW('REVIEWER', 'article:approve'),
    ROW('ADMIN', 'article:approve'),
    ROW('REVIEWER', 'article:reject'),
    ROW('ADMIN', 'article:reject'),
    ROW('REVIEWER', 'article:publish'),
    ROW('ADMIN', 'article:publish'),
    ROW('REVIEWER', 'article:offline'),
    ROW('ADMIN', 'article:offline'),
    ROW('REVIEWER', 'article:publish-batch'),
    ROW('ADMIN', 'article:publish-batch'),
    ROW('REVIEWER', 'article:offline-batch'),
    ROW('ADMIN', 'article:offline-batch'),
    ROW('EDITOR', 'article:bind-tag'),
    ROW('ADMIN', 'article:bind-tag'),
    ROW('EDITOR', 'review:list'),
    ROW('REVIEWER', 'review:list'),
    ROW('ADMIN', 'review:list'),
    ROW('ADMIN', 'log:list'),
    ROW('ADMIN', 'log:read'),
    ROW('EMPLOYEE', 'portal:article:list'),
    ROW('EDITOR', 'portal:article:list'),
    ROW('REVIEWER', 'portal:article:list'),
    ROW('ADMIN', 'portal:article:list'),
    ROW('EMPLOYEE', 'portal:article:read'),
    ROW('EDITOR', 'portal:article:read'),
    ROW('REVIEWER', 'portal:article:read'),
    ROW('ADMIN', 'portal:article:read'),
    ROW('EMPLOYEE', 'portal:category:tree'),
    ROW('EDITOR', 'portal:category:tree'),
    ROW('REVIEWER', 'portal:category:tree'),
    ROW('ADMIN', 'portal:category:tree'),
    ROW('EMPLOYEE', 'portal:tag:list'),
    ROW('EDITOR', 'portal:tag:list'),
    ROW('REVIEWER', 'portal:tag:list'),
    ROW('ADMIN', 'portal:tag:list')
) AS v(role_code, permission_code)
JOIN sys_role r        ON r.role_code        = v.role_code
JOIN sys_permission p  ON p.permission_code  = v.permission_code
WHERE NOT EXISTS (SELECT 1
                  FROM sys_role_permission rp
                  WHERE rp.role_id = r.id AND rp.permission_id = p.id);

COMMIT;

-- ---------------------------------------------------------------------
-- 4. 执行后自检（手工运行确认基线完整）
--    预期：roles=4，permissions=53，role_permission=95
-- ---------------------------------------------------------------------
-- SELECT (SELECT COUNT(*) FROM sys_role)            AS roles,
--        (SELECT COUNT(*) FROM sys_permission)      AS permissions,
--        (SELECT COUNT(*) FROM sys_role_permission) AS role_permissions;
