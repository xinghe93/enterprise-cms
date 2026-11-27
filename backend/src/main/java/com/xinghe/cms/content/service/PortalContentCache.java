package com.xinghe.cms.content.service;

import com.xinghe.cms.common.enums.ArticleStatus;
import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.response.ResultCode;
import com.xinghe.cms.content.mapper.PortalCacheMapper;
import com.xinghe.cms.content.mapper.PortalCacheMapper.ArticleStamp;
import com.xinghe.cms.content.vo.ArticleDetailVO;
import com.xinghe.cms.content.vo.ArticleSummaryVO;
import com.xinghe.cms.content.vo.CategoryVO;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.json.JsonMapper;

@Service
public class PortalContentCache implements InitializingBean {

    private static final Logger LOG = LoggerFactory.getLogger(PortalContentCache.class);
    private static final String PREFIX = "cms:portal-cache:v1:";
    private static final Duration ARTICLE_TTL = Duration.ofMinutes(5);
    private static final Duration TREE_TTL = Duration.ofMinutes(10);
    private static final int MAX_CACHE_ENTRY_BYTES = 256 * 1024;
    private static final int MYSQL_TABLE_NOT_FOUND = 1146;
    private static final String MISSING_REVISION = "缺少门户缓存版本，请先执行 005_portal_cache_revision.sql";

    private final PortalCacheMapper mapper;
    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final JavaType articleType;
    private final JavaType treeType;
    private final boolean enabled;

    public PortalContentCache(PortalCacheMapper mapper, StringRedisTemplate redis, JsonMapper json,
            @Value("${cms.content-cache.enabled:false}") boolean enabled) {
        this.mapper = mapper;
        this.redis = redis;
        this.json = json;
        this.enabled = enabled;
        articleType = json.constructType(CachedArticle.class);
        treeType = json.getTypeFactory().constructCollectionType(List.class, CategoryVO.class);
    }

    @Override
    public void afterPropertiesSet() {
        if (!enabled) {
            return;
        }
        try {
            if (mapper.treeRevision() == null) {
                throw new IllegalStateException(MISSING_REVISION);
            }
        } catch (DataAccessException ex) {
            throw new IllegalStateException("无法读取 MySQL 门户缓存版本，请检查迁移和数据库连接", ex);
        }
    }

    // Cache JSON keeps the article nested. The HTTP representation remains @JsonUnwrapped.
    record CachedArticle(ArticleSummaryVO article, String content) {
        ArticleDetailVO toDetail() {
            return new ArticleDetailVO(article, content);
        }
    }

    ArticleDetailVO article(long id, Supplier<ArticleDetailVO> load) {
        if (!canReadCache()) {
            return load.get();
        }
        ArticleStamp stamp = mapper.articleStamp(id);
        if (stamp == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "文章不存在");
        }
        if (stamp.revision() == null) {
            return load.get();
        }
        String key = PREFIX + "article:" + id + ":" + stamp.revision() + ":" + stamp.version();
        Predicate<CachedArticle> matches = candidate -> matchesArticle(candidate, id, stamp);
        CachedArticle result = getOrLoad(key, articleType, ARTICLE_TTL, matches, () -> {
            ArticleDetailVO detail = load.get();
            return new CachedArticle(detail.article(), detail.content());
        });
        return result.toDetail();
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<CategoryVO> categoryTree(Supplier<List<CategoryVO>> load) {
        if (!canReadCache()) {
            return load.get();
        }
        String revision = mapper.treeRevision();
        if (revision == null) {
            return load.get();
        }
        return getOrLoad(PREFIX + "category-tree:" + revision, treeType, TREE_TTL, tree -> true, load);
    }

    private <T> T getOrLoad(String key, JavaType type, Duration ttl, Predicate<T> valid, Supplier<T> load) {
        try {
            String cached = redis.opsForValue().get(key);
            if (cached != null) {
                T result = json.readValue(cached, type);
                if (result != null && valid.test(result)) {
                    LOG.debug("门户业务缓存命中");
                    return result;
                }
            }
        } catch (DataAccessException ex) {
            LOG.debug("门户缓存读取失败：{}", ex.getClass().getSimpleName());
            return load.get();
        } catch (JacksonException ex) {
            LOG.debug("门户缓存解码失败：{}", ex.getClass().getSimpleName());
        }

        T result = load.get();
        if (valid.test(result)) {
            try {
                byte[] payload = json.writeValueAsBytes(result);
                if (payload.length <= MAX_CACHE_ENTRY_BYTES) {
                    redis.opsForValue().set(key, new String(payload, StandardCharsets.UTF_8), ttl);
                }
            } catch (DataAccessException | JacksonException ex) {
                LOG.debug("门户缓存写入失败：{}", ex.getClass().getSimpleName());
            }
        }
        return result;
    }

    private static boolean matchesArticle(CachedArticle value, long id, ArticleStamp stamp) {
        ArticleSummaryVO article = value.article();
        return article != null && value.content() != null && Long.toString(id).equals(article.id())
                && article.status() == ArticleStatus.PUBLISHED && "ALL".equals(article.visibility())
                && article.version() == stamp.version() && Objects.equals(article.authorName(), stamp.authorName());
    }

    private boolean canReadCache() {
        return enabled && (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly());
    }

    void invalidateCategoryTree() {
        invalidate(false, true);
    }

    void invalidateArticleDetails() {
        invalidate(true, false);
    }

    void invalidateCategoryMetadata() {
        invalidate(true, true);
    }

    private void invalidate(boolean article, boolean tree) {
        // Acquire the revision row after business writes, once even for a batch of articles.
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            if (synchronization instanceof RevisionUpdate update) {
                update.article |= article;
                update.tree |= tree;
                return;
            }
        }
        TransactionSynchronizationManager.registerSynchronization(new RevisionUpdate(article, tree));
    }

    private final class RevisionUpdate implements TransactionSynchronization {
        private boolean article;
        private boolean tree;

        private RevisionUpdate(boolean article, boolean tree) {
            this.article = article;
            this.tree = tree;
        }

        @Override
        public void beforeCommit(boolean readOnly) {
            int updated;
            try {
                updated = mapper.advanceRevisions(article ? UUID.randomUUID().toString() : null,
                        tree ? UUID.randomUUID().toString() : null);
            } catch (DataAccessException ex) {
                // A node can write before 005 is installed, but only if it cannot serve cache hits.
                if (!enabled && ex.getMostSpecificCause() instanceof SQLException sql
                        && sql.getErrorCode() == MYSQL_TABLE_NOT_FOUND) {
                    return;
                }
                throw ex;
            }
            if (updated != 1) {
                throw new IllegalStateException(MISSING_REVISION);
            }
        }
    }
}
