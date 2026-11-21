package com.datagov.control.etl;

/** DuckDB SQL 拼装的转义小工具。所有用户提供的标识符 / 字面量都必须经过这里。 */
public final class EtlSql {

    private EtlSql() {
    }

    /** 标识符加双引号并转义内部双引号。 */
    public static String ident(String name) {
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }

    /** 字符串字面量。 */
    public static String literal(String value) {
        return "'" + value.replace("'", "''") + "'";
    }
}
