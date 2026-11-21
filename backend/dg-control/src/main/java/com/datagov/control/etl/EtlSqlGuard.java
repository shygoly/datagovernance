package com.datagov.control.etl;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 自定义 SQL / 表达式的静态护栏。
 *
 * <p>DuckDB 在管道里<b>能读本地文件、能 ATTACH 外部库、能装扩展</b>,
 * 所以「只允许 SELECT」远远不够:{@code SELECT * FROM '/etc/passwd'} 是合法 SELECT,
 * {@code SELECT * FROM read_csv('...')} 也是。护栏因此做三件事:
 * <ol>
 *   <li>词法扫描(跳过字符串与带引号标识符),拒绝注释、{@code ;} 拼接多语句、
 *       DML/DDL/COPY/ATTACH/INSTALL/LOAD/PRAGMA 等关键字;</li>
 *   <li>拒绝一切文件 / 外部扫描函数({@code read_*}、{@code *_scan}、{@code glob} 等);</li>
 *   <li>FROM / JOIN 之后只允许 {@code {{nodeId}}} 占位符、子查询或 WITH 里定义的名称 ——
 *       这同时堵死了「FROM '文件路径'」的替换扫描。</li>
 * </ol>
 * 护栏是词法层的第一道防线;Runner 仍须在隔离的 DuckDB 实例里关闭文件系统与扩展自动加载。
 */
public final class EtlSqlGuard {

    private EtlSqlGuard() {
    }

    /**
     * @param problems    问题列表,空表示通过
     * @param rewritten   占位符替换成带引号视图名后的 SQL(去掉末尾分号);有问题时为 null
     * @param placeholders 引用到的节点 id(按出现顺序、去重)
     * @param words       出现过的裸单词(大写),供敏感列传播做粗粒度匹配
     * @param hasStar     是否出现 {@code *}
     */
    public record Result(List<String> problems, String rewritten, Set<String> placeholders,
                         Set<String> words, boolean hasStar) {
        public boolean ok() {
            return problems.isEmpty();
        }
    }

    static final Set<String> FORBIDDEN_WORDS = Set.of(
            "COPY", "ATTACH", "DETACH", "INSTALL", "LOAD", "PRAGMA", "INSERT", "UPDATE", "DELETE",
            "DROP", "CREATE", "ALTER", "TRUNCATE", "MERGE", "EXPORT", "IMPORT", "CALL", "SET", "RESET",
            "USE", "VACUUM", "CHECKPOINT", "EXECUTE", "PREPARE", "DESCRIBE", "SHOW", "SUMMARIZE",
            "EXPLAIN", "BEGIN", "COMMIT", "ROLLBACK", "TRANSACTION", "GRANT", "REVOKE", "FORCE",
            "GLOB", "QUERY", "QUERY_TABLE", "GETENV", "CURRENT_SETTING", "CURRENT_DATABASE",
            "CURRENT_SCHEMA", "CURRENT_SCHEMAS", "SNIFF_CSV", "LOAD_EXTENSION");

    /** 前缀 / 后缀黑名单:覆盖 read_csv、read_parquet、parquet_scan、duckdb_settings、pragma_* 等。 */
    static boolean forbiddenFunctionName(String upperWord) {
        return upperWord.startsWith("READ_") || upperWord.startsWith("DUCKDB_")
                || upperWord.startsWith("PRAGMA_") || upperWord.startsWith("PARQUET_")
                || upperWord.startsWith("HTTP_") || upperWord.startsWith("ICEBERG_")
                || upperWord.startsWith("DELTA_") || upperWord.endsWith("_SCAN");
    }

    private static final Set<String> TABLE_FUNCTION_ALLOW = Set.of("UNNEST", "GENERATE_SERIES", "RANGE");
    private static final Set<String> CLAUSE_END = Set.of("WHERE", "GROUP", "ORDER", "HAVING", "LIMIT",
            "UNION", "INTERSECT", "EXCEPT", "QUALIFY", "WINDOW", "OFFSET", "FETCH", "SELECT");

