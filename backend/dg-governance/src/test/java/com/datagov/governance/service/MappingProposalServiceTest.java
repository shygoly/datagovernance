package com.datagov.governance.service;

import com.datagov.common.tenant.Caller;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.governance.entity.MappingProposal;
import com.datagov.governance.mapper.MappingProposalMapper;
import com.datagov.governance.service.MappingProposalService.DecisionRequest;
import com.datagov.governance.service.MappingProposalService.FieldProfile;
import com.datagov.governance.service.MappingProposalService.SuggestRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MappingProposalServiceTest {

    private MappingProposalMapper mapper;
    private MappingProposalService service;

    @BeforeEach
    void setUp() {
        mapper = mock(MappingProposalMapper.class);
        service = new MappingProposalService(mapper, new ObjectMapper());
        WorkspaceContext.set(new Caller("u1", "admin", "ws1", false, Set.of("governance:knowledge:review")));
        when(mapper.selectCount(any())).thenReturn(0L);
    }

    @AfterEach
    void tearDown() {
        WorkspaceContext.clear();
    }

    @Test
    @DisplayName("计费 treat_date 触发 T0 陷阱 → block_and_escalate")
    void billingTrapEscalates() {
        List<MappingProposal> out = service.suggest(new SuggestRequest(
                List.of(new FieldProfile("cath_int", "treat_date", "治疗相关计费日期", "timestamp", 0.0, null, List.of())),
                List.of("semantic://attr.treatment.actual_exec_end_t0", "semantic://attr.treatment.billing_date"),
                null));
        assertThat(out).hasSize(1);
        assertThat(out.getFirst().getRoutingTier()).isEqualTo("block_and_escalate");
        verify(mapper).insert(any(MappingProposal.class));
    }

    @Test
    @DisplayName("驳回后相同指纹被抑制")
    void rejectionSuppressesResubmit() {
        MappingProposal first = service.suggest(new SuggestRequest(
                List.of(new FieldProfile("t", "cath_out_dt", "出导管室实际完成", "timestamp", 0.0, null, List.of())),
                List.of("semantic://attr.treatment.actual_exec_end_t0"),
                null)).getFirst();

        MappingProposal stored = new MappingProposal();
        stored.setId(first.getId());
        stored.setWorkspaceId("ws1");
        stored.setState("PROPOSED");
        stored.setSuggestionFingerprint(first.getSuggestionFingerprint());
        when(mapper.selectById(first.getId())).thenReturn(stored);

        service.reject(first.getId(), new DecisionRequest("计费列勿映射 T0", null));

        when(mapper.selectCount(any())).thenReturn(1L);
        List<MappingProposal> second = service.suggest(new SuggestRequest(
                List.of(new FieldProfile("t", "cath_out_dt", "出导管室实际完成", "timestamp", 0.0, null, List.of())),
                List.of("semantic://attr.treatment.actual_exec_end_t0"),
                null));
        assertThat(second).isEmpty();
    }
}
