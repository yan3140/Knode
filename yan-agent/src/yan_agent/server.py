import uvicorn

from yan_agent.asyncio_runtime import run
from yan_agent.observability.logging import configure_logging
from yan_agent.observability.tracing import configure_tracing
from yan_agent.settings import Settings


def serve(settings: Settings) -> None:
    configure_logging(settings.log_level, settings.otel_service_name)
    configure_tracing(
        service_name=settings.otel_service_name,
        service_version="0.7.0",
        otlp_endpoint=settings.otel_exporter_otlp_endpoint,
        sample_ratio=settings.otel_trace_sample_ratio,
    )
    config = uvicorn.Config(
        "yan_agent.main:app",
        host=settings.api_host,
        port=settings.api_port,
        loop="none",
        log_config=None,
    )
    run(uvicorn.Server(config).serve())


def main() -> None:
    serve(Settings())


if __name__ == "__main__":
    main()
