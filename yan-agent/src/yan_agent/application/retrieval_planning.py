import hashlib
import json
import re
import unicodedata
from typing import Protocol

from yan_agent.domain.errors import ModelInvalidResponseError
from yan_agent.domain.qa import QueryRewrite, RetrievalMode
from yan_agent.domain.retrieval_planning import (
    PlannedQuery,
    QueryComplexity,
    QueryPurpose,
    RetrievalPlan,
    RetrievalPlanDraft,
    RetrievalPlanningRequest,
    RetrievalPlanningResult,
    RetrievalStrategy,
)
from yan_agent.ports.planning_cache import PlanningCache, PlanningCacheUnavailable
from yan_agent.ports.retrieval_planning import RetrievalPlanner

_CONTEXT_TRUNCATION_MARKER = "\n...[context truncated]...\n"


class QueryRewriteModel(Protocol):
    async def rewrite(self, question: str) -> QueryRewrite: ...


class MultiQueryPlanningModel(Protocol):
    async def plan_retrieval(
        self,
        original_question: str,
        model_input: str,
        required_exact_terms: list[str],
    ) -> RetrievalPlanDraft: ...


class RawQueryPlanner:
    strategy = RetrievalStrategy.RAW_QUERY
    model_calls_per_plan = 0
    planner_version = "raw-query.v2"

    async def plan(self, request: RetrievalPlanningRequest) -> RetrievalPlanningResult:
        mode = request.requested_mode
        if mode is RetrievalMode.AUTO:
            mode = RetrievalMode.HYBRID
        return RetrievalPlanningResult(
            strategy=self.strategy,
            query=QueryRewrite(
                query=request.original_question,
                mode=mode,
            ),
            planner_version=self.planner_version,
            prompt_version=None,
            model_calls=0,
        )


class SingleRewritePlanner:
    strategy = RetrievalStrategy.SINGLE_REWRITE
    model_calls_per_plan = 2
    planner_version = "single-rewrite.v1"
    prompt_version = "query-rewrite.v1"

    def __init__(self, model: QueryRewriteModel) -> None:
        self._model = model

    async def plan(self, request: RetrievalPlanningRequest) -> RetrievalPlanningResult:
        usage_method = getattr(self._model, "rewrite_with_usage", None)
        if callable(usage_method):
            rewrite, model_calls = await usage_method(request.model_input)
        else:
            rewrite = await self._model.rewrite(request.model_input)
            model_calls = 1
        if request.requested_mode is not RetrievalMode.AUTO:
            rewrite = rewrite.model_copy(update={"mode": request.requested_mode})
        return RetrievalPlanningResult(
            strategy=self.strategy,
            query=rewrite,
            planner_version=self.planner_version,
            prompt_version=self.prompt_version,
            model_calls=model_calls,
        )


class MultiQueryPlanner:
    strategy = RetrievalStrategy.MULTI_QUERY
    model_calls_per_plan = 2
    planner_version = "multi-query.v1"
    prompt_version = "retrieval-plan.v1"

    def __init__(self, model: MultiQueryPlanningModel) -> None:
        self._model = model

    async def plan(self, request: RetrievalPlanningRequest) -> RetrievalPlanningResult:
        required_exact_terms = extract_exact_terms(request.original_question)
        usage_method = getattr(self._model, "plan_retrieval_with_usage", None)
        if callable(usage_method):
            plan, model_calls = await usage_method(
                request.original_question,
                request.model_input,
                required_exact_terms,
            )
        else:
            plan = await self._model.plan_retrieval(
                request.original_question,
                request.model_input,
                required_exact_terms,
            )
            model_calls = 1
        if plan.original_question != request.original_question:
            raise ModelInvalidResponseError("Planner changed the original question")
        if plan.planner_version != self.planner_version:
            raise ModelInvalidResponseError("Planner returned an unsupported version")

        plan = _enforce_deterministic_plan_rules(
            plan,
            request,
            required_exact_terms,
        )
        if required_exact_terms:
            missing_terms = [term for term in required_exact_terms if term not in plan.exact_terms]
            if missing_terms:
                raise ModelInvalidResponseError(
                    "Planner dropped exact terms: " + ", ".join(missing_terms)
                )

        if request.requested_mode is not RetrievalMode.AUTO:
            plan = plan.model_copy(
                update={
                    "queries": [
                        query.model_copy(update={"mode": request.requested_mode})
                        for query in plan.queries
                    ]
                }
            )
        original_query = next(
            query for query in plan.queries if query.purpose is QueryPurpose.ORIGINAL
        )
        return RetrievalPlanningResult(
            strategy=self.strategy,
            query=QueryRewrite(query=original_query.text, mode=original_query.mode),
            planner_version=self.planner_version,
            prompt_version=self.prompt_version,
            model_calls=model_calls,
            plan=plan,
        )


