package com.example.server.service.agenttool;

import com.example.server.dto.VideoContext;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Enforces the security and resource boundary around a ReAct tool loop. */
@Component
public class AgentHarnessPolicy {

    private static final Set<String> ALLOWED_TOOLS = Set.of(
            "searchEvidence", "expandTimestamp", "inspectFrame");

    private final int maxToolCalls;
    private final int maxSameToolCalls;
    private final int maxQueryChars;
    private final int maxWindowSeconds;

    public AgentHarnessPolicy(@Value("${agent.react.max-tool-calls:8}") int maxToolCalls,
                              @Value("${agent.react.max-same-tool-calls:3}") int maxSameToolCalls,
                              @Value("${agent.react.max-query-chars:200}") int maxQueryChars,
                              @Value("${agent.react.max-window-seconds:90}") int maxWindowSeconds) {
        if (maxToolCalls < 1 || maxToolCalls > 50
                || maxSameToolCalls < 1 || maxSameToolCalls > maxToolCalls
                || maxQueryChars < 16 || maxQueryChars > 2_000
                || maxWindowSeconds < 5 || maxWindowSeconds > 300) {
            throw new IllegalArgumentException("Invalid Agent Harness policy configuration");
        }
        this.maxToolCalls = maxToolCalls;
        this.maxSameToolCalls = maxSameToolCalls;
        this.maxQueryChars = maxQueryChars;
        this.maxWindowSeconds = maxWindowSeconds;
    }

    public Session open(VideoContext context) {
        long maximumTimestamp = context.segments().stream()
                .mapToLong(VideoContext.VideoSegment::endMs)
                .max().orElse(0);
        return new Session(maximumTimestamp);
    }

    public final class Session {
        private final long maximumTimestamp;
        private final Map<String, Integer> callsByTool = new HashMap<>();
        private int totalCalls;
        private boolean evidenceSearchAuthorized;

        private Session(long maximumTimestamp) {
            this.maximumTimestamp = maximumTimestamp;
        }

        public void authorize(String toolName, JsonNode arguments) {
            if (totalCalls >= maxToolCalls) deny("tool-call-budget-exhausted");
            totalCalls++;
            int sameToolCalls = callsByTool.merge(toolName, 1, Integer::sum);
            if (!ALLOWED_TOOLS.contains(toolName)) deny("tool-not-allowed");
            if (sameToolCalls > maxSameToolCalls) deny("same-tool-budget-exhausted");
            if (!evidenceSearchAuthorized && !"searchEvidence".equals(toolName)) {
                deny("first-tool-must-search-evidence");
            }

            switch (toolName) {
                case "searchEvidence" -> validateQuery(arguments);
                case "expandTimestamp" -> {
                    validateTimestamp(arguments);
                    int window = arguments.path("windowSeconds").asInt(-1);
                    if (window < 5 || window > maxWindowSeconds) deny("window-out-of-policy");
                }
                case "inspectFrame" -> validateTimestamp(arguments);
                default -> deny("tool-not-allowed");
            }
            if ("searchEvidence".equals(toolName)) evidenceSearchAuthorized = true;
        }

        public int totalCalls() {
            return totalCalls;
        }

        private void validateQuery(JsonNode arguments) {
            String query = arguments.path("query").asText("").trim();
            if (query.isEmpty() || query.length() > maxQueryChars) deny("query-out-of-policy");
        }

        private void validateTimestamp(JsonNode arguments) {
            JsonNode value = arguments.get("timestampMs");
            if (value == null || !value.canConvertToLong()) deny("invalid-timestamp");
            long timestamp = value.longValue();
            if (timestamp < 0 || timestamp > maximumTimestamp) deny("timestamp-out-of-video");
        }

        private void deny(String reason) {
            throw new PolicyViolationException(reason);
        }
    }

    public static class PolicyViolationException extends IllegalArgumentException {
        public PolicyViolationException(String message) {
            super(message);
        }
    }
}
