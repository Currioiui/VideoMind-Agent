# VideoMind Agent V2 architecture

V2 keeps the reliable Java-controlled Planner–Executor–Critic loop and adds an
on-demand Tool Calling evidence researcher in front of the Planner.

```text
User goal
  -> deterministic hybrid retrieval (safe fallback)
  -> Tool Calling evidence researcher
       -> searchEvidence(query)
       -> expandTimestamp(timestamp, window)
       -> inspectFrame(timestamp)
  -> raw VideoSegment selection
  -> Planner
  -> Executor
  -> Critic
       -> pass: persist result
       -> fail: refresh evidence / replan / execute again
```

## Security boundary

The model never supplies a media id. `AgentToolContext` binds the server-authorized
media id and immutable `VideoContext` to the execution thread. All exposed tools
are read-only and return bounded results.

## Reliability boundary

Tool research has its own timeout and shares the parent Agent execution deadline.
If tool calling fails or the configured model does not support tools, V2 falls back
to the existing deterministic retrieval result. The Critic and Java evidence
verification remain authoritative.

## Important classes

- `config/AiModelConfig`: one generic `ChatModel` bean shared by all AI components.
- `service/agenttool/VideoAgentTools`: bounded read-only video tools.
- `service/agenttool/ToolCallingEvidenceService`: tool loop invocation and safe fallback.
- `service/agenttool/AgentToolContext`: server-owned tool authorization context.
- `service/AgentLoopService`: controlled Planner–Executor–Critic workflow.

## Configuration

```properties
agent.tools.enabled=true
agent.tools.max-segments=12
agent.tools.neighborhood-seconds=15
agent.tools.timeout-seconds=90
```

Set `AGENT_TOOLS_ENABLED=false` to return to the original deterministic evidence
selection without changing any code.

## Next extensions

1. Add a vision-language tool that reads the actual frame rather than OCR metadata.
2. Add a cross-encoder reranker and measure Recall@K / nDCG on the golden set.
3. Move all Planner/Executor/Critic prompts to versioned resource files.
4. Add Testcontainers integration tests for Redis, MySQL, RocketMQ and Qdrant.
