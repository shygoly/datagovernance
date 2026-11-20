package com.datagov.metadata.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datagov.metadata.entity.SemanticEntities.SemanticLink;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SemanticLinkMapper extends BaseMapper<SemanticLink> {
}
