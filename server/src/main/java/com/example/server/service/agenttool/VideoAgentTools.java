package com.example.server.service.agenttool;

import com.example.server.dto.VideoContext;
import com.example.server.dto.VideoEvidenceHit;
import com.example.server.service.AgentTelemetry;
import com.example.server.service.LongVideoContextService;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/** Read-only, bounded tools exposed to the evidence research model. */
@Component
public class VideoAgentTools {

    private static final int MAX_WINDOW_SECONDS = 300;
    private static final int MAX_WINDOW_SEGMENTS = 12;

    private final LongVideoContextService contextService;
    private final AgentTelemetry telemetry;

    public VideoAgentTools(LongVideoContextService contextService, AgentTelemetry telemetry) {
        this.contextService = contextService;
        this.telemetry = telemetry;
    }

    public List<VideoEvidenceHit> searchEvidence(String query) {
        if (query == null || query.isBlank()) throw new IllegalArgumentException("query is required");
        AgentToolContext.Invocation invocation = AgentToolContext.current();
        telemetry.incrementCurrent("tool.searchEvidence.calls", 1);
        VideoContext queryContext = new VideoContext(
                invocation.context().source(), query.trim(), invocation.context().segments());
        return contextService.searchEvidence(invocation.mediaId(), queryContext);
    }

    public List<VideoContext.VideoSegment> expandTimestamp(
            long timestampMs, int windowSeconds) {
        if (timestampMs < 0) throw new IllegalArgumentException("timestampMs must not be negative");
        int safeWindow = Math.max(5, Math.min(MAX_WINDOW_SECONDS, windowSeconds));
        long from = Math.max(0, timestampMs - safeWindow * 1_000L);
        long to = timestampMs + safeWindow * 1_000L;
        telemetry.incrementCurrent("tool.expandTimestamp.calls", 1);
        return AgentToolContext.current().context().segments().stream()
                .filter(segment -> segment.endMs() >= from && segment.startMs() <= to)
                .sorted(Comparator.comparingLong(VideoContext.VideoSegment::startMs))
                .limit(MAX_WINDOW_SEGMENTS)
                .toList();
    }

    public FrameEvidence inspectFrame(long timestampMs) {
        if (timestampMs < 0) throw new IllegalArgumentException("timestampMs must not be negative");
        telemetry.incrementCurrent("tool.inspectFrame.calls", 1);
        VideoContext.VideoSegment nearest = AgentToolContext.current().context().segments().stream()
                .min(Comparator.comparingLong(segment -> distance(segment, timestampMs)))
                .orElseThrow(() -> new IllegalStateException("Video has no segments"));
        return new FrameEvidence(
                nearest.startMs(), nearest.endMs(), nearest.ocrTexts(), nearest.evidenceFrames());
    }

    private long distance(VideoContext.VideoSegment segment, long timestampMs) {
        if (timestampMs >= segment.startMs() && timestampMs <= segment.endMs()) return 0;
        return timestampMs < segment.startMs()
                ? segment.startMs() - timestampMs
                : timestampMs - segment.endMs();
    }

    public record FrameEvidence(
            long startMs,
            long endMs,
            List<String> ocrTexts,
            List<String> frameReferences
    ) {
    }
}
