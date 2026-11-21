package com.datagov.metadata.service;

import com.datagov.common.error.BizException;
import com.datagov.common.tenant.Caller;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.data.spi.ConnectionConfig;
import com.datagov.data.spi.DataAccessGateway;
import com.datagov.data.spi.DataSourceType;
import com.datagov.data.spi.query.SqlQuery;
import com.datagov.metadata.domain.DataSourceStatus;
import com.datagov.metadata.entity.CatalogAssetEntities.CatalogColumn;
import com.datagov.metadata.entity.CatalogAssetEntities.CatalogDataset;
import com.datagov.metadata.entity.CatalogAssetEntities.CatalogSource;
import com.datagov.metadata.entity.DataSourceEntity;
import com.datagov.metadata.entity.DatasetProfileRun;
import com.datagov.metadata.event.DatasetProfileCompletedEvent;
import com.datagov.metadata.mapper.CatalogColumnMapper;
import com.datagov.metadata.mapper.CatalogDatasetMapper;
import com.datagov.metadata.mapper.CatalogSourceMapper;
import com.datagov.metadata.mapper.DatasetProfileRunMapper;
import com.datagov.metadata.service.DatasetQualityProfileService.CodeTableSpec;
import com.datagov.metadata.service.DatasetQualityProfileService.ProfileRequest;
import com.datagov.metadata.service.DatasetQualityProfileService.ProfileView;
import com.datagov.metadata.service.DatasetQualityProfileService.ThresholdSpec;
import com.datagov.metadata.service.DatasetQualityProfileService.TimestampPairSpec;
import com.datagov.metadata.service.DatasetQualityProfileService.UnitSpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatasetQualityProfileServiceTest {

    private CatalogDatasetMapper datasetMapper;
    private CatalogColumnMapper columnMapper;
    private CatalogSourceMapper sourceMapper;
    private DataSourceService dataSourceService;
    private ConnectionConfigAssembler assembler;
    private DataAccessGateway gateway;
    private DatasetProfileRunMapper profileRunMapper;
    private ApplicationEventPublisher eventPublisher;
    private DatasetQualityProfileService service;

    @BeforeEach
    void setUp() {
        datasetMapper = mock(CatalogDatasetMapper.class);
        columnMapper = mock(CatalogColumnMapper.class);
        sourceMapper = mock(CatalogSourceMapper.class);
        dataSourceService = mock(DataSourceService.class);
        assembler = mock(ConnectionConfigAssembler.class);
        gateway = mock(DataAccessGateway.class);
        profileRunMapper = mock(DatasetProfileRunMapper.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = new DatasetQualityProfileService(
                datasetMapper, columnMapper, sourceMapper, dataSourceService,
                assembler, gateway, profileRunMapper, eventPublisher, new ObjectMapper());

        WorkspaceContext.set(new Caller("usr_alice", "alice", "ws_1", false, Set.of("metadata:quality:manage")));
    }

    @AfterEach
    void tearDown() {
        WorkspaceContext.clear();
    }

    private void stubDatasetAndSource() {
        CatalogDataset ds = new CatalogDataset();
        ds.setId("cd_1");
        ds.setWorkspaceId("ws_1");
        ds.setSourceId("cs_1");
        ds.setName("lab_orders");
        ds.setQualifiedName("public.lab_orders");
        ds.setSchemaHash("sha_schema_1");
        when(datasetMapper.selectById("cd_1")).thenReturn(ds);

        CatalogColumn c1 = col("col_1", "unit", 1);
        CatalogColumn c2 = col("col_2", "icd_code", 2);
        CatalogColumn c3 = col("col_3", "admit_time", 3);
        CatalogColumn c4 = col("col_4", "discharge_time", 4);
        CatalogColumn c5 = col("col_5", "lab_value", 5);
        when(columnMapper.selectList(any())).thenReturn(List.of(c1, c2, c3, c4, c5));

        CatalogSource src = new CatalogSource();
        src.setId("cs_1");
        src.setSourceType("JDBC");
        src.setDataSourceId("ds_pg");
        when(sourceMapper.selectById("cs_1")).thenReturn(src);

        DataSourceEntity dse = new DataSourceEntity();
        dse.setId("ds_pg");
        dse.setType(DataSourceType.POSTGRESQL);
        dse.setStatus(DataSourceStatus.AVAILABLE);
        when(dataSourceService.requireInWorkspace("ds_pg")).thenReturn(dse);
        when(assembler.assemble(dse, "ws_1")).thenReturn(mock(ConnectionConfig.class));
    }

    private static CatalogColumn col(String id, String name, int ordinal) {
        CatalogColumn c = new CatalogColumn();
        c.setId(id);
        c.setDatasetId("cd_1");
        c.setName(name);
        c.setOrdinal(ordinal);
        return c;
    }

    @Test
    @DisplayName("FR-02：五维确定性质量剖析检出异常、生成稳定指纹并发布解耦事件")
    void profileComputesFiveDimensionsAndPublishesEvent() {
        stubDatasetAndSource();

        // 1. null rate SQL: total=1000, unit present=1000, icd_code=900(10% null), admit=1000, discharge=1000, lab_value=1000
        // 2. unit heterogeneity: 12 rows (1.2%)
        // 3. code table drift: 5 rows
        // 4. timestamp inversion: 3 rows
        // 5. threshold outliers: 47 rows
        when(gateway.executeQuery(eq(DataSourceType.POSTGRESQL), any(), any()))
                .thenReturn(sqlResult(false, List.of("1000", "1000", "900", "1000", "1000", "1000")))
                .thenReturn(sqlResult(false, List.of("12")))
                .thenReturn(sqlResult(false, List.of("5")))
                .thenReturn(sqlResult(false, List.of("3")))
                .thenReturn(sqlResult(false, List.of("47")));

        ProfileRequest req = new ProfileRequest(
                10,
                0.05d,
                List.of(new UnitSpec("unit", List.of("mg/dL"), "STD_LAB_UNIT_V1")),
                List.of(new CodeTableSpec("icd_code", List.of("E11", "I10"), "STD_ICD10_V2026")),
                List.of(new TimestampPairSpec("admit_time", "discharge_time", "STD_TEMPORAL_V1")),
                List.of(new ThresholdSpec("lab_value", 0.0, 500.0, "STD_LAB_RANGE_V1"))
        );

        ProfileView view = service.profileDataset("cd_1", req);

        assertThat(view.verdict()).isEqualTo(DatasetQualityProfileService.VERDICT_ANOMALIES);
        assertThat(view.totalRows()).isEqualTo(1000L);
        assertThat(view.anomalyCount()).isEqualTo(5);
        assertThat(view.patternFingerprint()).startsWith("pat_");
        assertThat(view.reproducibilityFingerprint()).contains("\"schemaVersion\":1").contains("STD_LAB_RANGE_V1");
        assertThat(view.unitOrEnumHeterogeneity().get(0).violationRatio()).isEqualTo(0.012d);
        assertThat(view.thresholdOutliers().get(0).violationCount()).isEqualTo(47L);

        verify(profileRunMapper).insert(any(DatasetProfileRun.class));

        ArgumentCaptor<DatasetProfileCompletedEvent> eventCaptor =
                ArgumentCaptor.forClass(DatasetProfileCompletedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        DatasetProfileCompletedEvent evt = eventCaptor.getValue();
        assertThat(evt.verdict()).isEqualTo("ANOMALIES_DETECTED");
        assertThat(evt.patternFingerprint()).isEqualTo(view.patternFingerprint());
        assertThat(evt.anomalies()).hasSize(5);
    }

    @Test
    @DisplayName("C2：未绑定 Standard（standardRef）的阈值离群剖析显式失败")
    void missingStandardRefRejectedByC2() {
        ProfileRequest bad = new ProfileRequest(
                1, 0.05d, List.of(), List.of(), List.of(),
                List.of(new ThresholdSpec("lab_value", 0.0, 500.0, " "))
        );
        assertThatThrownBy(() -> service.profileDataset("cd_1", bad))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo("MTD_QUALITY_RULE_INVALID"));
    }

    @Test
    @DisplayName("C8：样本不足或查询截断时强制返回 INCONCLUSIVE")
    void insufficientSampleOrTruncatedForcesInconclusive() {
        stubDatasetAndSource();
        when(gateway.executeQuery(eq(DataSourceType.POSTGRESQL), any(), any()))
                .thenReturn(sqlResult(true, List.of("50", "50", "50", "50", "50", "50")));

        ProfileView view = service.profileDataset("cd_1", ProfileRequest.defaults());
        assertThat(view.verdict()).isEqualTo(DatasetQualityProfileService.VERDICT_INCONCLUSIVE);
        assertThat(view.truncated()).isTrue();
    }

    private static SqlQuery.Result sqlResult(boolean truncated, List<String> row) {
        return new SqlQuery.Result(List.of(), List.of(row), 1, truncated, 5L);
    }
}
