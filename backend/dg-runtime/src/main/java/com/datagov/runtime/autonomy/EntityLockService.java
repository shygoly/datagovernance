package com.datagov.runtime.autonomy;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.datagov.common.id.Ids;
import com.datagov.runtime.entity.AutonomyRuntimeEntities.EntityLock;
import com.datagov.runtime.mapper.AuEntityLockMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 实体级在途锁 + 冷却期（防震荡）。
 *
 * <p>唯一索引 {@code (workspace_id, entity_key)} 是最终仲裁者：
 * 即使两个实例同时通过 Arbiter 预检查，也只有一行的条件更新能成功。
 */
@Service
public class EntityLockService {

    private final AuEntityLockMapper mapper;

    public EntityLockService(AuEntityLockMapper mapper) {
        this.mapper = mapper;
    }

    public record LockState(boolean locked, String instanceId, Instant cooldownUntil) {
        public boolean blocked(Instant now) {
            return locked || (cooldownUntil != null && cooldownUntil.isAfter(now));
        }
    }

    public LockState check(String workspaceId, String entityKey) {
        EntityLock row = find(workspaceId, entityKey);
        if (row == null) {
            return new LockState(false, null, null);
        }
        return new LockState(row.getInstanceId() != null, row.getInstanceId(), row.getCooldownUntil());
    }

    @Transactional
    public boolean tryAcquire(String workspaceId, String entityKey, String instanceId, Instant now) {
        EntityLock row = find(workspaceId, entityKey);
        if (row == null) {
            EntityLock created = new EntityLock();
            created.setId(Ids.of("aulk"));
            created.setWorkspaceId(workspaceId);
            created.setEntityKey(entityKey);
            created.setInstanceId(instanceId);
            created.setLockedAt(now);
            created.setUpdatedAt(now);
            try {
                mapper.insert(created);
                return true;
            } catch (DuplicateKeyException race) {
                return false;
            }
        }
        int updated = mapper.update(null, new LambdaUpdateWrapper<EntityLock>()
                .eq(EntityLock::getId, row.getId())
                .isNull(EntityLock::getInstanceId)
                .and(w -> w.isNull(EntityLock::getCooldownUntil)
                        .or().le(EntityLock::getCooldownUntil, now))
                .set(EntityLock::getInstanceId, instanceId)
                .set(EntityLock::getLockedAt, now)
                .set(EntityLock::getCooldownUntil, null)
                .set(EntityLock::getUpdatedAt, now));
        return updated == 1;
    }

    /**
     * 终态释放并进入冷却。冷却期由 FlowSpec 的 cooldownSec 决定，
     * 失败/取消也冷却：否则一个持续失败的策略会被下一次触发立刻重试，形成风暴。
     */
    @Transactional
    public void release(String workspaceId, String entityKey, String instanceId,
                        Instant now, int cooldownSec) {
        mapper.update(null, new LambdaUpdateWrapper<EntityLock>()
                .eq(EntityLock::getWorkspaceId, workspaceId)
                .eq(EntityLock::getEntityKey, entityKey)
                .eq(EntityLock::getInstanceId, instanceId)
                .set(EntityLock::getInstanceId, null)
                .set(EntityLock::getLockedAt, null)
                .set(EntityLock::getCooldownUntil, now.plusSeconds(Math.max(0, cooldownSec)))
                .set(EntityLock::getUpdatedAt, now));
    }

    @Transactional
    public void forceRelease(String workspaceId, String entityKey) {
        mapper.update(null, new LambdaUpdateWrapper<EntityLock>()
                .eq(EntityLock::getWorkspaceId, workspaceId)
                .eq(EntityLock::getEntityKey, entityKey)
                .set(EntityLock::getInstanceId, null)
                .set(EntityLock::getLockedAt, null)
                .set(EntityLock::getCooldownUntil, null)
                .set(EntityLock::getUpdatedAt, Instant.now()));
    }

    private EntityLock find(String workspaceId, String entityKey) {
        return mapper.selectOne(new LambdaQueryWrapper<EntityLock>()
                .eq(EntityLock::getWorkspaceId, workspaceId)
                .eq(EntityLock::getEntityKey, entityKey)
                .last("LIMIT 1"));
    }
}
