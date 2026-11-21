package com.datagov.governance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datagov.governance.entity.GovernanceEntities.OperationalReportDef;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface OperationalReportDefMapper extends BaseMapper<OperationalReportDef> {

    @Select("""
            SELECT * FROM gv_operational_report_def
            WHERE workspace_id = #{workspaceId} AND code = #{code}
            LIMIT 1
            """)
    OperationalReportDef selectByWorkspaceAndCode(@Param("workspaceId") String workspaceId,
                                                  @Param("code") String code);
}
