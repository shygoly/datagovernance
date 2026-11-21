package com.datagov.control.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datagov.control.entity.AutonomyControlEntities.FlowDefinition;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AuFlowDefMapper extends BaseMapper<FlowDefinition> {
}
