package com.datagov.metadata.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datagov.metadata.entity.SemanticEntities.SemanticObject;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SemanticObjectMapper extends BaseMapper<SemanticObject> {
}
