package com.xinghe.cms.content.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.response.ResultCode;
import com.xinghe.cms.content.entity.Category;
import com.xinghe.cms.content.mapper.CategoryMapper;
import com.xinghe.cms.content.vo.CategoryVO;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CategoryService {

    private static final int ENABLED = 1;
    private static final Comparator<Category> TREE_ORDER =
            Comparator.comparing(Category::getSortOrder).thenComparing(Category::getId);

    private final CategoryMapper mapper;

    public CategoryService(CategoryMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public List<CategoryVO> adminTree(Integer status) {
        if (status != null) {
            validateStatus(status);
        }
        Map<Long, List<Category>> children = indexChildren(mapper.selectList(null));
        return buildTrees(children.getOrDefault(null, List.of()), children,
                category -> status == null || status.equals(category.getStatus()));
    }

    @Transactional(readOnly = true)
    public List<CategoryVO> portalTree() {
        List<Category> categories = mapper.selectList(null);
        Map<Long, Category> byId = indexById(categories);
        Set<Long> articleAncestors = new HashSet<>();
        for (Long categoryId : mapper.selectPublishedCategoryIds()) {
            Category category = byId.get(categoryId);
            while (category != null && articleAncestors.add(category.getId())) {
                category = byId.get(category.getParentId());
            }
        }

        // A published article can make an enabled ancestor selectable even across a disabled node.
        List<Category> enabled = categories.stream()
                .filter(category -> category.getStatus() == ENABLED)
                .toList();
        Map<Long, List<Category>> children = indexChildren(enabled);
        return buildTrees(children.getOrDefault(null, List.of()), children,
                category -> articleAncestors.contains(category.getId()));
    }

    @Transactional(readOnly = true)
    public Set<Long> descendantIds(Long categoryId) {
        validateId(categoryId);
        List<Category> categories = mapper.selectList(null);
        Category category = indexById(categories).get(categoryId);
        if (category == null) {
            return Set.of();
        }
        List<Category> descendants = traverse(List.of(category), indexChildren(categories));
        Set<Long> ids = new HashSet<>();
        descendants.forEach(item -> ids.add(item.getId()));
        return Set.copyOf(ids);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CategoryVO create(Long parentId, String categoryName, Integer sortOrder) {
        String name = normalizeName(categoryName);
        int order = sortOrder == null ? 0 : sortOrder;
        validateOrder(order);
        if (parentId != null) {
            validateId(parentId);
            validateAncestry(parentId, null, true, indexById(mapper.selectAllForUpdate()));
        }

        Category category = new Category();
        category.setParentId(parentId);
        category.setCategoryName(name);
        category.setSortOrder(order);
        category.setStatus(ENABLED);
        mapper.insert(category);
        return toView(mapper.selectById(category.getId()), List.of());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CategoryVO update(Long id, Long parentId, String categoryName, int sortOrder) {
        validateId(id);
        if (parentId != null) {
            validateId(parentId);
        }
        String name = normalizeName(categoryName);
        validateOrder(sortOrder);

        // Lock the current topology in primary key order before checking for cycles.
        Map<Long, Category> categories = indexById(mapper.selectAllForUpdate());
        Category category = requireCategory(categories.get(id));
        validateAncestry(parentId, id, !Objects.equals(category.getParentId(), parentId), categories);

        mapper.update(Wrappers.<Category>lambdaUpdate()
                .eq(Category::getId, id)
                .set(Category::getParentId, parentId)
                .set(Category::getCategoryName, name)
                .set(Category::getSortOrder, sortOrder));

        Category updated = mapper.selectById(id);
        categories.put(id, updated);
        return buildTrees(List.of(updated), indexChildren(categories.values()), ignored -> true).getFirst();
    }

    @Transactional
    public void setStatus(Long id, int status) {
        validateId(id);
        validateStatus(status);
        Category category = requireCategory(mapper.selectForUpdate(id));
        if (category.getStatus() != status) {
            mapper.update(Wrappers.<Category>lambdaUpdate()
                    .eq(Category::getId, id)
                    .set(Category::getStatus, status));
        }
    }

    @Transactional
    public void delete(Long id) {
        validateId(id);
        requireCategory(mapper.selectForUpdate(id));
        if (mapper.hasChild(id) || mapper.hasArticleReference(id)) {
            throw categoryInUse();
        }
        try {
            mapper.deleteById(id);
        } catch (DataIntegrityViolationException ex) {
            if (ex.getMostSpecificCause() instanceof SQLException sql && sql.getErrorCode() == 1451) {
                throw categoryInUse();
            }
            throw ex;
        }
    }

    /**
     * Keep the category ancestry enabled and stable until the caller's article write commits.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockAssignable(Long categoryId) {
        validateId(categoryId);
        validateAncestry(categoryId, null, true, indexById(mapper.selectAllForShare()));
    }

    private static void validateAncestry(Long parentId, Long movingId, boolean requireEnabled,
                                         Map<Long, Category> categories) {
        Set<Long> visited = new HashSet<>();
        Long id = parentId;
        while (id != null) {
            if (id.equals(movingId) || !visited.add(id)) {
                throw new BusinessException(ResultCode.CONFLICT, "分类不能形成循环");
            }
            Category category = requireCategory(categories.get(id));
            if (requireEnabled && category.getStatus() != ENABLED) {
                throw new BusinessException(ResultCode.CONFLICT, "分类或其上级分类已停用");
            }
            id = category.getParentId();
        }
    }

    private static Map<Long, Category> indexById(List<Category> categories) {
        Map<Long, Category> byId = new HashMap<>();
        categories.forEach(category -> byId.put(category.getId(), category));
        return byId;
    }

    private static Map<Long, List<Category>> indexChildren(Iterable<Category> categories) {
        Map<Long, List<Category>> children = new HashMap<>();
        for (Category category : categories) {
            children.computeIfAbsent(category.getParentId(), ignored -> new ArrayList<>()).add(category);
        }
        children.values().forEach(siblings -> siblings.sort(TREE_ORDER));
        return children;
    }

    private static List<Category> traverse(List<Category> roots, Map<Long, List<Category>> children) {
        List<Category> ordered = new ArrayList<>(roots);
        Set<Long> seen = new HashSet<>();
        for (int index = 0; index < ordered.size(); index++) {
            Long id = ordered.get(index).getId();
            if (!seen.add(id)) {
                throw new BusinessException(ResultCode.CONFLICT, "分类不能形成循环");
            }
            ordered.addAll(children.getOrDefault(id, List.of()));
        }
        return ordered;
    }

    private static List<CategoryVO> buildTrees(List<Category> roots, Map<Long, List<Category>> children,
                                                Predicate<Category> matchesFilter) {
        List<Category> ordered = traverse(roots, children);
        Map<Long, CategoryVO> included = new HashMap<>();
        for (Category category : ordered.reversed()) {
            List<CategoryVO> childViews = includedViews(children.getOrDefault(category.getId(), List.of()), included);
            if (matchesFilter.test(category) || !childViews.isEmpty()) {
                included.put(category.getId(), toView(category, childViews));
            }
        }
        return includedViews(roots, included);
    }

    private static List<CategoryVO> includedViews(List<Category> categories, Map<Long, CategoryVO> included) {
        return categories.stream()
                .map(category -> included.get(category.getId()))
                .filter(Objects::nonNull)
                .toList();
    }

    private static CategoryVO toView(Category category, List<CategoryVO> children) {
        Long parentId = category.getParentId();
        return new CategoryVO(category.getId().toString(), parentId == null ? null : parentId.toString(),
                category.getCategoryName(), category.getSortOrder(), category.getStatus(),
                children, category.getCreatedAt(), category.getUpdatedAt());
    }

    private static Category requireCategory(Category category) {
        if (category == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "分类不存在");
        }
        return category;
    }

    private static BusinessException categoryInUse() {
        return new BusinessException(ResultCode.CONFLICT, "分类仍有子分类或关联文章");
    }

    private static void validateId(Long id) {
        if (id == null || id <= 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "分类 ID 必须为正整数");
        }
    }

    private static void validateOrder(int sortOrder) {
        if (sortOrder < 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "排序值不能为负数");
        }
    }

    private static void validateStatus(int status) {
        if (status != 0 && status != ENABLED) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "分类状态必须为 0 或 1");
        }
    }

    private static String normalizeName(String categoryName) {
        if (categoryName == null || categoryName.isBlank() || categoryName.codePointCount(0, categoryName.length()) > 100) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "分类名称不能为空且长度不能超过 100");
        }
        return categoryName.strip();
    }
}
