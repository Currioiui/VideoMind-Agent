package com.example.server.service.agenttool;

import com.example.server.dto.VideoContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * Cheap deterministic gate in front of ReAct. Simple questions keep the fast
 * retrieval path; open-ended, visual or multi-hop questions may use ReAct.
 */
@Component
public class AgentTaskRouter {

    private static final List<String> MULTI_HOP_MARKERS = List.of(
            "为什么", "原因", "如何推导", "前后", "变化", "比较", "对比", "关系",
            "分别", "综合", "总结并", "why", "compare", "difference", "relationship",
            "before and after", "reason", "how did");
    private static final List<String> VISUAL_MARKERS = List.of(
            "画面", "截图", "图表", "ppt", "幻灯片", "关键帧", "屏幕", "展示了什么",
            "frame", "slide", "chart", "diagram", "shown on screen");
    private static final List<String> SINGLE_HOP_MARKERS = List.of(
            "有没有", "是否", "什么时候", "哪一段", "提到", "查找", "定位", "出现在哪",
            "when", "where", "mention", "find", "locate");

    private final boolean enabled;
    private final boolean forceReact;
    private final int simpleGoalMaxChars;

    public AgentTaskRouter(@Value("${agent.router.enabled:true}") boolean enabled,
                           @Value("${agent.router.force-react:false}") boolean forceReact,
                           @Value("${agent.router.simple-goal-max-chars:36}") int simpleGoalMaxChars) {
        if (simpleGoalMaxChars < 8 || simpleGoalMaxChars > 500) {
            throw new IllegalArgumentException("agent.router.simple-goal-max-chars must be between 8 and 500");
        }
        this.enabled = enabled;
        this.forceReact = forceReact;
        this.simpleGoalMaxChars = simpleGoalMaxChars;
    }

    public EvidenceResearchRoute route(VideoContext context) {
        if (!enabled) return deterministic("router-disabled");
        if (forceReact) return react("forced-by-configuration");
        if (context == null || context.userGoal() == null || context.userGoal().isBlank()) {
            return deterministic("missing-goal");
        }
        String goal = context.userGoal().trim().toLowerCase(Locale.ROOT);
        if (containsAny(goal, VISUAL_MARKERS)) return react("visual-evidence-required");
        if (containsAny(goal, MULTI_HOP_MARKERS)) return react("multi-hop-question");
        if (containsAny(goal, SINGLE_HOP_MARKERS)) return deterministic("explicit-single-hop-question");
        if (goal.length() > simpleGoalMaxChars) return react("complex-long-goal");
        return deterministic("simple-single-hop-question");
    }

    private boolean containsAny(String text, List<String> markers) {
        return markers.stream().anyMatch(text::contains);
    }

    private EvidenceResearchRoute deterministic(String reason) {
        return new EvidenceResearchRoute(EvidenceResearchRoute.Strategy.DETERMINISTIC, reason);
    }

    private EvidenceResearchRoute react(String reason) {
        return new EvidenceResearchRoute(EvidenceResearchRoute.Strategy.REACT, reason);
    }
}
