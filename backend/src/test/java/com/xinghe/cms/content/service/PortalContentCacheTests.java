package com.xinghe.cms.content.service;

import com.xinghe.cms.common.enums.ArticleStatus;
import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.response.ResultCode;
import com.xinghe.cms.content.mapper.PortalCacheMapper;
import com.xinghe.cms.content.mapper.PortalCacheMapper.ArticleStamp;
import com.xinghe.cms.content.vo.ArticleDetailVO;
import com.xinghe.cms.content.vo.ArticleSummaryVO;
import com.xinghe.cms.content.vo.CategoryVO;
import java.sql.SQLSyntaxErrorException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PortalContentCacheTests {

    private final PortalCacheMapper mapper = mock(PortalCacheMapper.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final Map<String, String> stored = new HashMap<>();
    private final PortalContentCache cache = new PortalContentCache(mapper, redis, JsonMapper.builder().build(), true);

    @BeforeEach
    void fakeRedis() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(call -> stored.get(call.getArgument(0)));
        doAnswer(call -> {
            stored.put(call.getArgument(0), call.getArgument(1));
            return null;
        }).when(values).set(anyString(), anyString(), any(Duration.class));
    }

    @AfterEach
    void clearTransaction() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void samePublicArticleUsesRedisButOfflineCannotReadCachedBody() {
        var article = article("正文");
        when(mapper.articleStamp(7)).thenReturn(new ArticleStamp("detail-version", 3, "作者"));
        assertThat(cache.article(7, () -> article)).isEqualTo(article);
        assertThat(cache.article(7, () -> { throw new AssertionError("缓存命中不应加载详情"); })).isEqualTo(article);
        assertThat(stored).hasSize(1);
        verify(values).set(anyString(), anyString(), eq(Duration.ofMinutes(5)));

        when(mapper.articleStamp(7)).thenReturn(null);
        assertThatThrownBy(() -> cache.article(7, () -> { throw new AssertionError("不应再读取正文"); }))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.getResultCode()).isEqualTo(ResultCode.NOT_FOUND));
    }

    @Test
    void taxonomyGenerationChangesTheKeyAndRedisFailureFallsBackToMysql() {
        when(mapper.treeRevision()).thenReturn("tree-1", "tree-1", "tree-2", "tree-2");
        var time = LocalDateTime.of(2026, 11, 27, 9, 0);
        var child = new CategoryVO("2", "1", "子分类", 0, 1, List.of(), time, time);
        var root = new CategoryVO("1", null, "根分类", 0, 1, List.of(child), time, time);
        Supplier<List<CategoryVO>> miss = () -> { throw new AssertionError("缓存命中不应加载分类"); };
        assertThat(cache.categoryTree(() -> List.of(root))).containsExactly(root);
        assertThat(cache.categoryTree(miss)).containsExactly(root);
        assertThat(cache.categoryTree(List::of)).isEmpty();
        assertThat(stored).hasSize(2);
        when(values.get(anyString())).thenThrow(new DataAccessResourceFailureException("Redis unavailable"));
        clearInvocations(values);
        assertThat(cache.categoryTree(() -> List.of(root))).containsExactly(root);
        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void missingRevisionRowStillAllowsMysqlReadsWithoutTouchingRedis() {
        var article = article("数据库中的正文");
        when(mapper.articleStamp(7)).thenReturn(new ArticleStamp(null, 3, "作者"));
        assertThat(cache.article(7, () -> article)).isEqualTo(article);
        assertThat(cache.categoryTree(List::of)).isEmpty();
        verifyNoInteractions(redis);
    }

    @Test
    void cacheEnabledStartupRequiresTheRevisionRow() {
        assertThatThrownBy(cache::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("005_portal_cache_revision.sql");
        when(mapper.treeRevision()).thenReturn("installed-revision");
        cache.afterPropertiesSet();
    }

    @Test
    void skipsCachingArticlesAboveTheUtf8ByteLimit() {
        var article = article("文".repeat(90_000));
        when(mapper.articleStamp(7)).thenReturn(new ArticleStamp("detail-version", 3, "作者"));
        assertThat(cache.article(7, () -> article)).isEqualTo(article);
        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void disabledReaderStillAdvancesTheSharedVersionOnCommit() {
        var disabled = new PortalContentCache(mapper, redis, JsonMapper.builder().build(), false);
        TransactionSynchronizationManager.initSynchronization();
        when(mapper.advanceRevisions(isNull(), anyString())).thenReturn(1);
        disabled.invalidateCategoryTree();
        assertThat(TransactionSynchronizationManager.getSynchronizations()).singleElement()
                .satisfies(update -> update.beforeCommit(false));
        verify(mapper).advanceRevisions(isNull(), anyString());
    }

    @Test
    void disabledReaderWithoutMigrationDoesNotBlockWrites() {
        var disabled = new PortalContentCache(mapper, redis, JsonMapper.builder().build(), false);
        TransactionSynchronizationManager.initSynchronization();
        when(mapper.advanceRevisions(isNull(), anyString())).thenThrow(new BadSqlGrammarException(
                "advanceRevisions", "UPDATE",
                new SQLSyntaxErrorException("Table does not exist", "42S02", 1146)));
        disabled.invalidateCategoryTree();
        assertThat(TransactionSynchronizationManager.getSynchronizations()).singleElement()
                .satisfies(update -> update.beforeCommit(false));
        verify(mapper).advanceRevisions(isNull(), anyString());
    }

    @Test
    void batchUpdatesRevisionOnceDuringCommitAndRollbackNeverAdvancesIt() {
        TransactionSynchronizationManager.initSynchronization();
        when(mapper.advanceRevisions(anyString(), anyString())).thenReturn(1);
        cache.invalidateCategoryTree();
        cache.invalidateCategoryTree();
        cache.invalidateArticleDetails();
        verifyNoInteractions(mapper);
        var synchronization = TransactionSynchronizationManager.getSynchronizations();
        assertThat(synchronization).hasSize(1);
        synchronization.getFirst().beforeCommit(false);
        verify(mapper).advanceRevisions(anyString(), anyString());
        TransactionSynchronizationManager.clearSynchronization();
        clearInvocations(mapper);

        TransactionSynchronizationManager.initSynchronization();
        cache.invalidateCategoryTree();
        TransactionSynchronizationManager.getSynchronizations().getFirst()
                .afterCompletion(org.springframework.transaction.support.TransactionSynchronization.STATUS_ROLLED_BACK);
        verifyNoInteractions(mapper);
    }

    private static ArticleDetailVO article(String content) {
        var time = LocalDateTime.of(2026, 11, 27, 9, 0);
        return new ArticleDetailVO(new ArticleSummaryVO("7", "标题", null, "8", "分类",
                "9", "作者", ArticleStatus.PUBLISHED, "ALL", 3, List.of(), time, time, time), content);
    }
}
