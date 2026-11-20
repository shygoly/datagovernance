package com.datagov.metadata.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datagov.metadata.entity.SemanticEntities.SemanticObject;
import com.datagov.metadata.entity.SemanticEntities.SemanticValueDict;
import com.datagov.metadata.mapper.SemanticObjectMapper;
import com.datagov.metadata.mapper.SemanticValueDictMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 维度值对齐（Value Linking）与确定性相对时间归一化服务（OpenSpec: semantic-ontology-kernel · Stage 3.2）。
 *
 * <p>严格遵守 CONSTITUTION C3/C5：
 * <ul>
 *   <li>仅基于 L1/L2 非敏感安全字典表 {@code md_semantic_value_dict} 进行确定性值对齐；</li>
 *   <li>相对时间表达（昨日/近 N 天/上月/本月/本季/去年）完全在确定性代码中换算，避免 LLM 心算日期出错。</li>
 * </ul>
 */
@Service
public class SemanticValueLinkingService {

    private static final Pattern RECENT_DAYS = Pattern.compile("(?:近|最近|过去)\\s*(\\d{1,3})\\s*天");
    private static final Pattern YEAR_QUARTER = Pattern.compile("(\\d{4})\\s*年?\\s*[Qq第]([1-4])\\s*季?");
    private static final Pattern YEAR_MONTH = Pattern.compile("(\\d{4})\\s*年\\s*(\\d{1,2})\\s*月");

    private final SemanticValueDictMapper valueDictMapper;
    private final SemanticObjectMapper objectMapperDao;
    private final ObjectMapper json;

    public SemanticValueLinkingService(SemanticValueDictMapper valueDictMapper,
                                       SemanticObjectMapper objectMapperDao,
                                       ObjectMapper json) {
        this.valueDictMapper = valueDictMapper;
        this.objectMapperDao = objectMapperDao;
        this.json = json;
    }

    public record ValueMatch(
            String objectId,
            String objectCode,
            String objectName,
            String matchedPhrase,
            String rawValue,
            String displayLabel
    ) {
    }

    public record NormalizedTimeRange(
            String matchedPhrase,
            LocalDate startDate,
            LocalDate endDate
    ) {
    }

    /**
     * 在当前 Workspace 的已发布非敏感对象字典中匹配自然语言问题中的业务枚举值。
     */
    public List<ValueMatch> matchValues(String workspaceId, String question) {
        if (question == null || question.isBlank()) {
            return List.of();
        }
        String qLower = question.toLowerCase(Locale.ROOT);
        List<SemanticObject> published = objectMapperDao.selectList(
                new LambdaQueryWrapper<SemanticObject>()
                        .eq(SemanticObject::getWorkspaceId, workspaceId)
                        .eq(SemanticObject::getStatus, "PUBLISHED"));
        Map<String, SemanticObject> safeObjects = published.stream()
                .filter(o -> !"L3".equalsIgnoreCase(o.getMaxSensitivity())
                        && !"L4".equalsIgnoreCase(o.getMaxSensitivity()))
                .collect(Collectors.toMap(SemanticObject::getId, Function.identity(), (a, b) -> a));
        if (safeObjects.isEmpty()) {
            return List.of();
        }

        List<SemanticValueDict> entries = valueDictMapper.selectList(
                new LambdaQueryWrapper<SemanticValueDict>()
                        .eq(SemanticValueDict::getWorkspaceId, workspaceId)
                        .in(SemanticValueDict::getObjectId, safeObjects.keySet()));

        List<ValueMatch> matches = new ArrayList<>();
        Set<String> dedup = new LinkedHashSet<>();

        for (SemanticValueDict entry : entries) {
            SemanticObject obj = safeObjects.get(entry.getObjectId());
            if (obj == null) {
                continue;
            }
            List<String> phrases = new ArrayList<>();
            phrases.add(entry.getDisplayLabel());
            phrases.add(entry.getRawValue());
            phrases.addAll(parseSynonyms(entry.getSynonymsJson()));
            phrases.sort(Comparator.comparingInt(String::length).reversed());

            for (String p : phrases) {
                if (p == null || p.isBlank() || p.trim().length() < 2) {
                    continue;
                }
                if (qLower.contains(p.trim().toLowerCase(Locale.ROOT))) {
                    String key = obj.getCode() + "=" + entry.getRawValue();
                    if (dedup.add(key)) {
                        matches.add(new ValueMatch(
                                obj.getId(), obj.getCode(), obj.getName(),
                                p.trim(), entry.getRawValue(), entry.getDisplayLabel()));
                    }
                    break;
                }
            }
        }
        return matches;
    }

