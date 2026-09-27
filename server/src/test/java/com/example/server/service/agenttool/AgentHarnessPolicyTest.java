package com.example.server.service.agenttool;

import com.example.server.dto.VideoContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentHarnessPolicyTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final VideoContext context = new VideoContext(
            "video", "goal", List.of(
            new VideoContext.VideoSegment(0, 60_000, "text", List.of(), List.of())));

    @Test
    void requiresSearchAsFirstToolAndKeepsTimestampInsideVideo() throws Exception {
        AgentHarnessPolicy policy = new AgentHarnessPolicy(4, 2, 100, 30);
        AgentHarnessPolicy.Session session = policy.open(context);

        assertThrows(AgentHarnessPolicy.PolicyViolationException.class,
                () -> session.authorize("inspectFrame", mapper.readTree("{\"timestampMs\":1000}")));
        assertDoesNotThrow(() -> session.authorize(
                "searchEvidence", mapper.readTree("{\"query\":\"Redis evidence\"}")));
        assertThrows(AgentHarnessPolicy.PolicyViolationException.class,
                () -> session.authorize("inspectFrame", mapper.readTree("{\"timestampMs\":90000}")));
    }

    @Test
    void enforcesPerToolBudget() throws Exception {
        AgentHarnessPolicy policy = new AgentHarnessPolicy(4, 1, 100, 30);
        AgentHarnessPolicy.Session session = policy.open(context);
        session.authorize("searchEvidence", mapper.readTree("{\"query\":\"Redis\"}"));

        assertThrows(AgentHarnessPolicy.PolicyViolationException.class,
                () -> session.authorize("searchEvidence", mapper.readTree("{\"query\":\"cache\"}")));
    }

    @Test
    void deniedAttemptsStillConsumeTheGlobalBudget() throws Exception {
        AgentHarnessPolicy policy = new AgentHarnessPolicy(2, 2, 100, 30);
        AgentHarnessPolicy.Session session = policy.open(context);

        assertThrows(AgentHarnessPolicy.PolicyViolationException.class,
                () -> session.authorize("inspectFrame", mapper.readTree("{\"timestampMs\":1000}")));
        assertThrows(AgentHarnessPolicy.PolicyViolationException.class,
                () -> session.authorize("inspectFrame", mapper.readTree("{\"timestampMs\":1000}")));
        assertThrows(AgentHarnessPolicy.PolicyViolationException.class,
                () -> session.authorize("searchEvidence", mapper.readTree("{\"query\":\"Redis\"}")));
        assertEquals(2, session.totalCalls());
    }
}
