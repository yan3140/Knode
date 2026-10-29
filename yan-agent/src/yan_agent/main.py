from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from dataclasses import dataclass

from fastapi import FastAPI, Response
from prometheus_client import CONTENT_TYPE_LATEST
from redis.asyncio import Redis
from sqlalchemy.ext.asyncio import AsyncEngine, async_sessionmaker, create_async_engine

from yan_agent.adapters.context_cache import RedisContextCache
from yan_agent.adapters.context_rebuild import RedisContextRebuildCoordinator
from yan_agent.adapters.java_knowledge.client import JavaKnowledgeClient
from yan_agent.adapters.java_knowledge.retriever import JavaKnowledgeRetriever
from yan_agent.adapters.model.wenwen_responses import WenwenResponsesModel
from yan_agent.adapters.persistence.run_repository import PostgresRunRepository
from yan_agent.adapters.readiness import InfrastructureReadinessProbe
from yan_agent.adapters.redis_run_events import RedisRunEventNotifier
from yan_agent.api.agent import AgentInvoker, create_agent_router
from yan_agent.api.health import create_health_router
from yan_agent.api.runs import RunApplication, create_runs_router
from yan_agent.application.adaptive_retrieval_router import AdaptiveRetrievalRouter
from yan_agent.application.context_manager import ContextManager
from yan_agent.application.qa_agent import KnowledgeQaAgent
from yan_agent.application.question_analysis import StructuredQuestionAnalyzer
from yan_agent.application.retrieval_planning import SingleRewritePlanner
from yan_agent.application.run_service import RunService
from yan_agent.application.unified_retrieval_planning import UnifiedRetrievalPlanner
from yan_agent.ids import new_ulid
from yan_agent.observability.metrics import AgentMetrics
from yan_agent.observability.middleware import ObservabilityMiddleware
from yan_agent.observability.tracing import instrument_fastapi, instrument_sqlalchemy
from yan_agent.ports.readiness import ReadinessProbe
from yan_agent.security.principal_token import PrincipalTokenVerifier
from yan_agent.settings import Settings


@dataclass
class _OwnedResources:
    agent: KnowledgeQaAgent
    model: WenwenResponsesModel
    knowledge_client: JavaKnowledgeClient

    async def aclose(self) -> None:
        await self.model.aclose()
        await self.knowledge_client.aclose()


@dataclass
class _OwnedRunResources:
    service: RunService
    engine: AsyncEngine
    redis: Redis

    async def aclose(self) -> None:
        await self.redis.aclose()
        await self.engine.dispose()


def create_app(
    readiness_probe: ReadinessProbe | None = None,
    settings: Settings | None = None,
    agent: AgentInvoker | None = None,
    run_service: RunApplication | None = None,
    principal_verifier: PrincipalTokenVerifier | None = None,
    metrics: AgentMetrics | None = None,
) -> FastAPI:
    current_settings = settings or Settings()
    current_metrics = metrics or AgentMetrics()
    principal_secret = current_settings.principal_jwt_secret.get_secret_value()
    if principal_verifier is None and len(principal_secret.encode("utf-8")) >= 32:
        principal_verifier = PrincipalTokenVerifier(principal_secret)

    owned_agent = None
    if agent is None:
        owned_agent = _build_owned_resources(current_settings, current_metrics)
        if owned_agent is not None:
            agent = owned_agent.agent

    owned_runs = None
    if run_service is None and len(principal_secret.encode("utf-8")) >= 32:
        owned_runs = _build_run_resources(current_settings, current_metrics)
        run_service = owned_runs.service

    @asynccontextmanager
    async def lifespan(_application: FastAPI) -> AsyncIterator[None]:
        yield
        if owned_agent is not None:
            await owned_agent.aclose()
        if owned_runs is not None:
            await owned_runs.aclose()

    application = FastAPI(title="Yan Agent", version="0.7.0", lifespan=lifespan)
    application.add_middleware(ObservabilityMiddleware, metrics=current_metrics)
    application.include_router(
        create_health_router(readiness_probe or InfrastructureReadinessProbe(current_settings))
    )
    application.include_router(create_agent_router(agent, principal_verifier))
    application.include_router(create_runs_router(run_service, principal_verifier))
    application.add_api_route(
        "/metrics",
        lambda: Response(current_metrics.render(), media_type=CONTENT_TYPE_LATEST),
        methods=["GET"],
        include_in_schema=False,
    )
    instrument_fastapi(application)
    return application


def _build_owned_resources(settings: Settings, metrics: AgentMetrics) -> _OwnedResources | None:
    model_key = settings.model_api_key.get_secret_value()
    service_key = settings.java_service_key.get_secret_value()
    principal_secret = settings.principal_jwt_secret.get_secret_value()
    if not model_key or not service_key or len(principal_secret.encode("utf-8")) < 32:
        return None
    model = WenwenResponsesModel(
        responses_url=settings.model_responses_url,
        api_key=model_key,
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
    )
    knowledge_client = JavaKnowledgeClient(
        base_url=settings.java_base_url,
        service_key=service_key,
        metrics=metrics,
    )
    return _OwnedResources(
        agent=KnowledgeQaAgent(
            model=model,
            retriever=JavaKnowledgeRetriever(knowledge_client),
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
        ),
        model=model,
        knowledge_client=knowledge_client,
    )


def _build_run_resources(settings: Settings, metrics: AgentMetrics) -> _OwnedRunResources:
    engine = create_async_engine(settings.sqlalchemy_dsn, pool_pre_ping=True)
    instrument_sqlalchemy(engine)
    sessions = async_sessionmaker(engine, expire_on_commit=False)
    repository = PostgresRunRepository(
        sessions,
        max_context_messages=settings.context_cache_max_messages,
    )
    redis = Redis.from_url(settings.redis_url, decode_responses=True)
    contexts = ContextManager(
        repository=repository,
        cache=RedisContextCache(
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
    return _OwnedRunResources(
        service=RunService(
            repository,
            new_ulid,
            contexts,
            event_notifier=RedisRunEventNotifier(redis),
            event_poll_seconds=settings.event_poll_seconds,
        ),
        engine=engine,
        redis=redis,
    )


app = create_app()
