package com.xinghe.cms.content.service;

import com.xinghe.cms.common.contract.AuthorizationSnapshot;
import com.xinghe.cms.common.contract.CurrentUser;
import com.xinghe.cms.common.enums.ArticleAction;
import com.xinghe.cms.common.enums.ArticleStatus;
import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.request.PageQuery;
import com.xinghe.cms.common.response.ResultCode;
import com.xinghe.cms.content.dto.ArticleDraft;
import com.xinghe.cms.content.dto.ArticleQuery;
import com.xinghe.cms.content.mapper.row.ArticleRow;
import com.xinghe.cms.content.vo.CategoryVO;
import com.xinghe.cms.content.vo.TagVO;
import com.xinghe.cms.permission.service.AuthorizationQueryService;
import com.xinghe.cms.security.CurrentUserProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/** 在已导入 001_init.sql 的专用空库中显式运行，测试数据随事务回滚。 */
@SpringBootTest
@Transactional
class ContentMysqlIT {

    private static final AuthorizationSnapshot EDITOR = new AuthorizationSnapshot(Set.of("EDITOR"),
            Set.of("article:list", "article:read", "article:create", "article:update",
                    "article:submit", "article:delete"));
    private static final AuthorizationSnapshot REVIEWER = new AuthorizationSnapshot(Set.of("REVIEWER"),
            Set.of("article:list", "article:approve", "article:publish", "article:offline"));
    private static final AuthorizationSnapshot EMPLOYEE = new AuthorizationSnapshot(Set.of("EMPLOYEE"),
            Set.of("portal:article:list", "portal:article:read"));

    @Autowired
    private CategoryService categories;
    @Autowired
    private TagService tags;
    @Autowired
    private ArticleDraftService drafts;
    @Autowired
    private ArticleWorkflowService workflow;
    @Autowired
    private ArticleQueryService queries;
    @Autowired
    private JdbcTemplate jdbc;
    @MockitoBean
    private CurrentUserProvider users;
    @MockitoBean
    private AuthorizationQueryService permissions;

    private long author;
    private long other;
    private long employee;
    private long root;
    private long child;

    @BeforeEach
    void seed() {
        author = createUser("content-it-author");
        other = createUser("content-it-other");
        employee = createUser("content-it-employee");
        root = Long.parseLong(categories.create(null, "内容测试根分类", 3).id());
        child = Long.parseLong(categories.create(root, "内容测试子分类", 0).id());
        authenticate(author, EDITOR);
    }

    @Test
    void pagesByScopeCategoryTagAndLiteralKeywordBeforeCounting() {
        long ownDraft = drafts.create(new ArticleDraft("Java 10%_!", null, "正文", child));
        authenticate(other, EDITOR);
        long otherDraft = drafts.create(new ArticleDraft("Java 10%_!", null, "另一位作者的正文", child));
        long otherPending = drafts.create(new ArticleDraft("Java 10%_!", null, "已提交", child));
        drafts.submit(otherPending, 0);
        authenticate(author, EDITOR);
        long wildcardOnly = drafts.create(new ArticleDraft("Java 100XX!", null, "正文", child));
        long tagId = Long.parseLong(tags.create("java-test-tag").id());
        for (long articleId : new long[]{ownDraft, otherDraft, otherPending, wildcardOnly}) {
            jdbc.update("INSERT INTO cms_article_tag (article_id, tag_id) VALUES (?, ?)", articleId, tagId);
        }
        ArticleQuery query = new ArticleQuery();
        query.setCategoryId(root);
        query.setKeyword(" 10%_! ");
        query.setTagId(tagId);
        query.setSize(1);

        var editorPage = queries.adminPage(query);
        assertThat(editorPage.total()).isEqualTo(1);
        assertThat(editorPage.records()).singleElement().satisfies(row -> {
            assertThat(row.id()).isEqualTo(ownDraft);
            assertThat(row.categoryName()).isEqualTo("内容测试子分类");
            assertThat(row.authorName()).isEqualTo("content-it-author");
            assertThat(row.content()).isNull();
        });

        authenticate(author, REVIEWER);
        var reviewerPage = queries.adminPage(query);
        assertThat(reviewerPage.total()).isEqualTo(2);
        query.setCurrent(2);
        assertThat(queries.adminPage(query).records()).extracting(ArticleRow::id)
                .doesNotContain(otherDraft).hasSize(1);
        authenticate(author, EDITOR);
        query.setAuthorId(other);
        assertThat(queries.adminPage(query).total()).isZero();
    }

