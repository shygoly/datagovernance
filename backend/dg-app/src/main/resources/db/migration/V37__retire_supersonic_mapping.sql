-- OpenSpec Change: semantic-ontology-kernel (Stage 5)
-- 完全替换 SuperSonic 为原生语义问数引擎后，清理废弃的 SuperSonic 账号映射菜单与权限项。
-- 历史数据表 ig_supersonic_mapping 暂保留作只读归档，不执行破坏性 DROP TABLE。

DELETE FROM pf_role_permission
WHERE permission_code IN ('menu:insight:mappings', 'insight:mapping:read', 'insight:mapping:manage');

DELETE FROM pf_permission
WHERE code IN ('menu:insight:mappings', 'insight:mapping:read', 'insight:mapping:manage');
