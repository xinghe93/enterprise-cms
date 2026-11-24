package com.xinghe.cms.content.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.request.PageQuery;
import com.xinghe.cms.common.response.ResultCode;
import com.xinghe.cms.content.entity.Tag;
import com.xinghe.cms.content.mapper.TagMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

class TagServiceTests {

    private final TagMapper mapper = mock(TagMapper.class);
    private final TagService service = new TagService(mapper);

    @Test
    void portalListEscapesLikeWildcardsAndKeepsPageMetadata() {
        Tag tag = tag(9L);
        when(mapper.findPage(any(), isNull(), eq("%a!!!%!_%"), eq(true)))
                .thenReturn(new Page<Tag>(2, 1, 2).setRecords(List.of(tag)));
        PageQuery query = new PageQuery();
        query.setCurrent(2);
        query.setSize(1);

        assertThat(service.portalPage(query, "  a!%_  ")).satisfies(page -> {
            assertThat(page.total()).isEqualTo(2);
            assertThat(page.current()).isEqualTo(2);
            assertThat(page.size()).isEqualTo(1);
            assertThat(page.records()).singleElement().satisfies(record -> {
                assertThat(record.id()).isEqualTo("9");
                assertThat(record.tagName()).isEqualTo("a!%_");
            });
        });
    }

    @Test
    void duplicateTagNameIsReportedAsConflict() {
        when(mapper.insert(any(Tag.class))).thenThrow(new DuplicateKeyException("uk_tag_name"));

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> service.create("Java"))
                .satisfies(ex -> assertThat(ex.getResultCode()).isEqualTo(ResultCode.CONFLICT));
    }

    @Test
    void deleteRejectsBoundTagWithoutRemovingAnyBindings() {
        when(mapper.selectForUpdate(9L)).thenReturn(tag(9L));
        when(mapper.hasArticleReference(9L)).thenReturn(true);

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> service.delete(9L))
                .satisfies(ex -> assertThat(ex.getResultCode()).isEqualTo(ResultCode.CONFLICT));

        verify(mapper, never()).deleteById(9L);
    }

    private static Tag tag(Long id) {
        Tag tag = new Tag();
        tag.setId(id);
        tag.setTagName("a!%_");
        tag.setStatus(1);
        tag.setCreatedAt(LocalDateTime.of(2026, 11, 10, 12, 0));
        tag.setUpdatedAt(tag.getCreatedAt());
        return tag;
    }
}
