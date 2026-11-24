package com.xinghe.cms.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xinghe.cms.content.entity.Category;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CategoryMapper extends BaseMapper<Category> {

    List<Category> selectAllForUpdate();

    List<Category> selectAllForShare();

    Category selectForUpdate(@Param("id") Long id);

    boolean hasChild(@Param("id") Long id);

    boolean hasArticleReference(@Param("id") Long id);

    List<Long> selectPublishedCategoryIds();
}
