package com.example.server.service.agenttool;

import com.example.server.dto.VideoContext;
import com.example.server.dto.VideoEvidenceHit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ObservedEvidenceLedgerTest {

    @Test
    void retainsOnlyTimestampsActuallyReturnedByTools() {
        ObservedEvidenceLedger ledger = new ObservedEvidenceLedger();
        ledger.observe(List.of(
                new VideoEvidenceHit(1_000, 2_000, "ASR", "hit", "text", List.of()),
                new VideoContext.VideoSegment(5_000, 6_000, "text", List.of(), List.of())));

        assertEquals(List.of(1_000L, 2_000L, 5_000L, 6_000L),
                ledger.retainObserved(List.of(1_000L, 2_000L, 3_000L, 5_000L, 6_000L)));
    }
}
