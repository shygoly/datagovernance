package com.datagov.app.lineage;

import com.datagov.app.lineage.StructuredJobLineageExtractor.EdgeDraft;
import com.datagov.app.lineage.StructuredJobLineageExtractor.Extraction;
import com.datagov.app.lineage.StructuredJobLineageExtractor.RuleInfo;
import com.datagov.metadata.service.LineageCatalogResolver;
import com.datagov.metadata.service.LineageService.EdgeInput;
import com.datagov.metadata.service.LineageService.NodeRef;
import com.datagov.metadata.service.lineage.SqlLineageParserClient;
import com.datagov.metadata.service.lineage.SqlLineageParserClient.ColumnEdge;
import com.datagov.metadata.service.lineage.SqlLineageParserClient.ParseRequest;
import com.datagov.metadata.service.lineage.SqlLineageParserClient.ParseResponse;
import com.datagov.metadata.service.lineage.SqlLineageParserClient.StatementLineage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("结构化作业血缘抽取器")
class StructuredJobLineageExtractorTest {

    private static final String WS = "ws_alpha";

    private FakeResolver resolver;
    private Map<String, RuleInfo> rules;
    private StructuredJobLineageExtractor extractor;

    @BeforeEach
    void setUp() {
        resolver = new FakeResolver();
        rules = new LinkedHashMap<>();
        extractor = new StructuredJobLineageExtractor(resolver,
                (workspaceId, ids) -> {
                    Map<String, RuleInfo> hit = new LinkedHashMap<>();
                    ids.forEach(id -> {
                        if (rules.containsKey(id)) {
                            hit.put(id, rules.get(id));
                        }
                    });
                    return hit;
                });
    }

    // ── OfflineSync ────────────────────────────────────────────────────

