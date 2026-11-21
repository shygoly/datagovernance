package com.datagov.governance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datagov.governance.entity.AutonomyTraceEntities.DecisionTrace;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AuDecisionTraceMapper extends BaseMapper<DecisionTrace> {
}
