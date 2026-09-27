package com.example.server.service.agenttool;

import java.util.List;

public record ToolResearchDecision(
        List<String> usedQueries,
        List<Long> selectedTimestamps,
        String rationale
) {
    public ToolResearchDecision {
        usedQueries = usedQueries == null ? List.of() : usedQueries.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .limit(8)
                .toList();
        selectedTimestamps = selectedTimestamps == null ? List.of() : selectedTimestamps.stream()
                .filter(value -> value != null && value >= 0)
                .distinct()
                .limit(12)
                .toList();
        rationale = rationale == null ? "" : rationale.trim();
    }
}