_EXACT_IDENTIFIER_PATTERNS = (
    re.compile(r"`([^`\r\n]+)`"),
    re.compile(r"\b[A-Za-z][A-Za-z0-9]*(?:_[A-Za-z0-9]+)+\b"),
    re.compile(r"\b[A-Za-z$][A-Za-z0-9$]*(?:\.[A-Za-z$][A-Za-z0-9$]*)+\b"),
)
_EXACT_TERM_PATTERNS = (
    *_EXACT_IDENTIFIER_PATTERNS,
    re.compile(r"\b\d+(?:\.\d+)?\b"),
)
_SEMANTIC_QUESTION = re.compile(
    r"(为什么|为何|原理|机制|用途|作用|意义|影响|如何实现|怎么实现|怎么工作)"
)


def deterministic_retrieval_mode(
    query: str,
    *,
    purpose: QueryPurpose | None = None,
) -> RetrievalMode:
    if purpose is QueryPurpose.EXACT:
        return RetrievalMode.EXACT
    if purpose is QueryPurpose.KEYWORD or extract_exact_terms(query):
        return RetrievalMode.KEYWORD
    if purpose is QueryPurpose.SEMANTIC or _SEMANTIC_QUESTION.search(query):
        return RetrievalMode.VECTOR
    return RetrievalMode.HYBRID


def extract_exact_terms(question: str) -> list[str]:
    terms: list[str] = []
    for pattern in _EXACT_TERM_PATTERNS:
        for match in pattern.finditer(question):
            term = match.group(1) if match.lastindex else match.group(0)
            if term not in terms:
                terms.append(term)
    return terms


def requires_exact_query(question: str) -> bool:
    return any(pattern.search(question) for pattern in _EXACT_IDENTIFIER_PATTERNS)


def _enforce_deterministic_plan_rules(
    plan: RetrievalPlan | RetrievalPlanDraft,
    request: RetrievalPlanningRequest,
    required_exact_terms: list[str],
) -> RetrievalPlan:
    queries = _deduplicate_planned_queries(plan.queries)
    queries = [
        query.model_copy(
            update={"weight": 0.6 if query.purpose is QueryPurpose.ORIGINAL else query.weight}
        )
        for query in queries
    ]
    known_sub_question_ids = {sub_question.sub_question_id for sub_question in plan.sub_questions}
    queries = [
        query.model_copy(
            update={
                "query_id": f"q-{index + 1}",
                "mode": (
                    request.requested_mode
                    if request.requested_mode is not RetrievalMode.AUTO
                    else deterministic_retrieval_mode(
                        query.text,
                        purpose=query.purpose,
                    )
                ),
                "target_sub_question_ids": [
                    item for item in query.target_sub_question_ids if item in known_sub_question_ids
                ],
            }
        )
        for index, query in enumerate(queries)
    ]
    exact_terms = list(plan.exact_terms)
    for term in required_exact_terms:
        if term not in exact_terms:
            exact_terms.append(term)

    complexity = plan.complexity
    if complexity is QueryComplexity.SIMPLE and len(queries) > 2:
        original = next(query for query in queries if query.purpose is QueryPurpose.ORIGINAL)
        alternatives = [query for query in queries if query.purpose is not QueryPurpose.ORIGINAL]
        alternatives.sort(
            key=lambda query: (
                -query.weight,
                query.purpose.value,
                query.query_id,
            )
        )
        queries = [original, alternatives[0]]
    if requires_exact_query(request.original_question) or plan.complexity is QueryComplexity.EXACT:
        complexity = QueryComplexity.EXACT
        exact_index = next(
            (index for index, query in enumerate(queries) if query.purpose is QueryPurpose.EXACT),
            None,
        )
        if exact_index is None:
            exact_index = next(
                (
                    index
                    for index, query in enumerate(queries)
                    if query.purpose is not QueryPurpose.ORIGINAL
                ),
                None,
            )
        if exact_index is None:
            existing_ids = {query.query_id for query in queries}
            query_id = "q-exact-required"
            suffix = 2
            while query_id in existing_ids:
                query_id = f"q-exact-required-{suffix}"
                suffix += 1
            query_terms = required_exact_terms or exact_terms
            queries.append(
                PlannedQuery(
                    query_id=query_id,
                    text="精确标识符 " + " ".join(query_terms),
                    purpose=QueryPurpose.EXACT,
                    mode=RetrievalMode.EXACT,
                    weight=1.0,
                    target_sub_question_ids=[],
                )
            )
        else:
            exact_query = queries[exact_index]
            query_terms = required_exact_terms or exact_terms
            missing = [term for term in query_terms if term not in exact_query.text]
            exact_text = exact_query.text
            if missing:
                exact_text = f"{exact_text} {' '.join(missing)}"
            queries[exact_index] = exact_query.model_copy(
                update={
                    "text": exact_text,
                    "purpose": QueryPurpose.EXACT,
                    "mode": RetrievalMode.EXACT,
                }
            )

    standalone_question = plan.standalone_question
    if request.conversation_context_present and _normalize_text(
        standalone_question
    ) == _normalize_text(request.original_question):
        resolved_query = next(
            (
                query
                for query in queries
                if query.purpose is not QueryPurpose.ORIGINAL
                and _normalize_text(query.text) != _normalize_text(request.original_question)
            ),
            None,
        )
        if resolved_query is None:
            raise ModelInvalidResponseError(
                "Planner did not resolve the contextual standalone question"
            )
        standalone_question = resolved_query.text

    try:
        return RetrievalPlan.model_validate(
            {
                **plan.model_dump(),
                "standalone_question": standalone_question,
                "complexity": complexity,
                "queries": queries,
                "exact_terms": exact_terms,
            }
        )
    except ValueError as error:
        raise ModelInvalidResponseError(
            "Deterministic plan normalization produced an invalid plan"
        ) from error


