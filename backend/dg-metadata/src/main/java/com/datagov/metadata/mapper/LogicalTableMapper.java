package com.datagov.metadata.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datagov.metadata.entity.SemanticEntities.LogicalTable;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface LogicalTableMapper extends BaseMapper<LogicalTable> {
}
