import logging
import time
from collections.abc import Awaitable, Callable
from typing import Any, cast

from opentelemetry import trace

from yan_agent.ids import new_ulid
from yan_agent.observability.context import bind_context
from yan_agent.observability.metrics import AgentMetrics

logger = logging.getLogger(__name__)

AsgiMessage = dict[str, Any]
AsgiReceive = Callable[[], Awaitable[AsgiMessage]]
AsgiSend = Callable[[AsgiMessage], Awaitable[None]]
AsgiApp = Callable[[dict[str, Any], AsgiReceive, AsgiSend], Awaitable[None]]


class ObservabilityMiddleware:
    def __init__(self, app: AsgiApp, metrics: AgentMetrics) -> None:
        self._app = app
        self._metrics = metrics

    async def __call__(
        self,
        scope: dict[str, Any],
        receive: AsgiReceive,
        send: AsgiSend,
    ) -> None:
        if scope["type"] != "http":
            await self._app(scope, receive, send)
            return
        request_id = _request_id(scope)
        method = str(scope.get("method", "UNKNOWN"))
        started_at = time.perf_counter()
        status_code = 500

        async def send_with_context(message: AsgiMessage) -> None:
            nonlocal status_code
            if message["type"] == "http.response.start":
                status_code = int(message["status"])
                headers = list(message.get("headers", []))
                if not any(name.lower() == b"x-request-id" for name, _value in headers):
                    headers.append((b"x-request-id", request_id.encode("ascii")))
                message["headers"] = headers
            await send(message)

        with bind_context(request_id=request_id):
            try:
                await self._app(scope, receive, send_with_context)
            finally:
                duration = time.perf_counter() - started_at
                route = cast(str, getattr(scope.get("route"), "path", "unmatched"))
                self._metrics.observe_http(method, route, status_code, duration)
                path_run_id = scope.get("path_params", {}).get("run_id")
                run_id = path_run_id if isinstance(path_run_id, str) else None
                with bind_context(run_id=run_id):
                    if run_id is not None:
                        trace.get_current_span().set_attribute("agent.run.id", run_id)
                    logger.info(
                        "HTTP request completed",
                        extra={
                            "event": "http_request_completed",
                            "status": status_code,
                            "duration_ms": round(duration * 1000, 3),
                        },
                    )


def _request_id(scope: dict[str, Any]) -> str:
    for raw_name, raw_value in scope.get("headers", []):
        if raw_name.lower() != b"x-request-id":
            continue
        value = cast(bytes, raw_value).decode("ascii", errors="ignore").strip()
        if value and len(value) <= 128 and all(character.isprintable() for character in value):
            return value
    return new_ulid()
