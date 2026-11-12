package com.xinghe.cms.content.service;

import com.xinghe.cms.common.enums.ArticleAction;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.IllegalTransactionStateException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class ArticleWorkflowTransactionsTests {

    @Autowired
    private ArticleAccessService access;

    @Autowired
    private ArticleWorkflowService workflow;

    @Test
    void editingLockRequiresAnExistingTransaction() {
        assertThatThrownBy(() -> access.lockEditable(1L, 0, ArticleAction.SAVE))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void transitionRequiresAnExistingTransaction() {
        assertThatThrownBy(() -> workflow.transition(1L, 0, ArticleAction.SUBMIT))
                .isInstanceOf(IllegalTransactionStateException.class);
    }
}
