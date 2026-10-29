import json
import logging
import sys
from datetime import UTC, datetime

from opentelemetry import trace

from yan_agent.observability.context import correlation_context

_SAFE_EXTRA_FIELDS = (
    "event",
    "worker_id",
    "node",
    "status",
    "error_code",
    "duration_ms",
    "dependency",
    "operation",
    "query_id",
    "model_request_id",
    "degradation_reason",
)


class JsonLogFormatter(logging.Formatter):
    def __init__(self, service_name: str) -> None:
        super().__init__()
        self._service_name = service_name

    def format(self, record: logging.LogRecord) -> str:
        payload: dict[str, object] = {
            "timestamp": datetime.now(UTC).isoformat(),
            "level": record.levelname,
            "service": self._service_name,
            "logger": record.name,
            "message": record.getMessage(),
            **correlation_context(),
        }
        span_context = trace.get_current_span().get_span_context()
        if span_context.is_valid:
            payload["traceId"] = format(span_context.trace_id, "032x")
            payload["spanId"] = format(span_context.span_id, "016x")
        for field in _SAFE_EXTRA_FIELDS:
            value = getattr(record, field, None)
            if value is not None:
                payload[field] = value
        if record.exc_info is not None and record.exc_info[0] is not None:
            payload["exceptionType"] = record.exc_info[0].__name__
        return json.dumps(payload, ensure_ascii=False, separators=(",", ":"))


def configure_logging(level: str, service_name: str) -> None:
    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(JsonLogFormatter(service_name))
    root = logging.getLogger()
    root.handlers.clear()
    root.addHandler(handler)
    root.setLevel(level)