    @Test
    @DisplayName("OfflineSync 无规则:按 fieldMappings 产出 COPY / DIRECT 边")
    void offlineSyncCopy() {
        Extraction result = extractor.extract(WS, "OFFLINE_SYNC", offlineConfig(
                orderedMap("order_no", "order_id", "amount", "amt"), Map.of()));

        assertThat(result.edges()).hasSize(2);
        EdgeDraft first = result.edges().get(0);
        assertThat(first.from().qualifiedName()).isEqualTo("ods.public.orders.order_no");
        assertThat(first.to().qualifiedName()).isEqualTo("dws.public.order_sum.order_id");
        assertThat(first.relation()).isEqualTo("COPY");
        assertThat(first.dependencyType()).isEqualTo("DIRECT");
        assertThat(first.transformExpr()).isNull();
        assertThat(first.maskingApplied()).isFalse();
        assertThat(first.maskingEvidence()).isNull();
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    @DisplayName("OfflineSync 有清洗规则:TRANSFORM 且写规则摘要,不算脱敏")
    void offlineSyncTransform() {
        rules.put("rule_trim", new RuleInfo("rule_trim", "TRIM", Map.of("mode", "BOTH")));
        rules.put("rule_up", new RuleInfo("rule_up", "CHANGE_CASE", Map.of("mode", "UPPER")));

        Extraction result = extractor.extract(WS, "OfflineSync", offlineConfig(
                orderedMap("name", "name"), Map.of("name", List.of("rule_trim", "rule_up"))));

        EdgeDraft edge = result.edges().get(0);
        assertThat(edge.relation()).isEqualTo("TRANSFORM");
        assertThat(edge.transformExpr()).isEqualTo("TRIM(mode=BOTH) -> CHANGE_CASE(mode=UPPER)");
        assertThat(edge.maskingApplied()).isFalse();
    }

    @Test
    @DisplayName("OfflineSync 含 MASK 规则:maskingApplied=true 并写证据,凭据参数不入表达式")
    void offlineSyncMasking() {
        rules.put("rule_mask", new RuleInfo("rule_mask", "MASK",
                Map.of("mode", "HASH", "saltCredentialId", "cred_1")));

        Extraction result = extractor.extract(WS, "OFFLINE_SYNC", offlineConfig(
                orderedMap("id_card", "id_card"), Map.of("id_card", List.of("rule_mask"))));

        EdgeDraft edge = result.edges().get(0);
        assertThat(edge.relation()).isEqualTo("TRANSFORM");
        assertThat(edge.maskingApplied()).isTrue();
        assertThat(edge.maskingEvidence()).isEqualTo("rule:rule_mask(MASK/HASH)");
        assertThat(edge.transformExpr()).isEqualTo("MASK(mode=HASH)").doesNotContain("cred_1");
    }

    @Test
    @DisplayName("计划里直接写函数名(MD5/SHA256)同样识别为脱敏")
    void offlineSyncInlineFunctionName() {
        Extraction result = extractor.extract(WS, "OFFLINE_SYNC", offlineConfig(
                orderedMap("phone", "phone_md5"), Map.of("phone", List.of("md5"))));

        EdgeDraft edge = result.edges().get(0);
        assertThat(edge.maskingApplied()).isTrue();
        assertThat(edge.maskingEvidence()).isEqualTo("function:MD5");
        assertThat(edge.transformExpr()).isEqualTo("MD5");
    }

    @Test
    @DisplayName("规则 ID 查不到:TRANSFORM + 警告,但绝不声称已脱敏(C8)")
    void unknownRuleNeverClaimsMasking() {
        Extraction result = extractor.extract(WS, "OFFLINE_SYNC", offlineConfig(
                orderedMap("name", "name"), Map.of("name", List.of("rule_gone"))));

        EdgeDraft edge = result.edges().get(0);
        assertThat(edge.relation()).isEqualTo("TRANSFORM");
        assertThat(edge.transformExpr()).isEqualTo("rule:rule_gone");
        assertThat(edge.maskingApplied()).isFalse();
        assertThat(result.warnings()).singleElement().asString().contains("rule_gone");
    }

    @Test
    @DisplayName("同一字段混合清洗 + 脱敏:整条边视为脱敏阻断")
    void mixedRulesMasked() {
        rules.put("rule_trim", new RuleInfo("rule_trim", "TRIM", Map.of()));
        rules.put("rule_mask", new RuleInfo("rule_mask", "MASK", Map.of("mode", "PARTIAL")));

        Extraction result = extractor.extract(WS, "OFFLINE_SYNC", offlineConfig(
                orderedMap("mobile", "mobile"),
                Map.of("mobile", List.of("rule_trim", "rule_mask"))));

        EdgeDraft edge = result.edges().get(0);
        assertThat(edge.maskingApplied()).isTrue();
        assertThat(edge.maskingEvidence()).isEqualTo("rule:rule_mask(MASK/PARTIAL)");
        assertThat(edge.transformExpr()).startsWith("TRIM -> MASK(mode=PARTIAL)");
    }

    @Test
    @DisplayName("同时接受编译后的物理计划形状(source/target 嵌套段)")
    void acceptsPhysicalPlanShape() {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("kind", "OFFLINE_SYNC");
        plan.put("source", orderedObjMap("dataSourceId", "ds_src", "database", "ods",
                "schema", "public", "table", "orders"));
        plan.put("target", orderedObjMap("dataSourceId", "ds_dst", "database", "dws",
                "schema", "public", "table", "order_sum"));
        plan.put("fieldMappings", orderedMap("amount", "amt"));
        plan.put("fieldRules", Map.of());

        Extraction result = extractor.extract(WS, "OFFLINE_SYNC", plan);

        assertThat(result.edges()).singleElement().satisfies(edge -> {
            assertThat(edge.from().qualifiedName()).isEqualTo("ods.public.orders.amount");
            assertThat(edge.to().qualifiedName()).isEqualTo("dws.public.order_sum.amt");
        });
    }

    @Test
    @DisplayName("缺目标表 / 缺映射 / 配置为空 / 不支持的作业类型:不产出边并给原因")
    void degenerateInputs() {
        Map<String, Object> noTarget = offlineConfig(orderedMap("a", "b"), Map.of());
        noTarget.remove("targetTable");
        assertThat(extractor.extract(WS, "OFFLINE_SYNC", noTarget).edges()).isEmpty();
        assertThat(extractor.extract(WS, "OFFLINE_SYNC", offlineConfig(Map.of(), Map.of()))
                .warnings()).isNotEmpty();
        assertThat(extractor.extract(WS, "OFFLINE_SYNC", Map.of()).warnings()).isNotEmpty();
        assertThat(extractor.extract(WS, "OFFLINE_SYNC", null).edges()).isEmpty();
        Extraction unsupported = extractor.extract(WS, "BATCH", offlineConfig(
                orderedMap("a", "b"), Map.of()));
        assertThat(unsupported.edges()).isEmpty();
        assertThat(unsupported.warnings()).singleElement().asString().contains("BATCH");
    }

    @Test
    @DisplayName("源列与目标列限定名相同(同表自拷贝)时跳过,避免撞 LineageService 的自环校验")
    void selfEdgeSkipped() {
        Map<String, Object> config = offlineConfig(orderedMap("a", "a"), Map.of());
        config.put("targetDatabase", "ods");
        config.put("targetTable", "orders");

        Extraction result = extractor.extract(WS, "OFFLINE_SYNC", config);

        assertThat(result.edges()).isEmpty();
        assertThat(result.warnings()).hasSize(1);
    }

    // ── DbMigration ────────────────────────────────────────────────────

    @Test
    @DisplayName("DbMigration:按目录列清单 + 前缀/后缀/小写规则映射目标列,产出 COPY 边")
    void dbMigrationNaming() {
        resolver.datasets.put("Genre", "catds_genre");
        resolver.columns.put("catds_genre", List.of("GenreId", "Name"));

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("sourceDataSourceId", "ds_src");
        config.put("sourceDatabase", "chinook");
        config.put("sourceSchema", "public");
        config.put("targetDataSourceId", "ds_dst");
        config.put("targetDatabase", "dw");
        config.put("targetSchema", "stg");
        config.put("tables", List.of("Genre"));
        config.put("tablePrefix", "ods_");
        config.put("tableSuffix", "_v1");
        config.put("lowercaseNames", true);

        Extraction result = extractor.extract(WS, "DB_MIGRATION", config);

        assertThat(result.edges()).hasSize(2);
        assertThat(result.edges()).extracting(e -> e.from().qualifiedName() + " -> "
                        + e.to().qualifiedName())
                .containsExactly(
                        "chinook.public.Genre.GenreId -> dw.stg.ods_genre_v1.genreid",
                        "chinook.public.Genre.Name -> dw.stg.ods_genre_v1.name");
        assertThat(result.edges()).allSatisfy(edge -> {
            assertThat(edge.relation()).isEqualTo("COPY");
            assertThat(edge.dependencyType()).isEqualTo("DIRECT");
            assertThat(edge.maskingApplied()).isFalse();
        });
    }

    @Test
    @DisplayName("DbMigration:读取物理计划的 source.tables 与 naming 段")
    void dbMigrationFromPlan() {
        resolver.datasets.put("T1", "catds_1");
        resolver.columns.put("catds_1", List.of("ID"));

        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("source", orderedObjMap("dataSourceId", "ds_src", "database", "a",
                "tables", List.of("T1")));
        plan.put("target", orderedObjMap("dataSourceId", "ds_dst", "database", "b"));
        plan.put("naming", orderedObjMap("prefix", "p_", "lowercase", true));

        Extraction result = extractor.extract(WS, "DB_MIGRATION", plan);

        assertThat(result.edges()).singleElement().satisfies(edge ->
                assertThat(edge.to().qualifiedName()).isEqualTo("b.p_t1.id"));
    }

    @Test
    @DisplayName("DbMigration:源表不在目录或没有表清单时不猜列,给警告")
    void dbMigrationDoesNotGuess() {
        resolver.datasets.put("Known", "catds_k");
        resolver.columns.put("catds_k", List.of("id"));

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("sourceDataSourceId", "ds_src");
        config.put("sourceDatabase", "a");
        config.put("targetDataSourceId", "ds_dst");
        config.put("targetDatabase", "b");
        config.put("tables", List.of("Unknown", "Known"));

        Extraction result = extractor.extract(WS, "DB_MIGRATION", config);
        assertThat(result.edges()).hasSize(1);
        assertThat(result.warnings()).singleElement().asString().contains("Unknown");

        config.remove("tables");
        Extraction noTables = extractor.extract(WS, "DB_MIGRATION", config);
        assertThat(noTables.edges()).isEmpty();
        assertThat(noTables.warnings()).isNotEmpty();
    }

    // ── FileParse / ApiParse ───────────────────────────────────────────

    @Test
    @DisplayName("FileParse:源端为 file://{source}/{field} 且不带 Catalog ID,连向目标表列")
    void fileParse() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("sourceDataSourceId", "ds_files");
        config.put("path", "/landing/orders.csv");
        config.put("targetDataSourceId", "ds_dst");
        config.put("targetDatabase", "ods");
        config.put("targetSchema", "public");
        config.put("targetTable", "orders");
        config.put("fieldMappings", orderedMap("order_no", "order_id"));

        Extraction result = extractor.extract(WS, "FILE_PARSE", config);

        EdgeDraft edge = result.edges().get(0);
        assertThat(edge.from().nodeType()).isEqualTo("COLUMN");
        assertThat(edge.from().qualifiedName()).isEqualTo("file://ds_files/landing/orders.csv/order_no");
        assertThat(edge.from().catalogDatasetId()).isNull();
        assertThat(edge.to().qualifiedName()).isEqualTo("ods.public.orders.order_id");
        assertThat(edge.relation()).isEqualTo("COPY");
    }