    private enum T { WORD, STRING, QIDENT, PLACEHOLDER, SYMBOL }

    private record Tok(T type, String text, int start, int end) {
        String upper() {
            return text.toUpperCase(Locale.ROOT);
        }

        boolean sym(String s) {
            return type == T.SYMBOL && text.equals(s);
        }

        boolean word(String w) {
            return type == T.WORD && text.equalsIgnoreCase(w);
        }
    }

    /** 校验并改写一条自定义 SELECT。{@code upstreamIds} 是本节点允许引用的上游节点 id。 */
    public static Result validateSelect(String sql, Set<String> upstreamIds) {
        List<String> problems = new ArrayList<>();
        if (sql == null || sql.isBlank()) {
            problems.add("SQL 为空");
            return new Result(problems, null, Set.of(), Set.of(), false);
        }
        List<Tok> toks = lex(sql, problems);
        // 去掉末尾分号;其余位置的分号是多语句
        while (!toks.isEmpty() && toks.get(toks.size() - 1).sym(";")) {
            toks = toks.subList(0, toks.size() - 1);
        }
        if (toks.isEmpty()) {
            problems.add("SQL 为空");
            return new Result(problems, null, Set.of(), Set.of(), false);
        }

        Set<String> words = new LinkedHashSet<>();
        Set<String> placeholders = new LinkedHashSet<>();
        boolean hasStar = false;

        Tok first = toks.get(0);
        if (!(first.word("SELECT") || first.word("WITH"))) {
            problems.add("只允许单条 SELECT / WITH 查询,当前以「" + first.text + "」开头");
        }

        Set<String> cteNames = collectCteNames(toks);
        scanCommon(toks, problems, words);

        // 表引用状态机
        java.util.ArrayDeque<boolean[]> stack = new java.util.ArrayDeque<>(); // {isSubquery, inFrom}
        stack.push(new boolean[]{true, false});
        boolean firstAfterParen = false;
        boolean expectTable = false;
        for (int i = 0; i < toks.size(); i++) {
            Tok t = toks.get(i);
            if (t.type == T.SYMBOL && t.text.equals("*")) {
                hasStar = true;
            }
            if (t.type == T.PLACEHOLDER) {
                placeholders.add(t.text);
                if (!upstreamIds.contains(t.text)) {
                    problems.add("占位符 {{" + t.text + "}} 不是本节点的上游节点,可引用: "
                            + (upstreamIds.isEmpty() ? "(无)" : String.join(", ", upstreamIds)));
                }
            }

            if (firstAfterParen) {
                firstAfterParen = false;
                stack.peek()[0] = t.word("SELECT") || t.word("WITH");
            }

            if (expectTable) {
                if (t.sym("(")) {
                    // 子查询或括号表引用:留到下一个 token 判定
                } else if (t.word("SELECT") || t.word("WITH") || t.word("LATERAL")) {
                    if (!t.word("LATERAL")) {
                        expectTable = false;
                    }
                } else if (t.type == T.PLACEHOLDER) {
                    expectTable = false;
                } else if ((t.type == T.WORD || t.type == T.QIDENT)
                        && cteNames.contains(t.text.toLowerCase(Locale.ROOT))) {
                    expectTable = false;
                } else if (t.type == T.WORD && TABLE_FUNCTION_ALLOW.contains(t.upper())
                        && i + 1 < toks.size() && toks.get(i + 1).sym("(")) {
                    expectTable = false;
                } else {
                    problems.add("FROM / JOIN 之后只能引用 {{上游节点id}}、子查询或 WITH 里定义的名称,"
                            + "发现「" + t.text + "」");
                    expectTable = false;
                }
            }

            if (t.sym("(")) {
                stack.push(new boolean[]{false, false});
                firstAfterParen = true;
            } else if (t.sym(")")) {
                if (stack.size() > 1) {
                    stack.pop();
                }
            } else if (t.type == T.WORD) {
                String up = t.upper();
                boolean sub = stack.peek()[0];
                if (up.equals("JOIN")) {
                    expectTable = true;
                } else if (up.equals("FROM") && sub) {
                    stack.peek()[1] = true;
                    expectTable = true;
                } else if (sub && CLAUSE_END.contains(up)) {
                    stack.peek()[1] = false;
                }
            } else if (t.sym(",") && stack.peek()[0] && stack.peek()[1]) {
                expectTable = true;
            }
        }

        if (placeholders.isEmpty()) {
            problems.add("SQL 没有通过 {{节点id}} 引用任何上游节点");
        }
        if (!problems.isEmpty()) {
            return new Result(dedupe(problems), null, placeholders, words, hasStar);
        }
        return new Result(List.of(), render(sql, toks), placeholders, words, hasStar);
    }

