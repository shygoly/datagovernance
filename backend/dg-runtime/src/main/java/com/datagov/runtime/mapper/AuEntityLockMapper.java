package com.datagov.runtime.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datagov.runtime.entity.AutonomyRuntimeEntities.EntityLock;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AuEntityLockMapper extends BaseMapper<EntityLock> {
}