    @Test
    @DisplayName("ApiParse:同样走 file:// 源节点,且 fieldRules 的脱敏被识别")
    void apiParseWithMasking() {
        rules.put("rule_mask", new RuleInfo("rule_mask", "MASK", Map.of("mode", "FIXED")));
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("sourceDataSourceId", "ds_api");
        config.put("path", "/v1/users");
        config.put("targetDatabase", "ods");
        config.put("targetTable", "users");
        config.put("fieldMappings", orderedMap("email", "email"));
        config.put("fieldRules", Map.of("email", List.of("rule_mask")));

        Extraction result = extractor.extract(WS, "ApiParse", config);

        EdgeDraft edge = result.edges().get(0);
        assertThat(edge.from().qualifiedName()).isEqualTo("file://ds_api/v1/users/email");
        assertThat(edge.maskingApplied()).isTrue();
        assertThat(edge.relation()).isEqualTo("TRANSFORM");
    }

    // ── 映射到现有 LineageService 写入接口 ──────────────────────────────

    @Test
    @DisplayName("toEdgeInputs 可直接交给 LineageService.recordJob")
    void mapsToEdgeInput() {
        rules.put("rule_mask", new RuleInfo("rule_mask", "MASK", Map.of("mode", "HASH")));
        Extraction result = extractor.extract(WS, "OFFLINE_SYNC", offlineConfig(
                orderedMap("a", "b"), Map.of("a", List.of("rule_mask"))));

        List<EdgeInput> inputs = result.toEdgeInputs();

        assertThat(inputs).singleElement().satisfies(input -> {
            assertThat(input.relation()).isEqualTo("TRANSFORM");
            assertThat(input.transformExpr()).isEqualTo("MASK(mode=HASH)");
            assertThat(input.confidence()).isEqualTo(1.0);
            assertThat(input.origin()).isEqualTo("AUTO");
        });
    }

