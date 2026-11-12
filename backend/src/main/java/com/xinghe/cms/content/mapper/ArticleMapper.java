package com.xinghe.cms.content.mapper;

import com.xinghe.cms.common.contract.ArticleSnapshot;
import com.xinghe.cms.common.enums.ArticleStatus;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ArticleMapper {

    ArticleSnapshot findSnapshot(@Param("id") Long id);

    ArticleSnapshot lockSnapshot(@Param("id") Long id);

    int changeState(@Param("expected") ArticleSnapshot expected, @Param("target") ArticleStatus target);
}
