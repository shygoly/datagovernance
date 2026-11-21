package com.datagov.app.runner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 坏行台账:C5(摘要里没有 L3/L4 明文)的纯逻辑保证。 */
@DisplayName("ETL 坏行台账")
class EtlBadRowLedgerTest {

    @Test
    @DisplayName("sanitize:抹掉引号内文本、(列)=(值) 片段与数字串 —— 约束冲突消息常回显违规值")
    void sanitizeRemovesEchoedData() {
        assertThat(EtlBadRowLedger.sanitize(
                "ERROR: duplicate key value violates unique constraint \"pk_patient\"\n  Detail: Key (ssn)=(110101199003078888) already exists."))
                .doesNotContain("110101199003078888").doesNotContain("pk_patient").doesNotContain("ssn")
                .contains("duplicate key value");
        assertThat(EtlBadRowLedger.sanitize("invalid input syntax for type integer: 'abc13800138000'"))
                .doesNotContain("abc").doesNotContain("13800138000");
        assertThat(EtlBadRowLedger.sanitize("Duplicate key \"id: 3\" violates primary key constraint"))
                .doesNotContain("id: 3");
        assertThat(EtlBadRowLedger.sanitize(null)).isEmpty();
        assertThat(EtlBadRowLedger.sanitize("x".repeat(500))).hasSizeLessThanOrEqualTo(201);
    }

    @Test
    @DisplayName("category:按 SQLSTATE 类归类;未知 / 缺失为 OTHER")
    void category() {
        assertThat(EtlBadRowLedger.category("23505")).isEqualTo("CONSTRAINT_VIOLATION");
        assertThat(EtlBadRowLedger.category("22P02")).isEqualTo("DATA_TYPE_OR_RANGE");
        assertThat(EtlBadRowLedger.category("42703")).isEqualTo("SCHEMA_MISMATCH");
        assertThat(EtlBadRowLedger.category("08006")).isEqualTo("CONNECTION");
        assertThat(EtlBadRowLedger.category(null)).isEqualTo("OTHER");
        assertThat(EtlBadRowLedger.category("99999")).isEqualTo("OTHER");
    }

    @Test
    @DisplayName("行本体只在死信模式累积;maskColumns 命中列(大小写不敏感)被掩码;captureValues=false 时没有值")
    void bodyMaskingAndCapture() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("ID", 1);
        values.put("Phone", "13800138000");
        values.put("note", null);

        EtlBadRowLedger dead = new EtlBadRowLedger(Set.of("phone"), true, true);
        dead.addSinkError("snk", 7, new SQLException("boom 42", "23505"), values);
        Map<String, Object> row = dead.entries().get(0).bodyRows().get(0);
        assertThat(row).containsEntry("ordinal", 7L).containsEntry("category", "CONSTRAINT_VIOLATION")
                .containsEntry("sqlState", "23505");
        @SuppressWarnings("unchecked")
        Map<String, Object> masked = (Map<String, Object>) row.get("values");
        assertThat(masked).containsEntry("Phone", "***").containsEntry("ID", 1).containsEntry("note", null);

        EtlBadRowLedger noValues = new EtlBadRowLedger(Set.of(), false, true);
        noValues.addSinkError("snk", 0, new SQLException("boom"), values);
        assertThat(noValues.entries().get(0).bodyRows().get(0)).doesNotContainKey("values");

        EtlBadRowLedger skip = new EtlBadRowLedger(Set.of(), true, false);
        skip.addSinkError("snk", 0, new SQLException("boom"), values);
        assertThat(skip.entries().get(0).bodyRows()).as("SKIP 不保留行本体").isEmpty();
        assertThat(skip.total()).isEqualTo(1);
    }

    @Test
    @DisplayName("样例条数封顶;摘要 JSON 不含行内容;死信行数封顶并标记截断")
    void capsAndSummaryJson() {
        EtlBadRowLedger ledger = new EtlBadRowLedger(Set.of(), true, true);
        for (int i = 0; i < EtlBadRowLedger.MAX_BODY_ROWS + 5; i++) {
            ledger.addSinkError("snk", i, new SQLException("e"), Map.of("secret", "S-" + i));
        }
        EtlBadRowLedger.Entry entry = ledger.entries().get(0);
        assertThat(entry.count()).isEqualTo(EtlBadRowLedger.MAX_BODY_ROWS + 5L);
        assertThat(entry.bodyRows()).hasSize(EtlBadRowLedger.MAX_BODY_ROWS);
        assertThat(entry.bodyTruncated()).isTrue();

        String json = ledger.sampleJson(entry, "ROUTE_TO_DEAD_LETTER");
        assertThat(json).contains("\"policy\":\"ROUTE_TO_DEAD_LETTER\"").contains("deadLetterTruncated")
                .doesNotContain("S-0").doesNotContain("secret");
        // 样例只留 MAX_SAMPLES 条
        assertThat(json.split("\"ordinal\"", -1).length - 1).isEqualTo(EtlBadRowLedger.MAX_SAMPLES);
    }

    @Test
    @DisplayName("码表未命中:无敏感标签才带代码样例;计数累加;死信行也走掩码")
    void codesetMisses() {
        EtlBadRowLedger ledger = new EtlBadRowLedger(Set.of("gender"), true, true);
        ledger.addCodesetMisses("cs", "gender_cs", "gender", 3, List.of(), List.of(
                Map.of("id", 1, "gender", "x"), Map.of("id", 2, "gender", "y")));
        EtlBadRowLedger.Entry entry = ledger.entries().get(0);
        assertThat(entry.count()).isEqualTo(3);
        assertThat(ledger.sampleJson(entry, "SKIP_BAD_ROWS")).doesNotContain("distinctMissedCodes");
        assertThat(entry.bodyRows()).hasSize(2);
        assertThat(entry.bodyTruncated()).as("给了 2 行但有 3 个未命中").isTrue();
        @SuppressWarnings("unchecked")
        Map<String, Object> v = (Map<String, Object>) entry.bodyRows().get(0).get("values");
        assertThat(v).containsEntry("gender", "***");

        EtlBadRowLedger open = new EtlBadRowLedger(Set.of(), true, false);
        open.addCodesetMisses("cs", "g", "gender", 2, List.of("x", "y"), List.of());
        assertThat(open.sampleJson(open.entries().get(0), "SKIP_BAD_ROWS"))
                .contains("\"distinctMissedCodes\":[\"x\",\"y\"]");
        assertThat(ledger.countOf("nope")).isZero();
    }
}
