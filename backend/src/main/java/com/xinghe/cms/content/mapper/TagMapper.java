package com.xinghe.cms.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xinghe.cms.content.entity.Tag;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface TagMapper extends BaseMapper<Tag> {

    IPage<Tag> findPage(Page<Tag> page, @Param("status") Integer status,
                        @Param("pattern") String pattern, @Param("portal") boolean portal);

    Tag selectForUpdate(@Param("id") Long id);

    boolean hasArticleReference(@Param("id") Long id);
}