    // ── helpers ────────────────────────────────────────────────────────

    /**
     * 不依赖 Mockito 的替身:模拟「目标 / 源列均未进目录」的退化行为
     * (qualifiedName = db.schema.table.column,忽略空段),数据集与列清单按表名预置。
     */
    // ── EtlPipeline ────────────────────────────────────────────────────

    @Test
    @DisplayName("EtlPipeline:优先使用编译产物里的 lineageEdges(含 TRANSFORM 与脱敏证据)")
    void etlPipelineUsesDeclaredLineageEdges() {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("lineageEdges", List.of(
                Map.of("from", Map.of("dataSourceId", "ds_src", "database", "ods", "schema", "public",
                                "table", "users", "column", "id"),
                        "to", Map.of("dataSourceId", "ds_dst", "database", "dws", "schema", "public",
                                "table", "adults", "column", "id")),
                Map.of("from", Map.of("dataSourceId", "ds_src", "database", "ods", "schema", "public",
                                "table", "users", "column", "phone"),
                        "to", Map.of("dataSourceId", "ds_dst", "database", "dws", "schema", "public",
                                "table", "adults", "column", "phone_md5"),
                        "transformExpr", "md5(phone)", "maskingApplied", true,
                        "maskingEvidence", "function:MD5")));

        Extraction result = extractor.extract(WS, "ETL_PIPELINE", plan);

        assertThat(result.edges()).hasSize(2);
        assertThat(result.edges().get(0).relation()).isEqualTo("COPY");
        assertThat(result.edges().get(0).from().qualifiedName()).isEqualTo("ods.public.users.id");
        EdgeDraft masked = result.edges().get(1);
        assertThat(masked.relation()).isEqualTo("TRANSFORM");
        assertThat(masked.transformExpr()).isEqualTo("md5(phone)");
        assertThat(masked.maskingApplied()).isTrue();
        assertThat(masked.maskingEvidence()).isEqualTo("function:MD5");
    }

