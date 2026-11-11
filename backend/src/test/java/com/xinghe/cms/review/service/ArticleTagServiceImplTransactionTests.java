package com.xinghe.cms.review.service;

import java.lang.reflect.Method;
import java.util.List;

import com.xinghe.cms.review.service.impl.ArticleTagServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 校验 ArticleTagService.replaceBindings 的 MANDATORY 事务语义：不连接 MySQL（事务检查发生在方法体内之前）。
 */
@SpringBootTest
class ArticleTagServiceImplTransactionTests {

    @Autowired
    private ArticleTagService articleTagService;

    @Test
    void replaceBindingsRequiresExistingTransaction() {
        // 参数校验也在方法体内，若没有 MANDATORY 拦截会先抛 400；这里必须直接抛事务状态异常。
        assertThatThrownBy(() -> articleTagService.replaceBindings(1L, null))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void replaceBindingsDeclaresMandatoryPropagation() throws NoSuchMethodException {
        Method method = ArticleTagServiceImpl.class
                .getMethod("replaceBindings", Long.class, List.class);

        Transactional transactional = method.getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.MANDATORY);
    }

    @Test
    void findByArticleIdsIsReadOnlyTransactional() throws NoSuchMethodException {
        Method method = ArticleTagServiceImpl.class
                .getMethod("findByArticleIds", List.class);

        Transactional transactional = method.getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.readOnly()).isTrue();
    }
}
