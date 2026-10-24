-- EnterpriseCMS initial schema for MySQL 8.4.
-- Import explicitly; Spring Boot does not run this file automatically.
-- Existing tables and data are preserved. Later changes need numbered migrations.
CREATE DATABASE IF NOT EXISTS enterprise_cms
    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE enterprise_cms;

CREATE TABLE IF NOT EXISTS sys_department (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '部门 ID',
    parent_id BIGINT UNSIGNED NULL COMMENT '父部门 ID，根节点为 NULL',
    dept_code VARCHAR(64) NOT NULL COMMENT '部门编码',
    dept_name VARCHAR(100) NOT NULL COMMENT '部门名称',
    sort_order INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '排序值',
    status TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '0 停用，1 启用',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_department_code (dept_code),
    KEY idx_department_parent (parent_id),
    CONSTRAINT fk_department_parent FOREIGN KEY (parent_id) REFERENCES sys_department (id),
    CONSTRAINT chk_department_status CHECK (status IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='部门';

CREATE TABLE IF NOT EXISTS sys_user (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '员工 ID',
    username VARCHAR(64) NOT NULL COMMENT '登录账号',
    password_hash VARCHAR(255) NOT NULL COMMENT '密码哈希',
    real_name VARCHAR(100) NOT NULL COMMENT '员工姓名',
    department_id BIGINT UNSIGNED NULL COMMENT '所属部门 ID',
    status TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '0 停用，1 启用',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_username (username),
    KEY idx_user_department (department_id),
    CONSTRAINT fk_user_department FOREIGN KEY (department_id) REFERENCES sys_department (id),
    CONSTRAINT chk_user_status CHECK (status IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='员工账号';

CREATE TABLE IF NOT EXISTS sys_role (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '角色 ID',
    role_code VARCHAR(64) NOT NULL COMMENT '角色编码',
    role_name VARCHAR(100) NOT NULL COMMENT '角色名称',
    status TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '0 停用，1 启用',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_code (role_code),
    CONSTRAINT chk_role_status CHECK (status IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色';

CREATE TABLE IF NOT EXISTS sys_permission (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '权限 ID',
    permission_code VARCHAR(100) NOT NULL COMMENT '权限编码',
    permission_name VARCHAR(100) NOT NULL COMMENT '权限名称',
    permission_type VARCHAR(16) NOT NULL COMMENT 'MENU 菜单，BUTTON 按钮，API 接口',
    path VARCHAR(255) NULL COMMENT '菜单路由或接口路径',
    method VARCHAR(16) NULL COMMENT '接口 HTTP 方法',
    status TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '0 停用，1 启用',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_permission_code (permission_code),
    CONSTRAINT chk_permission_type CHECK (permission_type IN ('MENU', 'BUTTON', 'API')),
    CONSTRAINT chk_permission_status CHECK (status IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='权限';

CREATE TABLE IF NOT EXISTS sys_user_role (
    user_id BIGINT UNSIGNED NOT NULL,
    role_id BIGINT UNSIGNED NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (user_id, role_id),
    KEY idx_user_role_role (role_id),
    CONSTRAINT fk_user_role_user FOREIGN KEY (user_id) REFERENCES sys_user (id),
    CONSTRAINT fk_user_role_role FOREIGN KEY (role_id) REFERENCES sys_role (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='员工角色关联';

CREATE TABLE IF NOT EXISTS sys_role_permission (
    role_id BIGINT UNSIGNED NOT NULL,
    permission_id BIGINT UNSIGNED NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (role_id, permission_id),
    KEY idx_role_permission_permission (permission_id),
    CONSTRAINT fk_role_permission_role FOREIGN KEY (role_id) REFERENCES sys_role (id),
    CONSTRAINT fk_role_permission_permission FOREIGN KEY (permission_id) REFERENCES sys_permission (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色权限关联';

CREATE TABLE IF NOT EXISTS cms_category (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '分类 ID',
    parent_id BIGINT UNSIGNED NULL COMMENT '父分类 ID，根节点为 NULL',
    category_name VARCHAR(100) NOT NULL COMMENT '分类名称',
    sort_order INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '排序值',
    status TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '0 停用，1 启用',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_category_parent (parent_id),
    CONSTRAINT fk_category_parent FOREIGN KEY (parent_id) REFERENCES cms_category (id),
    CONSTRAINT chk_category_status CHECK (status IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='文章分类';

CREATE TABLE IF NOT EXISTS cms_tag (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '标签 ID',
    tag_name VARCHAR(64) NOT NULL COMMENT '标签名称',
    status TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '0 停用，1 启用',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_tag_name (tag_name),
    CONSTRAINT chk_tag_status CHECK (status IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='文章标签';

CREATE TABLE IF NOT EXISTS cms_article (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '文章 ID',
    title VARCHAR(255) NOT NULL COMMENT '文章标题',
    summary VARCHAR(1000) NULL COMMENT '文章摘要',
    content LONGTEXT NOT NULL COMMENT '文章正文',
    category_id BIGINT UNSIGNED NOT NULL COMMENT '分类 ID',
    author_id BIGINT UNSIGNED NOT NULL COMMENT '作者 ID',
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT' COMMENT '文章生命周期状态',
    visibility VARCHAR(16) NOT NULL DEFAULT 'ALL' COMMENT '首期仅支持全体员工可见',
    published_at DATETIME(3) NULL COMMENT '最近一次发布时间',
    version INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    is_deleted TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0 正常，1 已删除',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_article_status_published (is_deleted, status, published_at),
    KEY idx_article_category (category_id),
    KEY idx_article_author (author_id),
    CONSTRAINT fk_article_category FOREIGN KEY (category_id) REFERENCES cms_category (id),
    CONSTRAINT fk_article_author FOREIGN KEY (author_id) REFERENCES sys_user (id),
    CONSTRAINT chk_article_status CHECK (
        status IN ('DRAFT', 'PENDING_REVIEW', 'APPROVED', 'REJECTED', 'PUBLISHED', 'OFFLINE')
    ),
    CONSTRAINT chk_article_visibility CHECK (visibility = 'ALL'),
    CONSTRAINT chk_article_deleted CHECK (is_deleted IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='知识文章';

CREATE TABLE IF NOT EXISTS cms_review (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '审核记录 ID',
    article_id BIGINT UNSIGNED NOT NULL COMMENT '文章 ID',
    reviewer_id BIGINT UNSIGNED NOT NULL COMMENT '审核人 ID',
    result VARCHAR(16) NOT NULL COMMENT 'APPROVED 通过，REJECTED 驳回',
    comment VARCHAR(1000) NULL COMMENT '审核意见',
    reviewed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '审核时间',
    PRIMARY KEY (id),
    KEY idx_review_article_time (article_id, reviewed_at),
    KEY idx_review_reviewer (reviewer_id),
    CONSTRAINT fk_review_article FOREIGN KEY (article_id) REFERENCES cms_article (id),
    CONSTRAINT fk_review_reviewer FOREIGN KEY (reviewer_id) REFERENCES sys_user (id),
    CONSTRAINT chk_review_result CHECK (result IN ('APPROVED', 'REJECTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='文章审核历史';

CREATE TABLE IF NOT EXISTS cms_article_tag (
    article_id BIGINT UNSIGNED NOT NULL,
    tag_id BIGINT UNSIGNED NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (article_id, tag_id),
    KEY idx_article_tag_tag (tag_id),
    CONSTRAINT fk_article_tag_article FOREIGN KEY (article_id) REFERENCES cms_article (id),
    CONSTRAINT fk_article_tag_tag FOREIGN KEY (tag_id) REFERENCES cms_tag (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='文章标签关联';

CREATE TABLE IF NOT EXISTS sys_operation_log (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '日志 ID',
    user_id BIGINT UNSIGNED NULL COMMENT '操作人 ID，未登录时为 NULL',
    operation VARCHAR(100) NOT NULL COMMENT '操作名称',
    request_uri VARCHAR(255) NOT NULL COMMENT '请求路径',
    http_method VARCHAR(16) NOT NULL COMMENT 'HTTP 方法',
    result VARCHAR(16) NOT NULL COMMENT 'SUCCESS 成功，FAILURE 失败',
    duration_ms BIGINT UNSIGNED NOT NULL COMMENT '执行耗时，单位毫秒',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_operation_log_user_time (user_id, created_at),
    KEY idx_operation_log_time (created_at),
    CONSTRAINT fk_operation_log_user FOREIGN KEY (user_id) REFERENCES sys_user (id),
    CONSTRAINT chk_operation_log_result CHECK (result IN ('SUCCESS', 'FAILURE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='操作审计日志';