    @Test
    @DisplayName("EtlPipeline:无 lineageEdges 时,单源→过滤→单 Sink 且有列映射,回退为 COPY")
    void etlPipelineMinimalShapeFallsBackToCopy() {
        Map<String, Object> plan = Map.of("nodes", List.of(
                Map.of("id", "s", "type", "SOURCE_JDBC", "config", Map.of("dataSourceId", "ds_src",
                        "database", "ods", "schema", "public", "table", "users")),
                Map.of("id", "f", "type", "FILTER"),
                Map.of("id", "k", "type", "SINK_JDBC", "config", Map.of("dataSourceId", "ds_dst",
                        "database", "dws", "schema", "public", "table", "adults",
                        "columnMappings", orderedMap("id", "id", "name", "full_name")))));

        Extraction result = extractor.extract(WS, "etl_pipeline", plan);

        assertThat(result.edges()).hasSize(2);
        assertThat(result.edges().get(1).to().qualifiedName()).isEqualTo("dws.public.adults.full_name");
        assertThat(result.edges().get(1).relation()).isEqualTo("COPY");
    }

    @Test
    @DisplayName("EtlPipeline:含任意 SQL 转换又没有 lineageEdges 时不猜(C8)")
    void etlPipelineDoesNotGuessThroughSqlSteps() {
        Map<String, Object> plan = Map.of("nodes", List.of(
                Map.of("id", "s", "type", "SOURCE_JDBC"),
                Map.of("id", "t", "type", "TRANSFORM_SQL"),
                Map.of("id", "k", "type", "SINK_JDBC", "config", Map.of(
                        "columnMappings", Map.of("a", "b")))));

        Extraction result = extractor.extract(WS, "ETL_PIPELINE", plan);

        assertThat(result.edges()).isEmpty();
        assertThat(result.warnings()).hasSize(1);
    }

    // ── EtlPipeline: Custom SQL 列级补齐 (P1-6) ─────────────────────────

    /** 编译器对 Custom SQL 产出的是表级(无 column)低置信边。 */
    private static Map<String, Object> customSqlPlan(String sql, Map<String, String> columnMapping,
                                                     boolean withTableLevelEdges) {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("kind", "ETL_PIPELINE");
        if (withTableLevelEdges) {
            plan.put("lineageEdges", List.of(Map.of(
                    "relation", "ETL", "confidence", 0.5,
                    "from", Map.of("type", "TABLE", "nodeId", "src", "dataSourceId", "ds_src",
                            "database", "ods", "schema", "public", "table", "users"),
                    "to", Map.of("type", "TABLE", "nodeId", "sink", "dataSourceId", "ds_dst",
                            "database", "dws", "schema", "public", "table", "adults"))));
        }
        plan.put("sinkSpec", orderedObjMap("type", "JDBC", "nodeId", "sink", "dataSourceId", "ds_dst",
                "database", "dws", "schema", "public", "table", "adults",
                "columnMapping", columnMapping));
        plan.put("ir", orderedObjMap(
                "nodes", List.of(
                        orderedObjMap("id", "src", "kind", "SOURCE_JDBC", "config", orderedObjMap(
                                "dataSourceId", "ds_src", "database", "ods", "schema", "public",
                                "table", "users")),
                        orderedObjMap("id", "q", "kind", "TRANSFORM_CUSTOM_SQL",
                                "config", Map.of("sql", sql)),
                        orderedObjMap("id", "sink", "kind", "SINK_JDBC", "config", orderedObjMap(
                                "dataSourceId", "ds_dst", "database", "dws", "schema", "public",
                                "table", "adults"))),
                "edges", List.of(Map.of("from", "src", "to", "q"), Map.of("from", "q", "to", "sink"))));
        return plan;
    }

    private static ColumnEdge columnEdge(String srcTable, String srcColumn, String tgtColumn,
                                         String relation, String dependency, String expr) {
        return new ColumnEdge(srcTable, srcColumn, "__dg_etl_out", tgtColumn, dependency, relation,
                expr, false);
    }

    private static ParseResponse okResponse(ColumnEdge... edges) {
        return new ParseResponse("OK", "hash", List.of(
                new StatementLineage("__dg_etl_out", List.of(edges), List.of())), List.of());
    }

