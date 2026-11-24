package com.xinghe.cms.config.fixture;

import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ProbeMapper extends BaseMapper<PersistenceProbe> {

    List<PersistenceProbe> selectVisibleByName(@Param("name") String name);
}
