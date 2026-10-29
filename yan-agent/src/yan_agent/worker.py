import asyncio
import logging
import os
import socket
from datetime import UTC, datetime

from langgraph.checkpoint.postgres.aio import AsyncPostgresSaver
from prometheus_client import start_http_server
from redis.asyncio import Redis
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine

from yan_agent.adapters.context_cache import RedisContextCache
from yan_agent.adapters.context_rebuild import RedisContextRebuildCoordinator
from yan_agent.adapters.java_knowledge.client import JavaKnowledgeClient
from yan_agent.adapters.java_knowledge.retriever import JavaKnowledgeRetriever
from yan_agent.adapters.model.wenwen_responses import WenwenResponsesModel
from yan_agent.adapters.persistence.run_repository import PostgresRunRepository
from yan_agent.adapters.redis_run_events import RedisRunEventNotifier
from yan_agent.application.adaptive_retrieval_router import AdaptiveRetrievalRouter
from yan_agent.application.context_manager import ContextManager
from yan_agent.application.qa_agent import KnowledgeQaAgent
from yan_agent.application.question_analysis import StructuredQuestionAnalyzer
from yan_agent.application.retrieval_planning import SingleRewritePlanner
from yan_agent.application.run_worker import RunWorker
from yan_agent.application.unified_retrieval_planning import UnifiedRetrievalPlanner
from yan_agent.asyncio_runtime import run
from yan_agent.ids import new_ulid
from yan_agent.observability.logging import configure_logging
from yan_agent.observability.metrics import AgentMetrics
from yan_agent.observability.tracing import configure_tracing, instrument_sqlalchemy
from yan_agent.settings import Settings

logger = logging.getLogger(__name__)


async def serve_worker(settings: Settings) -> None:
    _validate_worker_settings(settings)
    metrics = AgentMetrics()
    start_http_server(settings.worker_metrics_port, registry=metrics.registry)
    engine = create_async_engine(settings.sqlalchemy_dsn, pool_pre_ping=True)
    instrument_sqlalchemy(engine)
    sessions = async_sessionmaker(engine, expire_on_commit=False)
    repository = PostgresRunRepository(
        sessions,
        max_context_messages=settings.context_cache_max_messages,
    )
    redis = Redis.from_url(settings.redis_url, decode_responses=True)
    contexts = ContextManager(
        repository,
        RedisContextCache(
            redis,
            ttl_seconds=settings.context_cache_ttl_seconds,
            ttl_jitter_ratio=settings.context_cache_ttl_jitter_ratio,
        ),
        rebuild_coordinator=RedisContextRebuildCoordinator(redis),
        rebuild_lease_seconds=settings.context_rebuild_lease_seconds,
        rebuild_retry_seconds=settings.context_rebuild_retry_seconds,
        max_concurrent_rebuilds=settings.context_max_concurrent_rebuilds,
        metrics=metrics,
    )
    worker_id = f"{socket.gethostname()}-{os.getpid()}"

    try:
        async with (
            WenwenResponsesModel(
                responses_url=settings.model_responses_url,
                api_key=settings.model_api_key.get_secret_value(),
                model=settings.model_name,
                reasoning_effort=settings.model_reasoning_effort,
                timeout_seconds=settings.model_timeout_seconds,
                max_output_tokens=settings.model_max_output_tokens,
                analyzer_reasoning_effort=settings.analyzer_reasoning_effort,
                analyzer_timeout_seconds=settings.analyzer_timeout_seconds,
                analyzer_max_output_tokens=settings.analyzer_max_output_tokens,
                analyzer_max_attempts=settings.analyzer_max_attempts,
                input_cost_per_million=settings.model_input_cost_per_million,
                output_cost_per_million=settings.model_output_cost_per_million,
                metrics=metrics,
            ) as model,
            JavaKnowledgeClient(
                base_url=settings.java_base_url,
                service_key=settings.java_service_key.get_secret_value(),
                metrics=metrics,
            ) as knowledge_client,
            AsyncPostgresSaver.from_conn_string(settings.postgres_dsn) as checkpointer,
        ):
            await checkpointer.setup()
            agent = KnowledgeQaAgent(
                model=model,
                retriever=JavaKnowledgeRetriever(knowledge_client),
                checkpointer=checkpointer,
                enable_interrupts=True,
                grounding_model=model,
                budget=settings.agent_budget,
                metrics=metrics,
                retrieval_router=(
                    AdaptiveRetrievalRouter(settings.adaptive_routing_policy)
                    if settings.adaptive_routing_enabled
                    else None
                ),
                adaptive_retrieval_planner=(
                    UnifiedRetrievalPlanner(
                        StructuredQuestionAnalyzer(model),
                        SingleRewritePlanner(model),
                    )
                    if settings.adaptive_execution_enabled and settings.adaptive_routing_enabled
                    else None
                ),
            )
            worker = RunWorker(
                repository=repository,
                contexts=contexts,
                agent=agent,
                new_id=new_ulid,
                clock=lambda: datetime.now(UTC),
                lease_seconds=settings.worker_lease_seconds,
                event_notifier=RedisRunEventNotifier(redis),
                metrics=metrics,
            )
            while True:
                try:
                    worked = await worker.run_once(worker_id)
                except Exception:
                    logger.exception(
                        "Agent worker iteration failed",
                        extra={"worker_id": worker_id},
                    )
                    worked = False
                if not worked:
                    await asyncio.sleep(settings.worker_poll_seconds)
    finally:
        await redis.aclose()
        await engine.dispose()


def _validate_worker_settings(settings: Settings) -> None:
    if not settings.model_api_key.get_secret_value():
        raise RuntimeError("YAN_AGENT_MODEL_API_KEY is required for the worker")
    if not settings.java_service_key.get_secret_value():
        raise RuntimeError("YAN_AGENT_JAVA_SERVICE_KEY is required for the worker")


def main() -> None:
    settings = Settings()
    configure_logging(settings.log_level, settings.otel_service_name)
    configure_tracing(
        service_name=settings.otel_service_name,
        service_version="0.7.0",
        otlp_endpoint=settings.otel_exporter_otlp_endpoint,
        sample_ratio=settings.otel_trace_sample_ratio,
    )
    run(serve_worker(settings))


if __name__ == "__main__":
    main()
