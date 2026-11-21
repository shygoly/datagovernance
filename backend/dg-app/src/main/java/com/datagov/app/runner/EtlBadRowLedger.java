package com.datagov.app.runner;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 一次 ETL 运行的坏行台账:按节点累计坏行数、错误类别样例,以及(死信模式下)行本体。
 *
 * <h3>C5 纪律:摘要里没有 L3/L4 明文</h3>
 * <ul>
 *   <li><b>样例不带行内容</b>:只有行号、错误类别、SQLSTATE 与经 {@link #sanitize} 清洗后的消息
 *       (引号内文本、「(列)=(值)」片段、数字串一律抹掉 —— 数据库的约束冲突消息经常把违规值原样回显);</li>
 *   <li><b>码表未命中样例</b>仅当编译器声明该列「无敏感标签」时才带去重后的代码值;</li>
 *   <li><b>行本体</b>(只进死信存储,不进摘要)里 {@code maskColumns} 命中的列被替换为 {@code ***};
 *       编译器无法确认列集合时 {@code captureValues=false},行里根本没有值。</li>
 * </ul>
 */
public final class EtlBadRowLedger {

    /** 摘要里最多保留几条错误样例。 */
    static final int MAX_SAMPLES = 5;
    /** 死信行本体每个节点最多保留多少行;超出只计数。 */
    static final int MAX_BODY_ROWS = 10_000;
    static final String MASK = "***";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern QUOTED = Pattern.compile("'[^']*'|\"[^\"]*\"");
    private static final Pattern KEY_VALUE = Pattern.compile("\\([^()]*\\)\\s*=\\s*\\([^()]*\\)");
    private static final Pattern DIGITS = Pattern.compile("\\d+");

    /** 单个节点的台账。 */
    public static final class Entry {
        private final String nodeId;
        private long count;
        private final List<Map<String, Object>> samples = new ArrayList<>();
        private final List<Map<String, Object>> body = new ArrayList<>();
        private boolean bodyTruncated;

        Entry(String nodeId) {
            this.nodeId = nodeId;
        }

        public String nodeId() {
            return nodeId;
        }

        public long count() {
            return count;
        }

        public List<Map<String, Object>> bodyRows() {
            return body;
        }

        public boolean bodyTruncated() {
            return bodyTruncated;
        }
    }

    private final Set<String> maskColumnsLower;
    private final boolean captureValues;
    private final boolean keepBody;
    private final Map<String, Entry> byNode = new LinkedHashMap<>();

    /**
     * @param keepBody true(ROUTE_TO_DEAD_LETTER)时才累积行本体
     */
    public EtlBadRowLedger(Set<String> maskColumnsLower, boolean captureValues, boolean keepBody) {
        this.maskColumnsLower = maskColumnsLower == null ? Set.of() : maskColumnsLower;
        this.captureValues = captureValues;
        this.keepBody = keepBody;
    }

    public long total() {
        return byNode.values().stream().mapToLong(e -> e.count).sum();
    }

    public List<Entry> entries() {
        return byNode.values().stream().filter(e -> e.count > 0).toList();
    }

    public long countOf(String nodeId) {
        Entry e = byNode.get(nodeId);
        return e == null ? 0 : e.count;
    }

    private Entry entry(String nodeId) {
        return byNode.computeIfAbsent(nodeId, Entry::new);
    }

    // ── 记录 ────────────────────────────────────────────────────────────

    /**
     * Sink 写入失败的一行。
     *
     * @param values 行内容(列标签 → 值),可为 null;是否保留由 {@code captureValues} 与 {@code keepBody} 决定
     */
    public void addSinkError(String nodeId, long ordinal, SQLException error, Map<String, Object> values) {
        Entry entry = entry(nodeId);
        entry.count++;
        String category = category(error.getSQLState());
        if (entry.samples.size() < MAX_SAMPLES) {
            Map<String, Object> sample = new LinkedHashMap<>();
            sample.put("ordinal", ordinal);
            sample.put("category", category);
            if (error.getSQLState() != null) {
                sample.put("sqlState", error.getSQLState());
            }
            sample.put("message", sanitize(error.getMessage()));
            entry.samples.add(sample);
        }
        if (keepBody) {
            appendBody(entry, ordinal, category, error.getSQLState(), values);
        }
    }

    /**
     * 码表未命中被判为坏行(onMiss=BAD_ROW)的一批。
     *
     * @param distinctCodes 去重后的未命中代码样例;仅当该列无敏感标签时由调用方传入,否则传空
     * @param bodyRows      未命中行的内容(列标签 → 值)样本,可为空
     */
    public void addCodesetMisses(String nodeId, String codesetRef, String column, long misses,
                                 List<String> distinctCodes, List<Map<String, Object>> bodyRows) {
        if (misses <= 0) {
            return;
        }
        Entry entry = entry(nodeId);
        entry.count += misses;
        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("category", "CODESET_MISS");
        sample.put("codeset", codesetRef);
        sample.put("column", column);
        sample.put("rows", misses);
        if (distinctCodes != null && !distinctCodes.isEmpty()) {
            sample.put("distinctMissedCodes", distinctCodes);
        }
        if (entry.samples.size() < MAX_SAMPLES) {
            entry.samples.add(sample);
        }
        if (keepBody) {
            long ordinal = 0;
            for (Map<String, Object> row : bodyRows == null ? List.<Map<String, Object>>of() : bodyRows) {
                appendBody(entry, ordinal++, "CODESET_MISS", null, row);
            }
            if (bodyRows == null || bodyRows.size() < misses) {
                entry.bodyTruncated = true;
            }
        }
    }

    private void appendBody(Entry entry, long ordinal, String category, String sqlState,
                            Map<String, Object> values) {
        if (entry.body.size() >= MAX_BODY_ROWS) {
            entry.bodyTruncated = true;
            return;
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("ordinal", ordinal);
        row.put("category", category);
        if (sqlState != null) {
            row.put("sqlState", sqlState);
        }
        if (captureValues && values != null) {
            row.put("values", mask(values));
        }
        entry.body.add(row);
    }

    /** 命中 maskColumns 的列(大小写不敏感)换成 {@value #MASK};null 保持 null。 */
    Map<String, Object> mask(Map<String, Object> values) {
        Map<String, Object> copy = new LinkedHashMap<>();
        values.forEach((k, v) -> copy.put(k,
                v != null && maskColumnsLower.contains(k.toLowerCase(Locale.ROOT)) ? MASK : v));
        return copy;
    }

    boolean isMasked(String column) {
        return column != null && maskColumnsLower.contains(column.toLowerCase(Locale.ROOT));
    }

    // ── 摘要 ────────────────────────────────────────────────────────────

    /** 写进 {@code etl_bad_row_batch.sample_error_json} 的内容。 */
    public String sampleJson(Entry entry, String policyMode) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("policy", policyMode);
        doc.put("samples", entry.samples);
        if (keepBody) {
            doc.put("deadLetterRows", entry.body.size());
            if (entry.bodyTruncated) {
                doc.put("deadLetterTruncated", true);
            }
        }
        try {
            return JSON.writeValueAsString(doc);
        } catch (JsonProcessingException e) {
            return "{\"policy\":\"" + policyMode + "\",\"samples\":[]}";
        }
    }

    // ── 清洗 ────────────────────────────────────────────────────────────

    /**
     * 抹掉错误消息里可能回显的数据:引号内文本、「(列)=(值)」片段、数字串。
     * 宁可让消息变得不好读,也不让摘要成为 L3/L4 明文的旁路。
     */
    static String sanitize(String message) {
        if (message == null) {
            return "";
        }
        String s = message.replace('\n', ' ').replace('\r', ' ');
        s = KEY_VALUE.matcher(s).replaceAll("(?)=(?)");
        s = QUOTED.matcher(s).replaceAll("?");
        s = DIGITS.matcher(s).replaceAll("#");
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    /** SQLSTATE 类 → 人看得懂的类别。 */
    static String category(String sqlState) {
        if (sqlState == null || sqlState.length() < 2) {
            return "OTHER";
        }
        return switch (sqlState.substring(0, 2)) {
            case "23" -> "CONSTRAINT_VIOLATION";
            case "22" -> "DATA_TYPE_OR_RANGE";
            case "42" -> "SCHEMA_MISMATCH";
            case "08" -> "CONNECTION";
            default -> "OTHER";
        };
    }
}
