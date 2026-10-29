from enum import StrEnum


class AgentErrorCode(StrEnum):
    MODEL_TIMEOUT = "MODEL_TIMEOUT"
    MODEL_RATE_LIMITED = "MODEL_RATE_LIMITED"
    MODEL_INVALID_RESPONSE = "MODEL_INVALID_RESPONSE"
    MODEL_UNAVAILABLE = "MODEL_UNAVAILABLE"
    KNOWLEDGE_UNAVAILABLE = "KNOWLEDGE_UNAVAILABLE"
    KNOWLEDGE_FORBIDDEN = "KNOWLEDGE_FORBIDDEN"
    BUDGET_EXCEEDED = "BUDGET_EXCEEDED"
    LEASE_LOST = "LEASE_LOST"
    INTERNAL_ERROR = "INTERNAL_ERROR"


class AgentExecutionError(RuntimeError):
    def __init__(
        self,
        code: AgentErrorCode,
        message: str,
        *,
        retryable: bool,
    ) -> None:
        super().__init__(message)
        self.code = code
        self.retryable = retryable
        self.model_calls = 0


class AgentBudgetExceeded(AgentExecutionError):
    def __init__(self, resource: str) -> None:
        super().__init__(
            AgentErrorCode.BUDGET_EXCEEDED,
            f"Agent execution budget exceeded: {resource}",
            retryable=False,
        )


class ModelTimeoutError(AgentExecutionError):
    def __init__(self, message: str = "Model request timed out") -> None:
        super().__init__(AgentErrorCode.MODEL_TIMEOUT, message, retryable=True)


class ModelRateLimitedError(AgentExecutionError):
    def __init__(self, message: str = "Model request was rate limited") -> None:
        super().__init__(AgentErrorCode.MODEL_RATE_LIMITED, message, retryable=True)


class ModelInvalidResponseError(AgentExecutionError):
    def __init__(self, message: str = "Model response is invalid") -> None:
        super().__init__(AgentErrorCode.MODEL_INVALID_RESPONSE, message, retryable=False)


class ModelUnavailableError(AgentExecutionError):
    def __init__(
        self,
        message: str = "Model service is unavailable",
        *,
        retryable: bool = True,
    ) -> None:
        super().__init__(AgentErrorCode.MODEL_UNAVAILABLE, message, retryable=retryable)


class KnowledgeUnavailableError(AgentExecutionError):
    def __init__(
        self,
        message: str = "Knowledge service is unavailable",
        *,
        retryable: bool = True,
    ) -> None:
        super().__init__(AgentErrorCode.KNOWLEDGE_UNAVAILABLE, message, retryable=retryable)


class KnowledgeForbiddenError(AgentExecutionError):
    def __init__(self, message: str = "Knowledge access is forbidden") -> None:
        super().__init__(AgentErrorCode.KNOWLEDGE_FORBIDDEN, message, retryable=False)
