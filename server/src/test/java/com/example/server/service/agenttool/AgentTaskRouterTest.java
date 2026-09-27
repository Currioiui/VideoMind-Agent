package com.example.server.service.agenttool;

import com.example.server.dto.VideoContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentTaskRouterTest {

    private final VideoContext.VideoSegment segment =
            new VideoContext.VideoSegment(0, 10_000, "Redis", List.of(), List.of());

    @Test
    void keepsSimpleFactQuestionOnDeterministicPath() {
        AgentTaskRouter router = new AgentTaskRouter(true, false, 36);
        EvidenceResearchRoute route = router.route(
                new VideoContext("video", "有没有提到 Redis？", List.of(segment)));

        assertEquals(EvidenceResearchRoute.Strategy.DETERMINISTIC, route.strategy());
    }

    @Test
    void sendsMultiHopQuestionToReact() {
        AgentTaskRouter router = new AgentTaskRouter(true, false, 36);
        EvidenceResearchRoute route = router.route(
                new VideoContext("video", "作者前后观点为什么发生变化？", List.of(segment)));

        assertEquals(EvidenceResearchRoute.Strategy.REACT, route.strategy());
    }

    @Test
    void keepsVerboseButExplicitLookupOnDeterministicPath() {
        AgentTaskRouter router = new AgentTaskRouter(true, false, 20);
        EvidenceResearchRoute route = router.route(new VideoContext(
                "video", "请帮我查找一下这段视频里面有没有明确提到 Redis 的具体版本号？", List.of(segment)));

        assertEquals(EvidenceResearchRoute.Strategy.DETERMINISTIC, route.strategy());
    }
}
