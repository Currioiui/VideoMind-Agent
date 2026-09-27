package com.example.server.service.agenttool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class ToolResearchDecisionParser {

    private final ObjectMapper objectMapper;

    public ToolResearchDecisionParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ToolResearchDecision parse(String response) {
        if (response == null || response.isBlank()) {
            throw new IllegalStateException("Tool research returned an empty response");
        }
        String value = response.replace("```json", "").replace("```", "").trim();
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        if (start < 0 || end <= start) throw new IllegalStateException("Tool research did not return JSON");
        try {
            return objectMapper.readValue(value.substring(start, end + 1), ToolResearchDecision.class);
        } catch (Exception e) {
            throw new IllegalStateException("Tool research returned invalid JSON", e);
        }
    }
}
