package yumefusaka.envoymart.agent.core;

/**
 * Agent 生命周期状态。
 */
public enum AgentStatus {
    IDLE,
    THINKING,
    WAITING_TOOL,
    OBSERVING,
    PLANNING,
    EXECUTING,
    FINISHED,
    ERROR
}
