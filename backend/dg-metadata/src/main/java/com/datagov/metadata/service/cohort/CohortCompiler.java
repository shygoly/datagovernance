package com.datagov.metadata.service.cohort;

import com.datagov.common.clinical.CohortSpec;
import com.datagov.common.clinical.CohortSpecValidator;
import com.datagov.common.clinical.CriterionNode;
import com.datagov.data.spi.query.ReadOnlySqlGuard;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 将 CohortSpec 编译为确定性成员集 SQL（EXISTS/NOT_EXISTS 子集 + 锚点相对窗）。
 * 不实现临床评分；禁止 Criterion 裸 SQL。
 */
@Service
public class CohortCompiler {

    public record CompiledCohort(
            String sql,
            String memberGrain,
            List<String> warnings,
            String digest
    ) {
    }

    public CompiledCohort compile(CohortSpec spec, List<EventTableBinding> bindings) {
        CohortSpecValidator.validate(spec);
        Map<String, EventTableBinding> byEvent = new LinkedHashMap<>();
        for (EventTableBinding b : bindings == null ? List.<EventTableBinding>of() : bindings) {
            if (b != null && b.eventCode() != null) {
                byEvent.put(b.eventCode().toLowerCase(Locale.ROOT), b);
            }
        }
        EventTableBinding anchor = requireBinding(byEvent, spec.anchor().event(), "anchor");

        List<String> warnings = new ArrayList<>();
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT DISTINCT a.").append(ident(anchor.memberColumn()))
                .append(" AS member_id FROM ").append(ident(anchor.tableName())).append(" a")
                .append(" WHERE 1=1");

        // 锚点相对时间窗：限制锚点事件自身时间跨度由调用方物化窗控制；此处对纳入事件使用相对窗
        for (CriterionNode node : spec.include()) {
            sql.append(" AND ").append(renderPredicate(node, byEvent, anchor, spec, true, warnings));
        }
        for (CriterionNode node : spec.exclude()) {
            sql.append(" AND NOT (").append(renderPredicate(node, byEvent, anchor, spec, false, warnings)).append(")");
        }

        String safe = ReadOnlySqlGuard.requireReadOnly(sql.toString());
        String digest = sha256(spec.code() + "|" + safe);
        return new CompiledCohort(safe, spec.memberGrain(), warnings, digest);
    }

    private String renderPredicate(CriterionNode node,
                                   Map<String, EventTableBinding> byEvent,
                                   EventTableBinding anchor,
                                   CohortSpec spec,
                                   boolean includeSide,
                                   List<String> warnings) {
        return switch (node.type()) {
            case "AND" -> "(" + String.join(" AND ",
                    node.children().stream()
                            .map(c -> renderPredicate(c, byEvent, anchor, spec, includeSide, warnings))
                            .toList()) + ")";
            case "OR" -> "(" + String.join(" OR ",
                    node.children().stream()
                            .map(c -> renderPredicate(c, byEvent, anchor, spec, includeSide, warnings))
                            .toList()) + ")";
            case "NOT" -> {
                if (node.children().size() != 1) {
                    throw new IllegalArgumentException("NOT 节点必须恰好 1 个子节点");
                }
                yield "NOT (" + renderPredicate(node.children().get(0), byEvent, anchor, spec, includeSide, warnings) + ")";
            }
            case "EXISTS", "FIRST", "LAST", "TIME_WITHIN" ->
                    existsClause(node, byEvent, anchor, spec, false);
            case "NOT_EXISTS" -> existsClause(node, byEvent, anchor, spec, true);
            case "SEQUENCE" -> {
                warnings.add("SEQUENCE 在 v0 降级为按 children 顺序的 EXISTS 合取；完整序列语义后续版本补齐");
                yield "(" + String.join(" AND ",
                        node.children().stream()
                                .map(c -> renderPredicate(c, byEvent, anchor, spec, includeSide, warnings))
                                .toList()) + ")";
            }
            default -> throw new IllegalArgumentException("不支持的 Criterion type: " + node.type());
        };
    }

    private String existsClause(CriterionNode node,
                                Map<String, EventTableBinding> byEvent,
                                EventTableBinding anchor,
                                CohortSpec spec,
                                boolean negated) {
        EventTableBinding ev = requireBinding(byEvent, node.event(), "criterion");
        String alias = "e_" + sanitize(node.event());
        List<String> window = node.window().isEmpty()
                ? List.of(spec.window().startOffset(), spec.window().endOffset())
                : node.window();
        if (window.size() != 2) {
            throw new IllegalArgumentException("window 必须为 [startOffset, endOffset] ISO-8601 时长对");
        }
        String start = isoInterval(window.get(0));
        String end = isoInterval(window.get(1));
        String body = "EXISTS (SELECT 1 FROM " + ident(ev.tableName()) + " " + alias
                + " WHERE " + alias + "." + ident(ev.memberColumn())
                + " = a." + ident(anchor.memberColumn())
                + " AND " + alias + "." + ident(ev.timeColumn())
                + " >= a." + ident(anchor.timeColumn()) + " + INTERVAL '" + start + "'"
                + " AND " + alias + "." + ident(ev.timeColumn())
                + " < a." + ident(anchor.timeColumn()) + " + INTERVAL '" + end + "')";
        return negated ? "NOT " + body : body;
    }

    private EventTableBinding requireBinding(Map<String, EventTableBinding> byEvent, String event, String ctx) {
        EventTableBinding b = byEvent.get(event.toLowerCase(Locale.ROOT));
        if (b == null) {
            throw new IllegalArgumentException(ctx + " 事件未绑定物理表: " + event);
        }
        return b;
    }

    private static String isoInterval(String offset) {
        // 接受 PT48H / P2D 等；编译为 PostgreSQL interval 字面量（已拒 SQL 注入）
        String v = Objects.requireNonNull(offset).trim();
        if (!v.matches("^[Pp]([0-9]+[Dd])?(T([0-9]+[Hh])?([0-9]+[Mm])?([0-9]+(\\.[0-9]+)?[Ss])?)?$")
                && !v.matches("^[0-9]+\\s+(hour|hours|day|days|minute|minutes)$")) {
            // 允许简化 "48 hours"
            if (!v.matches("^[A-Za-z0-9:.]+$")) {
                throw new IllegalArgumentException("非法时间偏移: " + offset);
            }
        }
        return v.replace("'", "");
    }

    private static String ident(String raw) {
        if (raw == null || !raw.matches("^[A-Za-z_][A-Za-z0-9_]*$")) {
            throw new IllegalArgumentException("非法标识符: " + raw);
        }
        return raw;
    }

    private static String sanitize(String code) {
        return code.replaceAll("[^A-Za-z0-9_]", "_").toLowerCase(Locale.ROOT);
    }

    private static String sha256(String input) {
        try {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : dig) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
