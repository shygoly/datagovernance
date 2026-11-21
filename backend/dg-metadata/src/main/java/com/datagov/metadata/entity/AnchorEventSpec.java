package com.datagov.metadata.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/** T₀ 锚点事件规格（逻辑绑定，禁硬编码物理表名）。 */
@Data
@TableName("md_anchor_event_spec")
public class AnchorEventSpec {
    @TableId(type = IdType.INPUT)
    private String id;
    private String workspaceId;
    private String code;
    private String name;
    private String anchorType;
    private String bindingJson;
    private String windowConfigJson;
    private String standardRef;
    private String status;
    private String createdBy;
    private Instant createdAt;
    private Instant updatedAt;
}
