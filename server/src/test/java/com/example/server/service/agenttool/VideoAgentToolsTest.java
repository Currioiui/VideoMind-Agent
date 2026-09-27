package com.example.server.service.agenttool;

import com.example.server.dto.VideoContext;
import com.example.server.service.AgentTelemetry;
import com.example.server.service.LongVideoContextService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class VideoAgentToolsTest {

    @Test
    void expandsTimestampAndInspectsNearestFrame() {
        LongVideoContextService contextService = mock(LongVideoContextService.class);
        AgentTelemetry telemetry = mock(AgentTelemetry.class);
        VideoAgentTools tools = new VideoAgentTools(contextService, telemetry);
        VideoContext context = new VideoContext("test://video", "goal", List.of(
                new VideoContext.VideoSegment(0, 10_000, "first", List.of("slide one"), List.of("1.jpg")),
                new VideoContext.VideoSegment(20_000, 30_000, "second", List.of("slide two"), List.of("2.jpg"))));

        try (AgentToolContext.Scope ignored = AgentToolContext.open(7L, context)) {
            assertEquals(2, tools.expandTimestamp(15_000, 5).size());
            assertEquals(List.of("slide two"), tools.inspectFrame(25_000).ocrTexts());
        }
    }
}
