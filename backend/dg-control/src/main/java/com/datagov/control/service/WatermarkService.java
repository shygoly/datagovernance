package com.datagov.control.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.common.id.Ids;
import com.datagov.control.entity.EtlEntities.EtlWatermark;
import com.datagov.control.event.ControlEvents;
import com.datagov.control.mapper.EtlWatermarkMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * ETL 增量水位线。
 *
 * <p>三条纪律:
 * <ol>
 *   <li><b>只在 Sink 成功后推进</b> —— 先推进水位再写目标,写失败就永久丢了那一段数据。
 *       调用方(Runner)必须在 Sink 事务提交之后、同一次 Execution 内调用 {@link #advance};</li>
 *   <li><b>只前进不后退</b> —— 并发或重试的旧 Execution 晚到的 advance 不得把水位拉回去
 *       (否则下次抽取会重复一大段);回退只能走显式的 {@link #reset};</li>
 *   <li><b>重置必须有理由</b> —— 重置意味着重抽或漏抽,留痕事件交给装配层落审计。</li>
 * </ol>
 */
@Service
public class WatermarkService {

    private static final Logger log = LoggerFactory.getLogger(WatermarkService.class);

    private final EtlWatermarkMapper mapper;
    private final ApplicationEventPublisher events;

    public WatermarkService(EtlWatermarkMapper mapper, ApplicationEventPublisher events) {
        this.mapper = mapper;
        this.events = events;
    }

    public Optional<EtlWatermark> get(String workspaceId, String jobId, String sourceNodeId) {
        return Optional.ofNullable(mapper.selectOne(new LambdaQueryWrapper<EtlWatermark>()
                .eq(EtlWatermark::getWorkspaceId, workspaceId)
                .eq(EtlWatermark::getJobId, jobId)
                .eq(EtlWatermark::getSourceNodeId, sourceNodeId)));
    }

    public List<EtlWatermark> list(String workspaceId, String jobId) {
        return mapper.selectList(new LambdaQueryWrapper<EtlWatermark>()
                .eq(EtlWatermark::getWorkspaceId, workspaceId)
                .eq(EtlWatermark::getJobId, jobId)
                .orderByAsc(EtlWatermark::getSourceNodeId));
    }

    /**
     * Sink 成功后推进水位线。
     *
     * @return true 表示水位线被推进(或首次建立);false 表示 {@code newValue} 不比现值新而被忽略
     */
    @Transactional
    public boolean advance(String workspaceId, String jobId, String sourceNodeId,
                           String column, String type, String newValue,
                           String executionId, String operatorId) {
        if (newValue == null || newValue.isBlank()) {
            // 本次没有抽到新数据:不动水位。把空值写进去会让下次从头开始
            return false;
        }
        String normalizedType = normalizeType(type);
        EtlWatermark existing = get(workspaceId, jobId, sourceNodeId).orElse(null);
        if (existing == null) {
            try {
                mapper.insert(newRow(workspaceId, jobId, sourceNodeId, column, normalizedType,
                        newValue, executionId, operatorId));
                return true;
            } catch (DuplicateKeyException race) {
                // 并发的另一次执行先建立了行;退化为比较推进
                existing = get(workspaceId, jobId, sourceNodeId).orElseThrow(() -> race);
            }
        }
        if (existing.getLastValue() != null
                && compare(existing.getWatermarkType(), newValue, existing.getLastValue()) <= 0) {
            log.info("水位线未前进,忽略 job={} node={} 现值={} 新值={}",
                    jobId, sourceNodeId, existing.getLastValue(), newValue);
            return false;
        }
        existing.setWatermarkColumn(column);
        existing.setWatermarkType(normalizedType);
        existing.setLastValue(newValue);
        existing.setLastExecutionId(executionId);
        existing.setUpdatedAt(Instant.now());
        existing.setUpdatedBy(operatorId);
        mapper.updateById(existing);
        return true;
    }

    /**
     * 人工重置水位线。
     *
     * @param newValue null 表示清空,下次全量抽取
     * @param note     必填的原因说明
     */
    @Transactional
    public EtlWatermark reset(String workspaceId, String jobId, String sourceNodeId,
                              String newValue, String note, String operatorId) {
        if (note == null || note.isBlank()) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED, "重置水位线必须说明原因");
        }
        EtlWatermark existing = get(workspaceId, jobId, sourceNodeId)
                .orElseThrow(() -> new BizException(ErrorCode.SYS_NOT_FOUND,
                        "节点 " + sourceNodeId + " 还没有水位线,无需重置"));
        String previous = existing.getLastValue();
        existing.setLastValue(newValue == null || newValue.isBlank() ? null : newValue);
        existing.setLastExecutionId(null);
        existing.setUpdatedAt(Instant.now());
        existing.setUpdatedBy(operatorId);
        // updateById 默认跳过 null 字段;清空水位必须显式写 null
        mapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<EtlWatermark>()
                .eq(EtlWatermark::getId, existing.getId())
                .set(EtlWatermark::getLastValue, existing.getLastValue())
                .set(EtlWatermark::getLastExecutionId, null)
                .set(EtlWatermark::getUpdatedAt, existing.getUpdatedAt())
                .set(EtlWatermark::getUpdatedBy, operatorId));
        log.warn("ETL 水位线被重置 ws={} job={} node={} {} -> {} by={} note={}",
                workspaceId, jobId, sourceNodeId, previous, existing.getLastValue(), operatorId, note);
        events.publishEvent(new ControlEvents.EtlWatermarkReset(workspaceId, jobId, sourceNodeId,
                previous, existing.getLastValue(), note, operatorId, Instant.now()));
        return existing;
    }

    // ── 比较 ────────────────────────────────────────────────────────────

    static String normalizeType(String type) {
        String t = type == null ? "STRING" : type.trim().toUpperCase(Locale.ROOT);
        return switch (t) {
            case "TIMESTAMP", "NUMBER", "STRING" -> t;
            default -> "STRING";
        };
    }

    /** 按水位线类型比较两个值的新旧;无法解析时退回字典序。 */
    static int compare(String type, String a, String b) {
        String t = normalizeType(type);
        try {
            if ("NUMBER".equals(t)) {
                return new BigDecimal(a.trim()).compareTo(new BigDecimal(b.trim()));
            }
            if ("TIMESTAMP".equals(t)) {
                return parseInstant(a).compareTo(parseInstant(b));
            }
        } catch (RuntimeException e) {
            // 落到字典序
        }
        return a.compareTo(b);
    }

    private static Instant parseInstant(String raw) {
        String s = raw.trim();
        try {
            return Instant.parse(s);
        } catch (RuntimeException ignored) {
            // next
        }
        try {
            return OffsetDateTime.parse(s).toInstant();
        } catch (RuntimeException ignored) {
            // next
        }
        try {
            return LocalDateTime.parse(s.replace(' ', 'T')).toInstant(ZoneOffset.UTC);
        } catch (RuntimeException ignored) {
            // next
        }
        return LocalDate.parse(s).atStartOfDay().toInstant(ZoneOffset.UTC);
    }

    private static EtlWatermark newRow(String workspaceId, String jobId, String sourceNodeId,
                                       String column, String type, String value,
                                       String executionId, String operatorId) {
        EtlWatermark row = new EtlWatermark();
        row.setId(Ids.of("ewm"));
        row.setWorkspaceId(workspaceId);
        row.setJobId(jobId);
        row.setSourceNodeId(sourceNodeId);
        row.setWatermarkColumn(column);
        row.setWatermarkType(type);
        row.setLastValue(value);
        row.setLastExecutionId(executionId);
        row.setUpdatedAt(Instant.now());
        row.setUpdatedBy(operatorId);
        return row;
    }
}
