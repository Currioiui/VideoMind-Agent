package com.example.server.service.agenttool;

/** Describes whether evidence retrieval needs an autonomous multi-step loop. */
public record EvidenceResearchRoute(Strategy strategy, String reason) {

    public EvidenceResearchRoute {
        strategy = strategy == null ? Strategy.DETERMINISTIC : strategy;
        reason = reason == null ? "" : reason.trim();
    }

    public boolean usesReact() {
        return strategy == Strategy.REACT;
    }

    public enum Strategy {
        DETERMINISTIC,
        REACT
    }
}
