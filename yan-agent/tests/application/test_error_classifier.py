from yan_agent.application.error_classifier import classify_agent_error
from yan_agent.domain.errors import (
    AgentErrorCode,
    ModelRateLimitedError,
    ModelTimeoutError,
)


def test_known_agent_error_keeps_stable_code_and_retryability() -> None:
    timeout = classify_agent_error(ModelTimeoutError())
    rate_limited = classify_agent_error(ModelRateLimitedError())

    assert timeout.code is AgentErrorCode.MODEL_TIMEOUT
    assert timeout.retryable is True
    assert rate_limited.code is AgentErrorCode.MODEL_RATE_LIMITED
    assert rate_limited.retryable is True


def test_unknown_error_is_safely_classified_without_exposing_message() -> None:
    classified = classify_agent_error(RuntimeError("secret database detail"))

    assert classified.code is AgentErrorCode.INTERNAL_ERROR
    assert classified.retryable is False
    assert "secret" not in classified.safe_message