    /** 表达式里出现的标识符(裸单词与带引号标识符,大写)。解析失败的部分被忽略。 */
    public static Set<String> identifiersOf(String expr) {
        Set<String> words = new LinkedHashSet<>();
        if (expr == null) {
            return words;
        }
        scanCommon(lex(expr, new ArrayList<>()), new ArrayList<>(), words);
        return words;
    }

    /** 校验一个标量 / 布尔表达式(过滤条件、派生列表达式)。 */
    public static List<String> validateExpression(String expr) {
        List<String> problems = new ArrayList<>();
        if (expr == null || expr.isBlank()) {
            problems.add("表达式为空");
            return problems;
        }
        List<Tok> toks = lex(expr, problems);
        Set<String> words = new HashSet<>();
        scanCommon(toks, problems, words);
        boolean prevOpenParen = true; // 表达式起点等同于 "(" 之后
        int depth = 0;
        for (Tok t : toks) {
            if (t.sym(";")) {
                problems.add("表达式里不允许分号");
            }
            if (t.type == T.PLACEHOLDER) {
                problems.add("表达式里不允许 {{节点}} 引用");
            }
            if (t.word("SELECT") || t.word("WITH") || t.word("VALUES")) {
                problems.add("表达式里不允许子查询,需要多表逻辑请用 JOIN 或自定义 SQL 节点");
            }
            if (t.word("FROM") && (prevOpenParen || depth == 0)) {
                problems.add("表达式里不允许 FROM 子句");
            }
            if (t.sym("(")) {
                depth++;
            } else if (t.sym(")")) {
                depth = Math.max(0, depth - 1);
            }
            prevOpenParen = t.sym("(");
        }
        return dedupe(problems);
    }

    // ── 内部 ────────────────────────────────────────────────────────────

    private static void scanCommon(List<Tok> toks, List<String> problems, Set<String> words) {
        for (Tok t : toks) {
            if (t.type == T.QIDENT) {
                // 带引号标识符不参与关键字检查,但要进 words,敏感列传播按列名匹配
                words.add(t.upper());
                continue;
            }
            if (t.type != T.WORD) {
                continue;
            }
            String up = t.upper();
            words.add(up);
            if (FORBIDDEN_WORDS.contains(up)) {
                problems.add("不允许使用 " + up + "(只读 SELECT 管道;若它是列名,请用双引号括起)");
            } else if (forbiddenFunctionName(up)) {
                problems.add("不允许使用文件 / 外部扫描函数 " + t.text);
            }
        }
        // 多语句:任何非末尾分号(末尾的已被调用方去掉;表达式场景另行报)
        for (int i = 0; i < toks.size() - 1; i++) {
            if (toks.get(i).sym(";") && !toks.subList(i + 1, toks.size()).stream().allMatch(x -> x.sym(";"))) {
                problems.add("不允许多条语句(发现分号后仍有内容)");
                break;
            }
        }
    }

    private static Set<String> collectCteNames(List<Tok> toks) {
        Set<String> names = new HashSet<>();
        for (int i = 0; i + 2 < toks.size(); i++) {
            Tok t = toks.get(i);
            if ((t.type == T.WORD || t.type == T.QIDENT) && toks.get(i + 1).word("AS")) {
                Tok after = toks.get(i + 2);
                if (after.sym("(") || after.word("MATERIALIZED") || after.word("NOT")) {
                    names.add(t.text.toLowerCase(Locale.ROOT));
                }
            }
        }
        return names;
    }

