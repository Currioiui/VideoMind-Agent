package com.example.server.service.agenttool;

import com.example.server.dto.VideoContext;
import com.example.server.dto.VideoEvidenceHit;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Records timestamps actually returned by tools and rejects model-only timestamps. */
final class ObservedEvidenceLedger {

    private final Set<Long> timestamps = new LinkedHashSet<>();

    void observe(Object result) {
        if (result instanceof Iterable<?> values) {
            values.forEach(this::observe);
        } else if (result instanceof VideoEvidenceHit hit) {
            addRange(hit.startMs(), hit.endMs());
        } else if (result instanceof VideoContext.VideoSegment segment) {
            addRange(segment.startMs(), segment.endMs());
        } else if (result instanceof VideoAgentTools.FrameEvidence frame) {
            addRange(frame.startMs(), frame.endMs());
        }
    }

    List<Long> retainObserved(List<Long> selectedTimestamps) {
        if (selectedTimestamps == null) return List.of();
        return selectedTimestamps.stream()
                .filter(timestamps::contains)
                .distinct()
                .toList();
    }

    int size() {
        return timestamps.size();
    }

    private void addRange(long startMs, long endMs) {
        if (startMs >= 0) timestamps.add(startMs);
        if (endMs >= 0) timestamps.add(endMs);
    }
}
