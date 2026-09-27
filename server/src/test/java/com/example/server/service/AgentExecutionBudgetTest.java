package com.example.server.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentExecutionBudgetTest {

    @Test
    void rejectsWorkAfterDeadline() throws Exception {
        try (AgentExecutionBudget.Scope ignored = AgentExecutionBudget.open(1)) {
            Thread.sleep(5);
            RuntimeException error = assertThrows(
                    AgentExecutionBudget.DeadlineExceededException.class,
                    AgentExecutionBudget::remainingMillis);
            assertInstanceOf(AgentBudgetExceededException.class, error);
        }
        assertDoesNotThrow(AgentExecutionBudget::remainingMillis);
    }

    @Test
    void usageAndDeadlineShareOnePublicBudgetException() {
        assertInstanceOf(AgentBudgetExceededException.class,
                new AgentExecutionBudget.UsageExceededException("token budget exhausted"));
        assertInstanceOf(AgentBudgetExceededException.class,
                new AgentExecutionBudget.DeadlineExceededException("time budget exhausted"));
    }
}
