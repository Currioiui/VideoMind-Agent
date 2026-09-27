package com.example.server.service.agenttool;

import com.example.server.dto.VideoContext;

import java.util.List;

/** Auditable result of the evidence-research stage. */
public record EvidenceResearchResult(
        VideoContext context,
        EvidenceResearchRoute route,
        List<ToolTrace> toolTrace,
        boolean fallback,
        String message
) {
    public EvidenceResearchResult {
        toolTrace = toolTrace == null ? List.of() : List.copyOf(toolTrace);
        message = message == null ? "" : message.trim();
    }

    public record ToolTrace(
            int round,
            String tool,
            String status,
            long durationMs
    ) { }
}
