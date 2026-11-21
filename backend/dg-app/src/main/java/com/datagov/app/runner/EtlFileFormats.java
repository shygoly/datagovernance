package com.datagov.app.runner;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * SOURCE_FILE 的 DuckDB 读取表达式构造。
 *
 * <p>路径是引擎自己创建的临时文件(或 S3 位置),用单引号字面量转义拼进去;
 * 用户可控的只有白名单内的 {@code options}(分隔符 / 表头 / 编码),取值都经过校验,
 * 绝不把用户字符串原样拼进 SQL。
 */
final class EtlFileFormats {

    static final Set<String> FORMATS = Set.of("CSV", "JSON", "NDJSON", "PARQUET");
    private static final Set<String> ENCODINGS = Set.of("utf-8", "utf-16", "latin-1");

    private EtlFileFormats() {
    }

    static String normalize(String format) {
        String f = format == null ? "" : format.trim().toUpperCase(Locale.ROOT);
        if (!FORMATS.contains(f)) {
            throw new IllegalArgumentException("不支持的文件格式: " + format
                    + "(可选 " + String.join(" / ", new java.util.TreeSet<>(FORMATS)) + ")");
        }
        return f;
    }

    /** 形如 {@code read_csv('/tmp/x.csv', header=true)} 的表函数调用。 */
    static String readExpression(String format, String path, Map<String, Object> options) {
        String literal = literal(path);
        return switch (normalize(format)) {
            case "CSV" -> {
                List<String> args = new ArrayList<>();
                args.add(literal);
                Object delimiter = first(options, "delimiter", "delim");
                if (delimiter != null) {
                    String d = String.valueOf(delimiter);
                    if (d.codePointCount(0, d.length()) != 1 || d.equals("\n") || d.equals("\r")
                            || d.equals("\"") || d.equals("'")) {
                        throw new IllegalArgumentException("CSV 分隔符必须是单个字符(且不能是引号或换行): " + d);
                    }
                    args.add("delim=" + literal(d));
                }
                Object header = first(options, "header", "hasHeader");
                if (header != null) {
                    args.add("header=" + bool(header, "header"));
                }
                Object encoding = options == null ? null : options.get("encoding");
                if (encoding != null) {
                    String e = String.valueOf(encoding).toLowerCase(Locale.ROOT);
                    if (!ENCODINGS.contains(e)) {
                        throw new IllegalArgumentException("不支持的 CSV 编码: " + encoding
                                + "(可选 utf-8 / utf-16 / latin-1)");
                    }
                    args.add("encoding=" + literal(e));
                }
                yield "read_csv(" + String.join(", ", args) + ")";
            }
            case "JSON" -> "read_json_auto(" + literal + ")";
            case "NDJSON" -> "read_json_auto(" + literal + ", format='newline_delimited')";
            default -> "read_parquet(" + literal + ")";
        };
    }

    static String literal(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    /** DuckDB COPY 的压缩参数值;NONE → uncompressed。 */
    static String parquetCompression(String compression) {
        String c = compression == null ? "SNAPPY" : compression.trim().toUpperCase(Locale.ROOT);
        return switch (c) {
            case "SNAPPY" -> "snappy";
            case "ZSTD" -> "zstd";
            case "GZIP" -> "gzip";
            case "NONE" -> "uncompressed";
            default -> throw new IllegalArgumentException("不支持的压缩格式: " + compression);
        };
    }

    private static Object first(Map<String, Object> options, String... keys) {
        if (options == null) {
            return null;
        }
        for (String key : keys) {
            if (options.get(key) != null) {
                return options.get(key);
            }
        }
        return null;
    }

    private static String bool(Object value, String name) {
        String s = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        if (!s.equals("true") && !s.equals("false")) {
            throw new IllegalArgumentException("选项 " + name + " 必须是 true / false: " + value);
        }
        return s;
    }
}
