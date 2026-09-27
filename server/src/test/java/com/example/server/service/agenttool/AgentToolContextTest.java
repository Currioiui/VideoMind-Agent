package com.example.server.service.agenttool;

import com.example.server.dto.VideoContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentToolContextTest {

    @Test
    void bindsAndClearsServerOwnedContext() {
        VideoContext context = new VideoContext(
                "test://video", "goal",
                List.of(new VideoContext.VideoSegment(0, 1_000, "hello", List.of(), List.of())));

        assertThrows(IllegalStateException.class, AgentToolContext::current);
        try (AgentToolContext.Scope ignored = AgentToolContext.open(42L, context)) {
            assertEquals(42L, AgentToolContext.current().mediaId());
            assertEquals(context, AgentToolContext.current().context());
        }
        assertThrows(IllegalStateException.class, AgentToolContext::current);
    }
}
