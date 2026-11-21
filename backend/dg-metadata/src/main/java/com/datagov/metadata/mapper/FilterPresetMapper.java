package com.datagov.metadata.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datagov.metadata.entity.AutonomyEntities.FilterPreset;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface FilterPresetMapper extends BaseMapper<FilterPreset> {
}
