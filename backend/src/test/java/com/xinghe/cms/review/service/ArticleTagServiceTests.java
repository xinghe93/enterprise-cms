package com.xinghe.cms.review.service;

import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.LongStream;

import com.xinghe.cms.common.contract.TagView;
import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.response.ResultCode;
import com.xinghe.cms.review.mapper.ArticleTagMapper;
import com.xinghe.cms.review.mapper.ArticleTagRow;
import com.xinghe.cms.review.mapper.TagRow;
import com.xinghe.cms.review.service.impl.ArticleTagServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * 文章标签关联服务的规则测试：不启动 Spring、不连接 MySQL。
 * 用 JDK 动态代理代替 Mapper（本机 JDK 25 下 Mockito 无法自附加）。
 */
class ArticleTagServiceTests {

    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 11, 1, 10, 0, 0);
    private static final LocalDateTime UPDATED_AT = LocalDateTime.of(2026, 11, 2, 11, 0, 0);

    private final List<Long> existingBindings = new ArrayList<>();
    private final Map<Long, TagRow> tagDictionary = new LinkedHashMap<>();
    private final List<ArticleTagRow> articleTagRows = new ArrayList<>();

    private final AtomicReference<List<Long>> lockedIds = new AtomicReference<>();
    private final AtomicReference<List<Long>> deletedIds = new AtomicReference<>();
    private final AtomicReference<List<Long>> insertedIds = new AtomicReference<>();
    private final AtomicInteger lockCalls = new AtomicInteger();
    private final AtomicInteger deleteCalls = new AtomicInteger();
    private final AtomicInteger insertCalls = new AtomicInteger();
    private final AtomicInteger tagRowsCalls = new AtomicInteger();

    private ArticleTagServiceImpl service;

    @BeforeEach
    void setUp() {
        ArticleTagMapper mapper = (ArticleTagMapper) Proxy.newProxyInstance(
                ArticleTagMapper.class.getClassLoader(),
                new Class<?>[]{ArticleTagMapper.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "lockTagsByIds" -> {
                        lockCalls.incrementAndGet();
                        List<Long> ids = requireLongs(args[0], "lockTagsByIds");
                        lockedIds.set(ids);
                        yield ids.stream().map(tagDictionary::get).filter(Objects::nonNull).toList();
                    }
                    case "selectBoundTagIds" -> new ArrayList<>(existingBindings);
                    case "deleteBindingsByIds" -> {
                        deleteCalls.incrementAndGet();
                        deletedIds.set(copyOf(args[1]));
                        yield 0;
                    }
                    case "insertBindings" -> {
                        insertCalls.incrementAndGet();
                        insertedIds.set(requireLongs(args[1], "insertBindings"));
                        yield ((List<?>) args[1]).size();
                    }
                    case "selectTagRowsByArticleIds" -> {
                        tagRowsCalls.incrementAndGet();
                        yield new ArrayList<>(articleTagRows);
                    }
                    case "toString" -> "ArticleTagMapperProxy";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
        service = new ArticleTagServiceImpl(mapper);
    }

    @Test
    void replaceRejectsNullTagList() {
        assertBadRequest(() -> service.replaceBindings(1L, null), "标签 ID 列表不能为空");
        assertThat(deleteCalls).hasValue(0);
    }

    @Test
    void replaceRejectsInvalidArticleId() {
        assertBadRequest(() -> service.replaceBindings(null, List.of(1L)), "文章 ID 必须为正整数");
        assertBadRequest(() -> service.replaceBindings(0L, List.of(1L)), "文章 ID 必须为正整数");
        assertBadRequest(() -> service.replaceBindings(-3L, List.of(1L)), "文章 ID 必须为正整数");
    }

    @Test
    void replaceRejectsNullAndNonPositiveTagIds() {
        assertBadRequest(() -> service.replaceBindings(1L, java.util.Arrays.asList(1L, null)), "标签 ID 必须为正整数");
        assertBadRequest(() -> service.replaceBindings(1L, List.of(0L)), "标签 ID 必须为正整数");
        assertBadRequest(() -> service.replaceBindings(1L, List.of(-1L)), "标签 ID 必须为正整数");
    }

    @Test
    void replaceRejectsDuplicateTagIds() {
        assertBadRequest(() -> service.replaceBindings(1L, List.of(5L, 5L)), "标签 ID 不能重复");
    }

    @Test
    void replaceRejectsMoreThanOneHundredTags() {
        List<Long> tooMany = LongStream.rangeClosed(1, 101).boxed().toList();

        assertBadRequest(() -> service.replaceBindings(1L, tooMany), "标签数量不能超过 100");
    }

    @Test
    void replaceAcceptsExactlyOneHundredTags() {
        List<Long> hundred = LongStream.rangeClosed(1, 100).boxed().toList();
        hundred.forEach(this::enableTag);

        service.replaceBindings(1L, hundred);

        assertThat(insertCalls).hasValue(1);
        assertThat(insertedIds.get()).hasSize(100).isSorted();
        assertThat(deleteCalls).hasValue(0);
    }

    @Test
    void replaceWithEmptyListClearsBindingsAndSkipsLockAndInsert() {
        existingBindings.addAll(List.of(2L, 3L));

        service.replaceBindings(1L, List.of());

        assertThat(deleteCalls).hasValue(1);
        assertThat(deletedIds.get()).containsExactly(2L, 3L);
        assertThat(lockCalls).hasValue(0);
        assertThat(insertCalls).hasValue(0);
    }

    @Test
    void replaceComputesInsertDiffAndKeepsExistingBindings() {
        existingBindings.addAll(List.of(2L, 3L));
        enableTag(3L);
        enableTag(4L);

        service.replaceBindings(1L, List.of(3L, 4L));

        assertThat(lockedIds.get()).containsExactly(4L);
        assertThat(insertedIds.get()).containsExactly(4L);
        assertThat(deletedIds.get()).containsExactly(2L);
    }

    @Test
    void replaceLocksNewTagsInAscendingIdOrder() {
        enableTag(3L);
        enableTag(5L);
        enableTag(9L);

        service.replaceBindings(1L, List.of(9L, 3L, 5L));

        assertThat(lockedIds.get()).containsExactly(3L, 5L, 9L);
        assertThat(insertedIds.get()).containsExactly(3L, 5L, 9L);
    }

    @Test
    void replaceThrowsNotFoundWhenNewTagDoesNotExist() {
        enableTag(4L);

        assertThatThrownBy(() -> service.replaceBindings(1L, List.of(4L, 8L)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("标签不存在")
                .satisfies(exception -> assertThat(((BusinessException) exception).getResultCode())
                        .isEqualTo(ResultCode.NOT_FOUND));

        assertThat(deleteCalls).hasValue(0);
        assertThat(insertCalls).hasValue(0);
    }

    @Test
    void replaceRejectsDisabledTagForNewBinding() {
        TagRow disabled = new TagRow(4L, "停用标签", 0, CREATED_AT, UPDATED_AT);
        tagDictionary.put(4L, disabled);

        assertThatThrownBy(() -> service.replaceBindings(1L, List.of(4L)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("标签已停用")
                .satisfies(exception -> assertThat(((BusinessException) exception).getResultCode())
                        .isEqualTo(ResultCode.CONFLICT));

        assertThat(deleteCalls).hasValue(0);
        assertThat(insertCalls).hasValue(0);
    }

    @Test
    void replaceKeepsAlreadyBoundDisabledTag() {
        existingBindings.addAll(List.of(2L, 3L));
        tagDictionary.put(2L, new TagRow(2L, "旧停用标签", 0, CREATED_AT, UPDATED_AT));

        service.replaceBindings(1L, List.of(2L, 3L));

        assertThat(lockCalls).hasValue(0);
        assertThat(insertCalls).hasValue(0);
        assertThat(deleteCalls).hasValue(0);
    }

    @Test
    void findByArticleIdsRejectsMoreThanFiveHundredArticles() {
        List<Long> tooMany = LongStream.rangeClosed(1, 501).boxed().toList();

        assertBadRequest(() -> service.findByArticleIds(tooMany), "文章数量不能超过 500");
        assertThat(tagRowsCalls).hasValue(0);
    }

    @Test
    void findByArticleIdsReturnsEmptyMapWithoutQueryForNullAndEmptyInput() {
        assertThat(service.findByArticleIds(null)).isEmpty();
        assertThat(service.findByArticleIds(List.of())).isEmpty();
        assertThat(tagRowsCalls).hasValue(0);
    }

    @Test
    void findByArticleIdsKeepsEveryRequestedKeyAndSortsTagsById() {
        articleTagRows.add(tagRow(2L, 7L, "后端"));
        articleTagRows.add(tagRow(2L, 5L, "前端"));
        articleTagRows.add(tagRow(1L, 9L, "课程"));

        Map<Long, List<TagView>> result = service.findByArticleIds(List.of(1L, 2L, 3L));

        assertThat(result.keySet()).containsExactly(1L, 2L, 3L);
        assertThat(result.get(1L)).extracting(TagView::id).containsExactly(9L);
        assertThat(result.get(2L)).extracting(TagView::id).containsExactly(5L, 7L);
        assertThat(result.get(3L)).isEmpty();
        assertThat(tagRowsCalls).hasValue(1);
    }

    @Test
    void findByArticleIdsMapsTagViewFields() {
        articleTagRows.add(tagRow(4L, 6L, "SSM"));

        List<TagView> views = service.findByArticleIds(List.of(4L)).get(4L);

        assertThat(views)
                .extracting(TagView::id, TagView::tagName, TagView::status, TagView::createdAt, TagView::updatedAt)
                .containsExactly(tuple(6L, "SSM", 1, CREATED_AT, UPDATED_AT));
    }

    @Test
    void findByArticleIdsRejectsNonPositiveArticleId() {
        assertBadRequest(() -> service.findByArticleIds(List.of(0L)), "文章 ID 必须为正整数");
        assertBadRequest(() -> service.findByArticleIds(java.util.Arrays.asList(1L, null)), "文章 ID 必须为正整数");
        assertThat(tagRowsCalls).hasValue(0);
    }

    private void enableTag(Long id) {
        tagDictionary.put(id, new TagRow(id, "标签" + id, 1, CREATED_AT, UPDATED_AT));
    }

    private static ArticleTagRow tagRow(Long articleId, Long tagId, String tagName) {
        return new ArticleTagRow(articleId, tagId, tagName, 1, CREATED_AT, UPDATED_AT);
    }

    private static void assertBadRequest(Runnable action, String message) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .hasMessage(message)
                .satisfies(exception -> assertThat(((BusinessException) exception).getResultCode())
                        .isEqualTo(ResultCode.BAD_REQUEST));
    }

    private static List<Long> requireLongs(Object argument, String method) {
        if (argument instanceof List<?> values) {
            List<Long> ids = new ArrayList<>();
            for (Object value : values) {
                if (!(value instanceof Long id)) {
                    throw new IllegalStateException(method + " 的元素应为 Long，实际为 "
                            + (value == null ? "null" : value.getClass().getName()));
                }
                ids.add(id);
            }
            return ids;
        }
        throw new IllegalStateException(method + " 的参数应为 List，实际为 "
                + (argument == null ? "null" : argument.getClass().getName()));
    }

    private static List<Long> copyOf(Object argument) {
        return argument == null ? null : requireLongs(argument, "deleteBindingsByIds");
    }

    private static Object defaultValue(Class<?> returnType) {
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        return null;
    }
}