    private StructuredJobLineageExtractor extractorWith(SqlLineageParserClient client) {
        return new StructuredJobLineageExtractor(resolver, (workspaceId, ids) -> Map.of(), client);
    }

    @Test
    @DisplayName("Custom SQL:解析出的列边按 Sink 映射并入结果,{{上游}} 被重写成物理表,表级警告被取代")
    void customSqlEdgesAreMergedIntoExtraction() {
        resolver.datasets.put("users", "catds_users");
        resolver.columns.put("catds_users", List.of("ID", "Name"));
        SqlLineageParserClient client = mock(SqlLineageParserClient.class);
        when(client.parseColumn(any())).thenReturn(okResponse(
                columnEdge("public.users", "name", "full_name", "TRANSFORM", "DIRECT", "upper(name)"),
                columnEdge("public.users", "id", "uid", "COPY", "DIRECT", null),
                columnEdge("public.users", "name", "dropped", "COPY", "DIRECT", null)));

        Extraction result = extractorWith(client).extract(WS, "ETL_PIPELINE", customSqlPlan(
                "SELECT id AS uid, upper(name) AS full_name FROM {{src}};",
                orderedMap("uid", "user_id", "full_name", "full_name"), true));

        assertThat(result.edges()).extracting(e -> e.from().qualifiedName() + " -> "
                        + e.to().qualifiedName() + " " + e.relation())
                .containsExactly("ods.public.users.Name -> dws.public.adults.full_name TRANSFORM",
                        "ods.public.users.ID -> dws.public.adults.user_id COPY");
        assertThat(result.edges().get(0).transformExpr()).isEqualTo("upper(name)");
        assertThat(result.warnings()).noneMatch(w -> w.contains("无法解析的端点"));

        ArgumentCaptor<ParseRequest> captor = ArgumentCaptor.forClass(ParseRequest.class);
        verify(client).parseColumn(captor.capture());
        ParseRequest request = captor.getValue();
        assertThat(request.dialect()).isEqualTo("postgres");
        assertThat(request.sql()).isEqualTo("CREATE TABLE \"__dg_etl_out\" AS "
                + "SELECT id AS uid, upper(name) AS full_name FROM \"public\".\"users\"");
        assertThat(request.catalogSchema()).containsEntry("public.users", List.of("ID", "Name"));
    }

    @Test
    @DisplayName("Custom SQL:编译器已有的 lineageEdges 优先,同一 from→to 不被解析结果覆盖或重复")
    void customSqlKeepsCompilerEdgesAndDoesNotDuplicate() {
        SqlLineageParserClient client = mock(SqlLineageParserClient.class);
        when(client.parseColumn(any())).thenReturn(okResponse(
                columnEdge("public.users", "id", "id", "TRANSFORM", "DIRECT", "id + 1"),
                columnEdge("public.users", "name", "name", "COPY", "DIRECT", null)));
        Map<String, Object> plan = customSqlPlan("SELECT id, name FROM {{src}}",
                orderedMap("id", "id", "name", "name"), false);
        plan.put("lineageEdges", List.of(Map.of(
                "from", Map.of("dataSourceId", "ds_src", "database", "ods", "schema", "public",
                        "table", "users", "column", "id"),
                "to", Map.of("dataSourceId", "ds_dst", "database", "dws", "schema", "public",
                        "table", "adults", "column", "id"))));

        Extraction result = extractorWith(client).extract(WS, "ETL_PIPELINE", plan);

        assertThat(result.edges()).hasSize(2);
        assertThat(result.edges().get(0).relation()).isEqualTo("COPY");
        assertThat(result.edges().get(0).transformExpr()).isNull();
        assertThat(result.edges().get(1).from().qualifiedName()).isEqualTo("ods.public.users.name");
    }

    @Test
    @DisplayName("Custom SQL:没有 lineageEdges 时也能靠解析产出列边;INDIRECT 依赖原样保留")
    void customSqlWithoutDeclaredEdges() {
        SqlLineageParserClient client = mock(SqlLineageParserClient.class);
        when(client.parseColumn(any())).thenReturn(okResponse(
                columnEdge("public.users", "age", "id", "FILTER", "INDIRECT", "age > 18")));

        Extraction result = extractorWith(client).extract(WS, "ETL_PIPELINE", customSqlPlan(
                "SELECT id FROM {{src}} WHERE age > 18", Map.of(), false));

        assertThat(result.edges()).singleElement().satisfies(e -> {
            assertThat(e.dependencyType()).isEqualTo("INDIRECT");
            assertThat(e.relation()).isEqualTo("FILTER");
            assertThat(e.to().qualifiedName()).isEqualTo("dws.public.adults.id");
        });
    }

