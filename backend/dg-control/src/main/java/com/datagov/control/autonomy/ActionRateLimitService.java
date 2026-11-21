package com.datagov.control.autonomy;

import com.datagov.common.id.Ids;
import com.datagov.control.mapper.AuActionCounterMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * ActionType {@code paramEnvelope.maxExecutionsPerDay} 的跨实例日额度（P1-4）。
 *
 * <p>先原子 UPSERT 再读计数；超限时回退本次自增，避免被拒绝的尝试挤占额度。
 * 计数只覆盖真实副作用执行（Dry-Run / SHADOW / L0 不算）；执行失败时回退。
 */
@Service
public class ActionRateLimitService {

    private final AuActionCounterMapper mapper;

    public ActionRateLimitService(AuActionCounterMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional
    public boolean tryConsume(String workspaceId, String actionCode, int maxPerDay) {
        if (maxPerDay <= 0) {
            return true;
        }
        LocalDate day = LocalDate.now(ZoneOffset.UTC);
        mapper.increment(Ids.of("aec"), workspaceId, actionCode, day);
        Integer count = mapper.currentCount(workspaceId, actionCode, day);
        if (count != null && count > maxPerDay) {
            mapper.decrement(workspaceId, actionCode, day);
            return false;
        }
        return true;
    }

    /** 执行失败/未真正产生副作用时回退额度。 */
    @Transactional
    public void release(String workspaceId, String actionCode) {
        mapper.decrement(workspaceId, actionCode, LocalDate.now(ZoneOffset.UTC));
    }

    public int currentCount(String workspaceId, String actionCode) {
        Integer count = mapper.currentCount(workspaceId, actionCode, LocalDate.now(ZoneOffset.UTC));
        return count == null ? 0 : count;
    }
}
