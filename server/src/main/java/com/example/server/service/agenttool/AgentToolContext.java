package com.example.server.service.agenttool;

import com.example.server.dto.VideoContext;

/**
 * Server-owned context for tool execution. The LLM never receives or chooses a media id,
 * which prevents a tool call from crossing a user's media boundary.
 */
public final class AgentToolContext {

    private static final ThreadLocal<Invocation> CURRENT = new ThreadLocal<>();

    private AgentToolContext() {
    }

    public static Scope open(Long mediaId, VideoContext context) {
        if (mediaId == null || context == null) {
            throw new IllegalArgumentException("Tool context requires media id and video context");
        }
        Invocation previous = CURRENT.get();
        CURRENT.set(new Invocation(mediaId, context));
        return () -> {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        };
    }

    public static Invocation current() {
        Invocation invocation = CURRENT.get();
        if (invocation == null) throw new IllegalStateException("No Agent tool context is bound");
        return invocation;
    }

    public record Invocation(Long mediaId, VideoContext context) {
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