    @Test
    @DisplayName("Custom SQL:解析 INCONCLUSIVE 只加警告,绝不造边(C8)")
    void customSqlInconclusiveOnlyWarns() {
        SqlLineageParserClient client = mock(SqlLineageParserClient.class);
        when(client.parseColumn(any())).thenReturn(ParseResponse.inconclusive(
                SqlLineageParserClient.CODE_PARSER_UNAVAILABLE, "无法连接血缘解析服务"));

        Extraction result = extractorWith(client).extract(WS, "ETL_PIPELINE", customSqlPlan(
                "SELECT * FROM {{src}}", Map.of(), true));

        assertThat(result.edges()).isEmpty();
        assertThat(result.warnings()).anyMatch(w -> w.contains("INCONCLUSIVE")
                && w.contains("PARSER_UNAVAILABLE"));
    }

    @Test
    @DisplayName("Custom SQL:解析器抛异常 / 未注入解析器 / 上游不是物理表 → 只警告不造边")
    void customSqlDegradesWithoutGuessing() {
        SqlLineageParserClient boom = mock(SqlLineageParserClient.class);
        when(boom.parseColumn(any())).thenThrow(new IllegalStateException("boom"));
        assertThat(extractorWith(boom).extract(WS, "ETL_PIPELINE", customSqlPlan(
                "SELECT id FROM {{src}}", Map.of(), false)).edges()).isEmpty();

        Extraction noClient = extractor.extract(WS, "ETL_PIPELINE", customSqlPlan(
                "SELECT id FROM {{src}}", Map.of(), true));
        assertThat(noClient.edges()).isEmpty();
        assertThat(noClient.warnings()).anyMatch(w -> w.contains("Custom SQL"));

        SqlLineageParserClient client = mock(SqlLineageParserClient.class);
        Map<String, Object> plan = customSqlPlan("SELECT id FROM {{agg}}", Map.of(), false);
        Extraction notPhysical = extractorWith(client).extract(WS, "ETL_PIPELINE", plan);
        assertThat(notPhysical.edges()).isEmpty();
        verify(client, never()).parseColumn(any());
    }

    private static final class FakeResolver extends LineageCatalogResolver {
        final Map<String, String> datasets = new LinkedHashMap<>();
        final Map<String, List<String>> columns = new LinkedHashMap<>();

        FakeResolver() {
            super(null, null, null);
        }

        @Override
        public NodeRef resolveNodeRef(String workspaceId, String datasourceId, String database,
                                      String schema, String table, String columnName) {
            StringBuilder sb = new StringBuilder();
            for (String part : new String[]{database, schema, table, columnName}) {
                if (part != null && !part.isBlank()) {
                    sb.append(sb.length() == 0 ? "" : ".").append(part);
                }
            }
            return new NodeRef("COLUMN", sb.toString(), null, null, columnName);
        }

        @Override
        public Optional<String> resolveDatasetId(String workspaceId, String datasourceId,
                                                 String database, String schema, String table) {
            return Optional.ofNullable(datasets.get(table));
        }

        @Override
        public List<String> listColumnNames(String catalogDatasetId) {
            return columns.getOrDefault(catalogDatasetId, List.of());
        }
    }

    private static Map<String, Object> offlineConfig(Map<String, String> mappings,
                                                     Map<String, List<String>> fieldRules) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("sourceDataSourceId", "ds_src");
        config.put("sourceDatabase", "ods");
        config.put("sourceSchema", "public");
        config.put("sourceTable", "orders");
        config.put("targetDataSourceId", "ds_dst");
        config.put("targetDatabase", "dws");
        config.put("targetSchema", "public");
        config.put("targetTable", "order_sum");
        config.put("fieldMappings", mappings);
        config.put("fieldRules", fieldRules);
        return config;
    }

    private static Map<String, String> orderedMap(String... kv) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put(kv[i], kv[i + 1]);
        }
        return map;
    }

    private static Map<String, Object> orderedObjMap(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }
}
