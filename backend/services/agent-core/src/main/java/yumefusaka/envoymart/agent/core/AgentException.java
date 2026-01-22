package yumefusaka.envoymart.agent.core;

/**
 * Agent 系统异常。
 */
public class AgentException extends RuntimeException {
    public AgentException(String message) { super(message); }
    public AgentException(String message, Throwable cause) { super(message, cause); }
}
