package com.xinghe.cms.content.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.response.ResultCode;
import com.xinghe.cms.content.entity.Category;
import com.xinghe.cms.content.mapper.CategoryMapper;
import com.xinghe.cms.content.vo.CategoryVO;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class CategoryServiceTests {

    private final CategoryMapper mapper = mock(CategoryMapper.class);
    private final CategoryService service = new CategoryService(mapper);

    @Test
    void adminFilterIncludesDisabledAncestorAndOrdersMatchingSiblings() {
        when(mapper.selectList(null)).thenReturn(List.of(
                category(5, 1L, 0, 0),
                category(3, 1L, 2, 1),
                category(1, null, 0, 0),
                category(4, 1L, 1, 1),
                category(2, 1L, 1, 1)));

        List<CategoryVO> roots = service.adminTree(1);

        assertThat(roots).singleElement().satisfies(root -> {
            assertThat(root.id()).isEqualTo("1");
            assertThat(root.status()).isZero();
            assertThat(root.children()).extracting(CategoryVO::id).containsExactly("2", "4", "3");
            assertThat(root.children()).allSatisfy(child -> assertThat(child.children()).isEmpty());
        });
    }

    @Test
    void portalTreeIncludesEnabledAncestorsAndOmitsBranchesBelowDisabledNodes() {
        when(mapper.selectList(null)).thenReturn(List.of(
                category(1, null, 0, 1),
                category(2, 1L, 0, 0),
                category(3, 2L, 0, 1),
                category(4, 1L, 0, 1),
                category(5, 1L, 0, 1)));
        when(mapper.selectPublishedCategoryIds()).thenReturn(List.of(3L, 4L));

        assertThat(service.portalTree()).singleElement().satisfies(root -> {
            assertThat(root.id()).isEqualTo("1");
            assertThat(root.children()).extracting(CategoryVO::id).containsExactly("4");
        });
    }

    @Test
    void portalFilterCanSelectEnabledAncestorOfArticleInDisabledBranch() {
        when(mapper.selectList(null)).thenReturn(List.of(
                category(1, null, 0, 1),
                category(2, 1L, 0, 0),
                category(3, 2L, 0, 1)));
        when(mapper.selectPublishedCategoryIds()).thenReturn(List.of(3L));

        assertThat(service.portalTree()).singleElement().satisfies(root -> {
            assertThat(root.id()).isEqualTo("1");
            assertThat(root.children()).isEmpty();
        });
    }

    @Test
    void updateRejectsMovingNodeUnderItsDescendant() {
        when(mapper.selectAllForUpdate()).thenReturn(List.of(
                category(1, null, 0, 1),
                category(2, 1L, 0, 1),
                category(3, 2L, 0, 1)));

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> service.update(1L, 3L, "技术文档", 0))
                .satisfies(ex -> assertThat(ex.getResultCode()).isEqualTo(ResultCode.CONFLICT));
    }

    @Test
    void assignmentRejectsEnabledNodeWithDisabledAncestor() {
        when(mapper.selectAllForShare()).thenReturn(List.of(
                category(1, null, 0, 0),
                category(2, 1L, 0, 1)));

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> service.lockAssignable(2L))
                .satisfies(ex -> assertThat(ex.getResultCode()).isEqualTo(ResultCode.CONFLICT));
    }

    @Test
    void deleteRejectsCategoryReferencedByAnArticleIncludingDeletedOnes() {
        when(mapper.selectForUpdate(1L)).thenReturn(category(1, null, 0, 1));
        when(mapper.hasArticleReference(1L)).thenReturn(true);

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> service.delete(1L))
                .satisfies(ex -> assertThat(ex.getResultCode()).isEqualTo(ResultCode.CONFLICT));

        verify(mapper, never()).deleteById(1L);
    }

    @Test
    void articleCategoryFilterIncludesDisabledDescendants() {
        when(mapper.selectList(null)).thenReturn(List.of(
                category(1, null, 0, 1),
                category(2, 1L, 0, 0),
                category(3, 2L, 0, 1),
                category(4, null, 0, 1)));

        assertThat(service.descendantIds(1L)).containsExactlyInAnyOrder(1L, 2L, 3L);
        assertThat(service.descendantIds(9L)).isEmpty();
    }

    private static Category category(long id, Long parentId, int order, int status) {
        Category category = new Category();
        category.setId(id);
        category.setParentId(parentId);
        category.setCategoryName("分类 " + id);
        category.setSortOrder(order);
        category.setStatus(status);
        category.setCreatedAt(LocalDateTime.of(2026, 11, 10, 12, 0));
        category.setUpdatedAt(category.getCreatedAt());
        return category;
    }
}
