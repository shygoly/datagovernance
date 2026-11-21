package com.datagov.governance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datagov.governance.entity.GovernanceEntities.OperationalReportVersion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface OperationalReportVersionMapper extends BaseMapper<OperationalReportVersion> {

    @Select("""
            SELECT * FROM gv_operational_report_version
            WHERE report_id = #{reportId} AND version = #{version}
            LIMIT 1
            """)
    OperationalReportVersion selectByReportAndVersion(@Param("reportId") String reportId,
                                                      @Param("version") int version);
}
