package com.xinghe.cms.content.vo;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

public record ArticleDetailVO(@JsonUnwrapped ArticleSummaryVO article, String content) {
}
