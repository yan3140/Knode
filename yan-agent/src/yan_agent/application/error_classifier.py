from dataclasses import dataclass

from yan_agent.domain.errors import AgentErrorCode, AgentExecutionError
from yan_agent.domain.runs import RunLeaseLost


@dataclass(frozen=True)
class ErrorClassification:
    code: AgentErrorCode
    retryable: bool
    safe_message: str


_SAFE_MESSAGES = {
    AgentErrorCode.MODEL_TIMEOUT: "Model request timed out",
    AgentErrorCode.MODEL_RATE_LIMITED: "Model capacity is temporarily limited",
    AgentErrorCode.MODEL_INVALID_RESPONSE: "Model returned an invalid response",
    AgentErrorCode.MODEL_UNAVAILABLE: "Model service is unavailable",
    AgentErrorCode.KNOWLEDGE_UNAVAILABLE: "Knowledge service is unavailable",
    AgentErrorCode.KNOWLEDGE_FORBIDDEN: "Knowledge access was denied",
    AgentErrorCode.BUDGET_EXCEEDED: "Agent execution budget was exceeded",
    AgentErrorCode.LEASE_LOST: "Agent worker lease was lost",
    AgentErrorCode.INTERNAL_ERROR: "Agent execution failed",
}


def classify_agent_error(error: BaseException) -> ErrorClassification:
    if isinstance(error, AgentExecutionError):
        return ErrorClassification(
            code=error.code,
            retryable=error.retryable,
            safe_message=_SAFE_MESSAGES[error.code],
        )
    if isinstance(error, RunLeaseLost):
        return ErrorClassification(
            code=AgentErrorCode.LEASE_LOST,
            retryable=True,
            safe_message=_SAFE_MESSAGES[AgentErrorCode.LEASE_LOST],
        )
    return ErrorClassification(
        code=AgentErrorCode.INTERNAL_ERROR,
        retryable=False,
        safe_message=_SAFE_MESSAGES[AgentErrorCode.INTERNAL_ERROR],
    )