def _normalize_text(value: str) -> str:
    return " ".join(unicodedata.normalize("NFKC", value).split()).casefold()


def _deduplicate_planned_queries(queries: list[PlannedQuery]) -> list[PlannedQuery]:
    deduplicated: list[PlannedQuery] = []
    index_by_text: dict[str, int] = {}
    for query in queries:
        normalized = _normalize_text(query.text)
        existing_index = index_by_text.get(normalized)
        if existing_index is None:
            index_by_text[normalized] = len(deduplicated)
            deduplicated.append(query)
            continue
        existing = deduplicated[existing_index]
        purposes = {existing.purpose, query.purpose}
        preferred = (
            query
            if QueryPurpose.EXACT in purposes and query.purpose is QueryPurpose.EXACT
            else existing
        )
        target_ids = list(
            dict.fromkeys(
                [
                    *existing.target_sub_question_ids,
                    *query.target_sub_question_ids,
                ]
            )
        )
        deduplicated[existing_index] = preferred.model_copy(
            update={
                "weight": max(existing.weight, query.weight),
                "target_sub_question_ids": target_ids,
            }
        )
    return deduplicated


def planning_cache_key(
    request: RetrievalPlanningRequest,
    *,
    strategy: RetrievalStrategy | str,
    planner_model: str,
    prompt_version: str,
) -> str | None:
    if request.knowledge_base_id is None or request.knowledge_base_version is None:
        return None
    normalized_question = _normalize_text(request.original_question)
    context_hash = hashlib.sha256(request.model_input.encode("utf-8")).hexdigest()
    payload = {
        "knowledgeBaseId": request.knowledge_base_id,
        "knowledgeBaseVersion": request.knowledge_base_version,
        "contextVersion": request.context_version,
        "conversationContextHash": context_hash,
        "normalizedQuestion": normalized_question,
        "retrievalStrategy": str(strategy),
        "requestedMode": request.requested_mode.value,
        "plannerModel": planner_model,
        "plannerPromptVersion": prompt_version,
    }
    digest = hashlib.sha256(
        json.dumps(payload, ensure_ascii=False, sort_keys=True).encode("utf-8")
    ).hexdigest()
    return f"v1:{digest}"


class CachedRetrievalPlanner:
    def __init__(
        self,
        planner: RetrievalPlanner,
        cache: PlanningCache,
        *,
        planner_model: str,
        prompt_version: str,
        ttl_seconds: int,
        cache_strategy: RetrievalStrategy | str | None = None,
    ) -> None:
        if ttl_seconds <= 0:
            raise ValueError("ttl_seconds must be positive")
        self._planner = planner
        self._cache = cache
        self._planner_model = planner_model
        self._prompt_version = prompt_version
        self._ttl_seconds = ttl_seconds
        self._cache_strategy = cache_strategy or planner.strategy
        self.strategy = planner.strategy
        self.model_calls_per_plan = planner.model_calls_per_plan

    async def plan(self, request: RetrievalPlanningRequest) -> RetrievalPlanningResult:
        key = planning_cache_key(
            request,
            strategy=self._cache_strategy,
            planner_model=self._planner_model,
            prompt_version=self._prompt_version,
        )
        if key is None:
            return await self._planner.plan(request)
        try:
            cached = await self._cache.get(key)
        except PlanningCacheUnavailable:
            cached = None
        if cached is not None:
            return cached.model_copy(update={"model_calls": 0, "cache_hit": True})

        result = await self._planner.plan(request)
        try:
            await self._cache.set(key, result, self._ttl_seconds)
        except PlanningCacheUnavailable:
            pass
        return result


def render_contextual_question(
    question: str,
    conversation_context: str | None,
    max_context_chars: int,
) -> str:
    if conversation_context is None:
        return question
    context = conversation_context
    if len(context) > max_context_chars:
        available = max_context_chars - len(_CONTEXT_TRUNCATION_MARKER)
        prefix_length = available // 3
        suffix_length = available - prefix_length
        context = context[:prefix_length] + _CONTEXT_TRUNCATION_MARKER + context[-suffix_length:]
    return f"<conversation_context>\n{context}\n</conversation_context>\n当前问题：{question}"
