package com.datagov.metadata.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.datagov.metadata.entity.SemanticEntities.SemanticObject;
import com.datagov.metadata.entity.SemanticEntities.SemanticValueDict;
import com.datagov.metadata.mapper.SemanticObjectMapper;
import com.datagov.metadata.mapper.SemanticValueDictMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 码表解析与条目展开规则(同义词、键冲突、大小写、可见性)。 */
@DisplayName("SemanticCodesetService")
class SemanticCodesetServiceTest {

    private static final String WS = "ws_1";

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, SemanticObject.class);
        TableInfoHelper.initTableInfo(assistant, SemanticValueDict.class);
    }

    private static SemanticObject object(String id, String ws, String scope, String status) {
        SemanticObject o = new SemanticObject();
        o.setId(id);
        o.setWorkspaceId(ws);
        o.setScope(scope);
        o.setStatus(status);
        o.setCode("gender");
        return o;
    }

    private static SemanticValueDict entry(String raw, String label, String synonymsJson) {
        SemanticValueDict d = new SemanticValueDict();
        d.setId("e_" + raw);
        d.setRawValue(raw);
        d.setDisplayLabel(label);
        d.setSynonymsJson(synonymsJson);
        return d;
    }

    @Test
    @DisplayName("按 id 解析:本空间可见;别的空间的非 GLOBAL 对象不可见;GLOBAL 仅已发布可见")
    void resolveByIdVisibility() {
        SemanticObjectMapper objects = mock(SemanticObjectMapper.class);
        SemanticCodesetService service = new SemanticCodesetService(objects, mock(SemanticValueDictMapper.class));

        when(objects.selectById("own")).thenReturn(object("own", WS, "WORKSPACE", "DRAFT"));
        when(objects.selectById("foreign")).thenReturn(object("foreign", "ws_2", "WORKSPACE", "PUBLISHED"));
        when(objects.selectById("global_ok")).thenReturn(object("global_ok", "ws_2", "GLOBAL", "PUBLISHED"));
        when(objects.selectById("global_draft")).thenReturn(object("global_draft", "ws_2", "GLOBAL", "DRAFT"));
        when(objects.selectList(any())).thenReturn(List.of());

        assertThat(service.resolve(WS, "own")).isPresent();
        assertThat(service.resolve(WS, "foreign")).isEmpty();
        assertThat(service.resolve(WS, "global_ok")).isPresent();
        assertThat(service.resolve(WS, "global_draft")).isEmpty();
        assertThat(service.resolve(WS, " ")).isEmpty();
        assertThat(service.resolve(null, "own")).isEmpty();
    }

    @Test
    @DisplayName("按 code 解析:本空间覆盖 GLOBAL;同级先取已发布;已过期(valid_to)的忽略")
    void resolveByCodePrecedence() {
        SemanticObjectMapper objects = mock(SemanticObjectMapper.class);
        SemanticCodesetService service = new SemanticCodesetService(objects, mock(SemanticValueDictMapper.class));
        when(objects.selectById(any())).thenReturn(null);

        SemanticObject global = object("g", "ws_2", "GLOBAL", "PUBLISHED");
        SemanticObject ownDraft = object("own_draft", WS, "WORKSPACE", "DRAFT");
        SemanticObject ownPublished = object("own_pub", WS, "WORKSPACE", "PUBLISHED");
        SemanticObject expired = object("expired", WS, "WORKSPACE", "PUBLISHED");
        expired.setValidTo(Instant.now().minusSeconds(60));

        when(objects.selectList(any())).thenReturn(List.of(global, ownDraft, ownPublished, expired));
        assertThat(service.resolve(WS, "gender")).get().extracting(SemanticObject::getId).isEqualTo("own_pub");

        when(objects.selectList(any())).thenReturn(List.of(global, ownDraft));
        assertThat(service.resolve(WS, "gender")).get().extracting(SemanticObject::getId).isEqualTo("own_draft");

        when(objects.selectList(any())).thenReturn(List.of(global));
        assertThat(service.resolve(WS, "gender")).get().extracting(SemanticObject::getId).isEqualTo("g");
    }

    @Test
    @DisplayName("条目数:其它空间的 GLOBAL 码集返回 -1(未知),不是 0")
    void entryCountUnknownForForeignGlobal() {
        SemanticValueDictMapper dict = mock(SemanticValueDictMapper.class);
        when(dict.selectCount(any())).thenReturn(7L);
        SemanticCodesetService service = new SemanticCodesetService(mock(SemanticObjectMapper.class), dict);

        assertThat(service.entryCount(WS, object("own", WS, "WORKSPACE", "PUBLISHED"))).isEqualTo(7);
        assertThat(service.entryCount(WS, object("g", "ws_2", "GLOBAL", "PUBLISHED"))).isEqualTo(-1);
    }

    @Test
    @DisplayName("loadMapping:同义词归一到 raw_value 或展开为标签;显式 raw_value 优先于同义词;ignoreCase 转小写")
    void loadMapping() {
        SemanticValueDictMapper dict = mock(SemanticValueDictMapper.class);
        when(dict.selectList(any())).thenReturn(List.of(
                entry("F", "女", "[\"female\",\"女性\",\"M\"]"),   // 同义词 "M" 与下一条的 raw_value 冲突
                entry("M", "男", "[\"male\",\"男性\"]"),
                entry("U", "未知", "not-json"),                     // 坏 JSON:忽略同义词,不抛
                entry("O", "其他", null)));
        SemanticCodesetService service = new SemanticCodesetService(mock(SemanticObjectMapper.class), dict);

        Map<String, String> canonical = service.loadMapping("o", SemanticCodesetService.MAP_TO_CANONICAL, false);
        assertThat(canonical).containsEntry("M", "M").containsEntry("male", "M").containsEntry("男性", "M")
                .containsEntry("female", "F").containsEntry("F", "F").containsEntry("U", "U").containsEntry("O", "O");

        Map<String, String> labels = service.loadMapping("o", SemanticCodesetService.MAP_TO_LABEL, false);
        assertThat(labels).containsEntry("male", "男").containsEntry("female", "女").containsEntry("M", "男");

        Map<String, String> lower = service.loadMapping("o", SemanticCodesetService.MAP_TO_CANONICAL, true);
        assertThat(lower).containsEntry("m", "M").containsEntry("female", "F").doesNotContainKey("F");
    }
}
