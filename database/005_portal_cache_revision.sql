-- 门户缓存版本。先执行本脚本，再开启 CMS_CONTENT_CACHE_ENABLED。
-- 重跑会更换版本，使重启前或离线导入前的 Redis 键失效。
USE enterprise_cms;

CREATE TABLE IF NOT EXISTS cms_portal_cache_revision (
    id TINYINT UNSIGNED NOT NULL PRIMARY KEY,
    article_revision CHAR(36) CHARACTER SET ascii NOT NULL,
    tree_revision CHAR(36) CHARACTER SET ascii NOT NULL,
    CONSTRAINT chk_portal_cache_singleton CHECK (id = 1)
) ENGINE=InnoDB COMMENT='门户文章和分类缓存版本';

INSERT INTO cms_portal_cache_revision (id, article_revision, tree_revision)
VALUES (1, UUID(), UUID())
ON DUPLICATE KEY UPDATE article_revision = UUID(), tree_revision = UUID();
