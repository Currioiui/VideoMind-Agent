package com.example.server.service;

/** Agent 因时间、轮次、Token 或成本预算耗尽而正常终止。 */
public class AgentBudgetExceededException extends IllegalStateException {

    public AgentBudgetExceededException(String message) {
        super(message);
    }
}
