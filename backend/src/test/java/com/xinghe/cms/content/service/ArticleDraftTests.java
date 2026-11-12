package com.xinghe.cms.content.service;

import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.content.dto.ArticleDraft;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArticleDraftTests {

    @Test
    void acceptsSupplementaryCharactersUpToTheSchemaLength() {
        String title = "📘".repeat(255);
        String summary = "📗".repeat(1000);
        ArticleDraft draft = new ArticleDraft(title, summary, " 正文 ", 1L);
        assertThat(draft.title()).isEqualTo(title);
        assertThat(draft.summary()).isEqualTo(summary);
        assertThat(draft.content()).isEqualTo(" 正文 ");
    }

    @Test
    void checksTitleLengthBeforeRemovingWhitespace() {
        assertThatThrownBy(() -> new ArticleDraft(" " + "📘".repeat(255), null, "正文", 1L))
                .isInstanceOf(BusinessException.class);
    }
}