    /**
     * 确定性相对时间归一化（不依赖 LLM 计算日历）。
     */
    public NormalizedTimeRange normalizeRelativeTime(String question, LocalDate referenceDate) {
        if (question == null || question.isBlank()) {
            return null;
        }
        LocalDate ref = referenceDate == null ? LocalDate.now() : referenceDate;

        Matcher mDays = RECENT_DAYS.matcher(question);
        if (mDays.find()) {
            int days = Math.max(1, Math.min(3650, Integer.parseInt(mDays.group(1))));
            return new NormalizedTimeRange(mDays.group(0), ref.minusDays(days - 1L), ref);
        }
        Matcher mYq = YEAR_QUARTER.matcher(question);
        if (mYq.find()) {
            int year = Integer.parseInt(mYq.group(1));
            int q = Integer.parseInt(mYq.group(2));
            int startMonth = (q - 1) * 3 + 1;
            LocalDate start = LocalDate.of(year, startMonth, 1);
            LocalDate end = YearMonth.of(year, startMonth + 2).atEndOfMonth();
            return new NormalizedTimeRange(mYq.group(0), start, end);
        }
        Matcher mYm = YEAR_MONTH.matcher(question);
        if (mYm.find()) {
            int year = Integer.parseInt(mYm.group(1));
            int month = Math.max(1, Math.min(12, Integer.parseInt(mYm.group(2))));
            YearMonth ym = YearMonth.of(year, month);
            return new NormalizedTimeRange(mYm.group(0), ym.atDay(1), ym.atEndOfMonth());
        }
        if (question.contains("昨天") || question.contains("昨日")) {
            LocalDate y = ref.minusDays(1);
            return new NormalizedTimeRange("昨日", y, y);
        }
        if (question.contains("今天") || question.contains("今日")) {
            return new NormalizedTimeRange("今日", ref, ref);
        }
        if (question.contains("上周")) {
            LocalDate thisMonday = ref.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            LocalDate lastMonday = thisMonday.minusWeeks(1);
            return new NormalizedTimeRange("上周", lastMonday, lastMonday.plusDays(6));
        }
        if (question.contains("本周")) {
            LocalDate thisMonday = ref.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            return new NormalizedTimeRange("本周", thisMonday, ref);
        }
        if (question.contains("上个月") || question.contains("上月")) {
            YearMonth last = YearMonth.from(ref).minusMonths(1);
            return new NormalizedTimeRange("上月", last.atDay(1), last.atEndOfMonth());
        }
        if (question.contains("这个月") || question.contains("本月")) {
            YearMonth cur = YearMonth.from(ref);
            return new NormalizedTimeRange("本月", cur.atDay(1), ref);
        }
        if (question.contains("本季度") || question.contains("本季")) {
            int qStartMonth = ((ref.getMonthValue() - 1) / 3) * 3 + 1;
            return new NormalizedTimeRange("本季", LocalDate.of(ref.getYear(), qStartMonth, 1), ref);
        }
        if (question.contains("去年")) {
            int lastYear = ref.getYear() - 1;
            return new NormalizedTimeRange("去年", LocalDate.of(lastYear, 1, 1), LocalDate.of(lastYear, 12, 31));
        }
        if (question.contains("今年") || question.contains("本年")) {
            return new NormalizedTimeRange("今年", LocalDate.of(ref.getYear(), 1, 1), ref);
        }
        return null;
    }

    private List<String> parseSynonyms(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            return json.readValue(raw, new TypeReference<>() {});
        } catch (Exception e) {
            return List.of();
        }
    }
}