    private static List<String> dedupe(List<String> problems) {
        return new ArrayList<>(new LinkedHashSet<>(problems));
    }

    /** 按原文切片输出(保留 >=、::、|| 等多字符运算符的原样),只把占位符替换成带引号视图名。 */
    private static String render(String sql, List<Tok> toks) {
        StringBuilder sb = new StringBuilder();
        int cursor = toks.get(0).start;
        for (Tok t : toks) {
            if (t.type == T.PLACEHOLDER) {
                sb.append(sql, cursor, t.start).append(EtlSql.ident(t.text));
                cursor = t.end;
            }
        }
        sb.append(sql, cursor, toks.get(toks.size() - 1).end);
        return sb.toString();
    }

    private static List<Tok> lex(String sql, List<String> problems) {
        List<Tok> toks = new ArrayList<>();
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            int tokStart = i;
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                problems.add("不允许 SQL 注释(--)");
                return toks;
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                problems.add("不允许 SQL 注释(/* */)");
                return toks;
            } else if (c == '\'') {
                // E'...' 转义字符串里反斜杠会让引号配对与本词法器不一致,直接拒绝
                if (i > 0 && (sql.charAt(i - 1) == 'e' || sql.charAt(i - 1) == 'E')
                        && (i == 1 || !isWordChar(sql.charAt(i - 2)))) {
                    problems.add("不允许 E'...' 转义字符串");
                    return toks;
                }
                StringBuilder sb = new StringBuilder();
                i++;
                boolean closed = false;
                while (i < n) {
                    char d = sql.charAt(i);
                    if (d == '\'') {
                        if (i + 1 < n && sql.charAt(i + 1) == '\'') {
                            sb.append('\'');
                            i += 2;
                            continue;
                        }
                        closed = true;
                        i++;
                        break;
                    }
                    sb.append(d);
                    i++;
                }
                if (!closed) {
                    problems.add("字符串字面量未闭合");
                    return toks;
                }
                toks.add(new Tok(T.STRING, sb.toString(), tokStart, i));
            } else if (c == '"') {
                StringBuilder sb = new StringBuilder();
                i++;
                boolean closed = false;
                while (i < n) {
                    char d = sql.charAt(i);
                    if (d == '"') {
                        if (i + 1 < n && sql.charAt(i + 1) == '"') {
                            sb.append('"');
                            i += 2;
                            continue;
                        }
                        closed = true;
                        i++;
                        break;
                    }
                    sb.append(d);
                    i++;
                }
                if (!closed) {
                    problems.add("带引号的标识符未闭合");
                    return toks;
                }
                toks.add(new Tok(T.QIDENT, sb.toString(), tokStart, i));
            } else if (c == '{' && i + 1 < n && sql.charAt(i + 1) == '{') {
                int end = sql.indexOf("}}", i + 2);
                if (end < 0) {
                    problems.add("占位符 {{ 未闭合");
                    return toks;
                }
                String id = sql.substring(i + 2, end).trim();
                if (!EtlIrParser.NODE_ID.matcher(id).matches()) {
                    problems.add("占位符 id 无效: {{" + id + "}}");
                    return toks;
                }
                i = end + 2;
                toks.add(new Tok(T.PLACEHOLDER, id, tokStart, i));
            } else if (c == '$') {
                problems.add("不允许 $ 参数或美元引用字符串");
                return toks;
            } else if (isWordChar(c)) {
                int start = i;
                while (i < n && isWordChar(sql.charAt(i))) {
                    i++;
                }
                toks.add(new Tok(T.WORD, sql.substring(start, i), start, i));
            } else {
                toks.add(new Tok(T.SYMBOL, String.valueOf(c), i, i + 1));
                i++;
            }
        }
        return toks;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}
