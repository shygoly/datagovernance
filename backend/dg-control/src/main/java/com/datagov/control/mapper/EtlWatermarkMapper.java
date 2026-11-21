package com.datagov.control.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datagov.control.entity.EtlEntities.EtlWatermark;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface EtlWatermarkMapper extends BaseMapper<EtlWatermark> {
}
