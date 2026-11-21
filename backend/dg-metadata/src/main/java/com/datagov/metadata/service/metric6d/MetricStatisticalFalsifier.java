package com.datagov.metadata.service.metric6d;

import com.datagov.data.spi.query.ReadOnlySqlGuard;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * ③b 统计证伪 SQL 生成器（只读；经 DataAccessGateway 执行由调用方负责）。
 */
@Service
public class MetricStatisticalFalsifier {

    public record FalsifyQuery(String id, String sql, String assertion) {
    }

    public record TableBinding(String table, String pkColumn, String fkColumn) {
    }

    public List<FalsifyQuery> buildQueries(TableBinding parent, TableBinding child, double fkMatchThreshold) {
        if (parent == null || child == null) {
            throw new IllegalArgumentException("parent/child 绑定不能为空");
        }
        double thr = fkMatchThreshold <= 0 ? 0.95 : fkMatchThreshold;
        List<FalsifyQuery> out = new ArrayList<>();
        out.add(new FalsifyQuery(
                "PK_UNIQUENESS",
                ReadOnlySqlGuard.requireReadOnly(
                        "SELECT COUNT(*) = COUNT(DISTINCT " + ident(parent.pkColumn()) + ") AS pk_is_unique FROM "
                                + ident(parent.table())),
                "pk_is_unique == true"));
        out.add(new FalsifyQuery(
                "NDV_RATIO_1N",
                ReadOnlySqlGuard.requireReadOnly(
                        "SELECT CAST(COUNT(DISTINCT " + ident(child.fkColumn()) + ") AS DOUBLE) / NULLIF(COUNT(*), 0) AS ndv_ratio FROM "
                                + ident(child.table())),
                "0 < ndv_ratio < 1.0"));
        out.add(new FalsifyQuery(
                "FK_MATCH_RATE",
                ReadOnlySqlGuard.requireReadOnly(
                        "SELECT AVG(CASE WHEN p." + ident(parent.pkColumn()) + " IS NOT NULL THEN 1.0 ELSE 0.0 END) AS fk_match_rate "
                                + "FROM " + ident(child.table()) + " c LEFT JOIN " + ident(parent.table())
                                + " p ON c." + ident(child.fkColumn()) + " = p." + ident(parent.pkColumn())),
                "fk_match_rate >= " + thr));
        return out;
    }

    private static String ident(String raw) {
        if (raw == null || !raw.matches("^[A-Za-z_][A-Za-z0-9_]*$")) {
            throw new IllegalArgumentException("非法标识符: " + raw);
        }
        return raw.toLowerCase(Locale.ROOT).equals(raw) || raw.matches("^[A-Za-z_][A-Za-z0-9_]*$") ? raw : raw;
    }
}
