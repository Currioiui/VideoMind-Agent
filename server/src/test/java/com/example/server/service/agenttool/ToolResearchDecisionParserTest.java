package com.example.server.service.agenttool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ToolResearchDecisionParserTest {

    private final ToolResearchDecisionParser parser =
            new ToolResearchDecisionParser(new ObjectMapper());

    @Test
    void parsesFencedJsonAndNormalizesValues() {
        ToolResearchDecision result = parser.parse("""
                explanation
                ```json
                {"usedQueries":["  tree traversal  ","tree traversal"],
                 "selectedTimestamps":[120000,120000,-1],"rationale":" evidence "}
                ```
                """);

        assertEquals(List.of("tree traversal"), result.usedQueries());
        assertEquals(List.of(120000L), result.selectedTimestamps());
        assertEquals("evidence", result.rationale());
    }

    @Test
    void rejectsMissingJson() {
        assertThrows(IllegalStateException.class, () -> parser.parse("not json"));
    }
}