    @Test
    void enforcesVersionsAndLimitsPortalToPublishedArticles() {
        long id = drafts.create(new ArticleDraft(" 标题 ", "摘要", " 正文 ", child));
        assertThat(queries.adminDetail(id).content()).isEqualTo(" 正文 ");
        authenticate(employee, EMPLOYEE);
        assertThatThrownBy(() -> queries.portalDetail(id))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getResultCode()).isEqualTo(ResultCode.NOT_FOUND));

        authenticate(author, EDITOR);
        assertThat(drafts.submit(id, 0).version()).isEqualTo(1);
        assertThatThrownBy(() -> drafts.submit(id, 0))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getResultCode()).isEqualTo(ResultCode.CONFLICT));

        authenticate(other, REVIEWER);
        workflow.transition(id, 1, ArticleAction.APPROVE);
        var published = workflow.transition(id, 2, ArticleAction.PUBLISH);
        assertThat(published.publishedAt()).isNotNull();
        authenticate(employee, EMPLOYEE);
        assertThat(queries.portalDetail(id).title()).isEqualTo("标题");

        authenticate(other, REVIEWER);
        var offline = workflow.transition(id, 3, ArticleAction.OFFLINE);
        assertThat(offline.publishedAt()).isEqualTo(published.publishedAt());
        authenticate(employee, EMPLOYEE);
        assertThat(queries.portalPage(new ArticleQuery()).total()).isZero();
    }

    @Test
    void savesAnUnchangedDisabledCategoryAndKeepsReferencesOnLogicalDelete() {
        long id = drafts.create(new ArticleDraft("原稿", "原摘要", "正文", child));
        categories.setStatus(root, 0);

        drafts.save(id, 0, new ArticleDraft("更新稿", null, "新正文", child));
        var saved = queries.adminDetail(id);
        assertThat(saved.summary()).isNull();
        assertThat(saved.version()).isEqualTo(1);
        assertThatThrownBy(() -> drafts.create(new ArticleDraft("新稿", null, "正文", child)))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getResultCode()).isEqualTo(ResultCode.CONFLICT));

        drafts.delete(id, 1);
        assertThatThrownBy(() -> queries.adminDetail(id))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getResultCode()).isEqualTo(ResultCode.NOT_FOUND));
        assertThatThrownBy(() -> categories.delete(child))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getResultCode()).isEqualTo(ResultCode.CONFLICT));
    }

    @Test
    void portalTaxonomyRequiresPublishedReferencesAndOmitsDisabledChoices() {
        long articleId = drafts.create(new ArticleDraft("门户测试", null, "正文", child));
        TagVO enabled = tags.create("test-tag%_!");
        TagVO disabled = tags.create("停用测试标签");
        for (TagVO tag : new TagVO[]{enabled, disabled}) {
            jdbc.update("INSERT INTO cms_article_tag (article_id, tag_id) VALUES (?, ?)", articleId, Long.parseLong(tag.id()));
        }
        assertThat(categories.portalTree()).isEmpty();
        assertThat(tags.portalPage(new PageQuery(), null).total()).isZero();
        tags.setStatus(Long.parseLong(disabled.id()), 0);

        drafts.submit(articleId, 0);
        authenticate(other, REVIEWER);
        workflow.transition(articleId, 1, ArticleAction.APPROVE);
        var published = workflow.transition(articleId, 2, ArticleAction.PUBLISH);

        assertThat(categories.portalTree()).singleElement().satisfies(category ->
                assertThat(category.children()).extracting(CategoryVO::id).containsExactly(Long.toString(child)));
        assertThat(tags.portalPage(new PageQuery(), "%_!").records()).extracting(TagVO::id)
                .containsExactly(enabled.id());
        assertThat(tags.portalPage(new PageQuery(), null).total()).isEqualTo(1);
        assertThat(published.status()).isEqualTo(ArticleStatus.PUBLISHED);
    }

    private void authenticate(long userId, AuthorizationSnapshot snapshot) {
        when(users.requireCurrentUser()).thenReturn(new CurrentUser(userId, "fixture", null));
        when(permissions.getForUser(userId)).thenReturn(snapshot);
    }

    private long createUser(String name) {
        jdbc.update("INSERT INTO sys_user (username, password_hash, real_name) VALUES (?, 'test-fixture', ?)", name, name);
        return jdbc.queryForObject("SELECT id FROM sys_user WHERE username = ?", Long.class, name);
    }
}
