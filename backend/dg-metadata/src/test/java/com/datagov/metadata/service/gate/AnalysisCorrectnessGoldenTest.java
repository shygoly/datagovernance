package com.datagov.metadata.service.gate;

import com.datagov.metadata.entity.SemanticEntities.SemanticObject;
import com.datagov.metadata.service.SemanticQueryCompiler.SemanticQuerySpec;
import com.datagov.metadata.service.SemanticQueryCompiler.TimeRange;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 加载 {@code evals/analysis-correctness/golden/*.json}（W3 ≥6 条）。
 */
@DisplayName("W3 Analysis Correctness Golden")
class AnalysisCorrectnessGoldenTest {

    private final ObjectMapper json = new ObjectMapper();
    private final NullAsZeroGuard nullGuard = new NullAsZeroGuard();
    private final SemanticGateService gate = new SemanticGateService(json);

    @Test
    @DisplayName("执行 golden 目录全部用例")
    void runsAllGoldenCases() throws Exception {
        Path dir = resolveGoldenDir();
        assertThat(dir).exists();
        try (Stream<Path> stream = Files.list(dir)) {
            List<Path> files = stream.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList();
            assertThat(files.size()).isGreaterThanOrEqualTo(10);
            for (Path file : files) {
                JsonNode caseNode = json.readTree(file.toFile());
                runCase(caseNode);
            }
        }
    }

    private void runCase(JsonNode c) {
        String kind = c.path("kind").asText();
        switch (kind) {
            case "null_as_zero" -> {
                String expr = c.path("expression").asText();
                boolean allow = c.path("null_as_zero").asBoolean(false);
                String expect = textOrNull(c.get("expect_error"));
                if (expect == null) {
                    assertThatCode(() -> nullGuard.assertAllowed(expr, allow)).doesNotThrowAnyException();
                } else {
                    assertThatThrownBy(() -> nullGuard.assertAllowed(expr, allow))
                            .isInstanceOf(CompileRejectException.class)
                            .satisfies(ex -> assertThat(((CompileRejectException) ex).reject().errorCode()).isEqualTo(expect));
                }
            }
            case "gate" -> {
                SemanticGateRequest req = buildGateRequest(c);
                String expect = textOrNull(c.get("expect_error"));
                assertThatThrownBy(() -> gate.evaluate(req))
                        .isInstanceOf(CompileRejectException.class)
                        .satisfies(ex -> assertThat(((CompileRejectException) ex).reject().errorCode()).isEqualTo(expect));
            }
            case "gate_pass" -> {
                SemanticGateRequest req = buildGateRequest(c);
                SemanticGatePass pass = gate.evaluate(req);
                assertThat(pass.truncated()).isEqualTo(c.path("expect_truncated").asBoolean());
                assertThat(pass.effectiveLimit()).isEqualTo(c.path("expect_effective_limit").asInt());
            }
            case "sql_row" -> runSqlRowCase(c);
            default -> throw new IllegalArgumentException("unknown golden kind: " + kind);
        }
    }

    /** 行级比对（默认 H2 内存库；可选指向真实 PG）。 */
    private void runSqlRowCase(JsonNode c) {
        String url = System.getenv().getOrDefault("DG_GOLDEN_JDBC_URL",
                "jdbc:h2:mem:golden_" + c.path("id").asText("x") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        String user = System.getenv().getOrDefault("DG_GOLDEN_JDBC_USER", "sa");
        String pass = System.getenv().getOrDefault("DG_GOLDEN_JDBC_PASSWORD", "");
        try (Connection conn = DriverManager.getConnection(url, user, pass);
             Statement st = conn.createStatement()) {
            if (c.has("setup_sql") && c.get("setup_sql").isArray()) {
                for (JsonNode s : c.get("setup_sql")) {
                    st.execute(s.asText());
                }
            }
            String sql = c.path("sql").asText();
            try (ResultSet rs = st.executeQuery(sql)) {
                assertThat(rs.next()).isTrue();
                String cell = rs.getString(1);
                if (c.has("expect_cell")) {
                    assertThat(cell).isEqualToIgnoringCase(c.path("expect_cell").asText());
                }
                if (c.has("expect_cell_min")) {
                    assertThat(Double.parseDouble(cell)).isGreaterThanOrEqualTo(c.path("expect_cell_min").asDouble());
                }
            }
        } catch (Exception ex) {
            throw new AssertionError("sql_row golden failed: " + c.path("id").asText(), ex);
        }
    }

    private SemanticGateRequest buildGateRequest(JsonNode c) {
        boolean emptyMetrics = c.has("metrics") && c.get("metrics").isArray() && c.get("metrics").isEmpty();
        List<SemanticObject> metrics = new java.util.ArrayList<>();
        if (!emptyMetrics) {
            SemanticObject metric = new SemanticObject();
            metric.setId("m1");
            metric.setCode(c.path("metric_code").asText("metric.gmv"));
            metric.setObjectType("METRIC");
            metric.setStatus("PUBLISHED");
            if (c.has("metric_standard_ref")) {
                String ref = c.get("metric_standard_ref").asText("");
                metric.setStandardRef(ref.isBlank() ? null : ref);
            } else {
                metric.setStandardRef("STD-1");
            }
            if (c.has("metric_spec_json")) {
                metric.setSpecJson(c.path("metric_spec_json").asText("{}"));
            } else if (c.has("metric_spec")) {
                metric.setSpecJson(c.get("metric_spec").toString());
            } else {
                metric.setSpecJson("{}");
            }
            metrics.add(metric);
        }

        List<SemanticObject> dims = new java.util.ArrayList<>();
        List<String> dimCodes = new java.util.ArrayList<>();
        if (c.has("dimensions") && c.get("dimensions").isArray()) {
            int i = 0;
            for (JsonNode d : c.get("dimensions")) {
                String code = d.asText();
                dimCodes.add(code);
                SemanticObject dim = new SemanticObject();
                dim.setId("d" + (i++));
                dim.setCode(code);
                dim.setObjectType("ATTRIBUTE");
                dim.setStatus("PUBLISHED");
                dims.add(dim);
            }
        }

        Integer limit = c.has("limit") ? c.get("limit").asInt() : 10;
        TimeRange time = null;
        if (c.has("time_start") || c.has("time_end")) {
            time = new TimeRange("dim.date",
                    textOrNull(c.get("time_start")),
                    textOrNull(c.get("time_end")));
        }
        List<String> metricCodes = emptyMetrics ? List.of()
                : List.of(metrics.get(0).getCode());
        SemanticQuerySpec spec = new SemanticQuerySpec(
                null, metricCodes, dimCodes, List.of(), time, null, limit);

        Set<String> allowed = c.has("allowed_datasets")
                ? json.convertValue(c.get("allowed_datasets"), json.getTypeFactory().constructCollectionType(Set.class, String.class))
                : Set.of();
        Set<String> required = c.has("required_datasets")
                ? json.convertValue(c.get("required_datasets"), json.getTypeFactory().constructCollectionType(Set.class, String.class))
                : Set.of();
        int maxRows = c.has("max_rows") ? c.get("max_rows").asInt() : 200;
        String grain = textOrNull(c.get("grain"));

        return new SemanticGateRequest(
                "ws_1", SemanticExecutionMode.SINGLE, spec, metrics, dims,
                grain, allowed, maxRows, required);
    }

    private static String textOrNull(JsonNode n) {
        return n == null || n.isNull() ? null : n.asText();
    }

    private static Path resolveGoldenDir() {
        Path cwd = Path.of("").toAbsolutePath();
        Path[] candidates = {
                cwd.resolve("evals/analysis-correctness/golden"),
                cwd.resolve("../evals/analysis-correctness/golden"),
                cwd.resolve("../../evals/analysis-correctness/golden"),
                cwd.resolve("../../../evals/analysis-correctness/golden")
        };
        for (Path p : candidates) {
            if (Files.isDirectory(p)) {
                return p.normalize();
            }
        }
        throw new IllegalStateException("找不到 evals/analysis-correctness/golden，cwd=" + cwd);
    }
}
