package com.datagov.data.connector.federation;

import com.datagov.common.error.BizException;
import com.datagov.data.spi.ConnectionConfig;
import com.datagov.data.spi.DataAccessGateway;
import com.datagov.data.spi.DataSourceType;
import com.datagov.data.spi.FederatedQueryGateway.DuckDbFederatedRequest;
import com.datagov.data.spi.FederatedQueryGateway.FederatedResult;
import com.datagov.data.spi.FederatedQueryGateway.FederatedTableInput;
import com.datagov.data.spi.FederatedQueryGateway.FederationEngine;
import com.datagov.data.spi.FederatedQueryGateway.StarRocksFederatedRequest;
import com.datagov.data.spi.catalog.CanonicalType;
import com.datagov.data.spi.query.SqlQuery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultFederatedQueryGatewayTest {

    @Test
    @DisplayName("DuckDB 嵌入式联邦引擎：跨源 HIS 患者表 + LIS 检验表按 EMPI 任意 JOIN 并精确聚合")
    void duckDbExecutesArbitraryCrossSourceJoinAndAggregation() {
        DataAccessGateway mockGateway = mock(DataAccessGateway.class);
        when(mockGateway.availableTypes()).thenReturn(List.of(DataSourceType.POSTGRESQL, DataSourceType.STARROCKS));
        DefaultFederatedQueryGateway fed = new DefaultFederatedQueryGateway(mockGateway);

        assertThat(fed.availableEngines()).containsExactly(FederationEngine.DUCKDB, FederationEngine.STARROCKS);

        FederatedTableInput hisPatients = new FederatedTableInput(
                "wh_his_patients",
                List.of(
                        new SqlQuery.Column("empi_id", "VARCHAR", CanonicalType.VARCHAR),
                        new SqlQuery.Column("gender", "VARCHAR", CanonicalType.VARCHAR)
                ),
                List.of(
                        Map.of("empi_id", "P001", "gender", "M"),
                        Map.of("empi_id", "P002", "gender", "F"),
                        Map.of("empi_id", "P003", "gender", "M")
                )
        );
        FederatedTableInput lisLabs = new FederatedTableInput(
                "wh_lis_labs",
                List.of(
                        new SqlQuery.Column("empi_id", "VARCHAR", CanonicalType.VARCHAR),
                        new SqlQuery.Column("lactate", "DOUBLE", CanonicalType.DOUBLE)
                ),
                List.of(
                        Map.of("empi_id", "P001", "lactate", 2.4),
                        Map.of("empi_id", "P001", "lactate", 3.6),
                        Map.of("empi_id", "P002", "lactate", 1.5),
                        Map.of("empi_id", "P003", "lactate", 3.0)
                )
        );

        String joinSql = """
                SELECT p.gender,
                       COUNT(DISTINCT p.empi_id) AS patient_cnt,
                       ROUND(AVG(l.lactate), 2) AS avg_lactate
                FROM wh_his_patients p
                JOIN wh_lis_labs l ON p.empi_id = l.empi_id
                GROUP BY p.gender
                ORDER BY p.gender ASC
                """;

        FederatedResult res = fed.executeDuckDbJoin(new DuckDbFederatedRequest(
                List.of(hisPatients, lisLabs), joinSql, 100, 30
        ));

        assertThat(res.engineUsed()).isEqualTo("DUCKDB");
        assertThat(res.truncated()).isFalse();
        assertThat(res.rowCount()).isEqualTo(2);
        assertThat(res.rows().get(0).get("gender")).isEqualTo("F");
        assertThat(((Number) res.rows().get(0).get("patient_cnt")).longValue()).isEqualTo(1L);
        assertThat(((Number) res.rows().get(0).get("avg_lactate")).doubleValue()).isEqualTo(1.5);
        assertThat(res.rows().get(1).get("gender")).isEqualTo("M");
        assertThat(((Number) res.rows().get(1).get("patient_cnt")).longValue()).isEqualTo(2L);
        assertThat(((Number) res.rows().get(1).get("avg_lactate")).doubleValue()).isEqualTo(3.0);
    }

    @Test
    @DisplayName("C3 只读护栏：DuckDB 联邦查询拒绝任何写语句并如实报告 maxRows 截断")
    void duckDbRejectsWriteSqlAndDetectsTruncation() {
        DefaultFederatedQueryGateway fed = new DefaultFederatedQueryGateway(mock(DataAccessGateway.class));
        FederatedTableInput src = new FederatedTableInput(
                "src_a",
                List.of(new SqlQuery.Column("id", "BIGINT", CanonicalType.BIGINT)),
                List.of(Map.of("id", 1), Map.of("id", 2), Map.of("id", 3))
        );

        assertThatThrownBy(() -> fed.executeDuckDbJoin(new DuckDbFederatedRequest(
                List.of(src), "DROP TABLE src_a", 10, 10
        ))).isInstanceOf(BizException.class);

        FederatedResult truncated = fed.executeDuckDbJoin(new DuckDbFederatedRequest(
                List.of(src), "SELECT id FROM src_a ORDER BY id", 2, 10
        ));
        assertThat(truncated.rowCount()).isEqualTo(2);
        assertThat(truncated.truncated()).isTrue();
    }

    @Test
    @DisplayName("StarRocks External Catalog 联邦查询委托 DataAccessGateway 执行并校验只读")
    void starRocksDelegatesCrossCatalogSqlThroughDataAccessGateway() {
        DataAccessGateway mockGateway = mock(DataAccessGateway.class);
        when(mockGateway.executeQuery(eq(DataSourceType.STARROCKS), any(ConnectionConfig.class), any(SqlQuery.Request.class)))
                .thenReturn(new SqlQuery.Result(
                        List.of(
                                new SqlQuery.Column("gender", "VARCHAR", CanonicalType.VARCHAR),
                                new SqlQuery.Column("patient_cnt", "BIGINT", CanonicalType.BIGINT)
                        ),
                        List.of(List.of("M", "42")),
                        1,
                        false,
                        18L
                ));

        DefaultFederatedQueryGateway fed = new DefaultFederatedQueryGateway(mockGateway);
        ConnectionConfig cfg = new ConnectionConfig(
                "127.0.0.1", 9030, "analytics", "root", "", Map.of(), null, null, 5000, 30000, List.of()
        );

        FederatedResult res = fed.executeStarRocksJoin(new StarRocksFederatedRequest(
                cfg,
                "SELECT p.gender, COUNT(DISTINCT p.empi_id) AS patient_cnt FROM pg_his.public.patients p JOIN mysql_lis.lab.results l ON p.empi_id = l.empi_id GROUP BY p.gender",
                100,
                30
        ));

        assertThat(res.engineUsed()).isEqualTo("STARROCKS");
        assertThat(res.rowCount()).isEqualTo(1);
        assertThat(res.rows().get(0).get("patient_cnt")).isEqualTo(42L);
    }
}
