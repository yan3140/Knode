import asyncio
import json
import time
from types import TracebackType
from typing import Literal, TypeVar

import httpx
from opentelemetry import propagate, trace
from opentelemetry.trace import Status, StatusCode
from pydantic import BaseModel, ConfigDict, Field, ValidationError, model_validator

from yan_agent.domain.errors import (
    ModelInvalidResponseError,
    ModelRateLimitedError,
    ModelTimeoutError,
    ModelUnavailableError,
)
from yan_agent.domain.grounding import CitationReview
from yan_agent.domain.qa import (
    EvidenceAssessment,
    GeneratedAnswer,
    IntentDecision,
    KnowledgeCitation,
    QueryRewrite,
)
from yan_agent.domain.question_analysis import QuestionAnalysisDraft
from yan_agent.domain.retrieval_planning import (
    PlannedQuery,
    QueryComplexity,
    RetrievalPlanDraft,
    SubQuestion,
)
from yan_agent.observability.metrics import AgentMetrics
from yan_agent.prompts.qa_v1 import (
    SYSTEM_RULES,
    answer_prompt,
    classify_prompt,
    conversational_prompt,
    grade_prompt,
    refine_prompt,
    repair_answer_prompt,
    review_answer_prompt,
    rewrite_prompt,
)
from yan_agent.prompts.question_analysis_v1 import question_analysis_prompt
from yan_agent.prompts.retrieval_plan_v1 import retrieval_plan_prompt

StructuredModel = TypeVar("StructuredModel", bound=BaseModel)
ReasoningEffort = Literal["none", "low", "medium", "high", "xhigh"]
tracer = trace.get_tracer(__name__)


class ModelServiceError(ModelUnavailableError):
    pass


class ModelResponseError(ModelInvalidResponseError):
    pass


class _ConversationalReply(BaseModel):
    model_config = ConfigDict(extra="forbid")

    answer: str = Field(min_length=1)


class _RetrievalFilter(BaseModel):
    model_config = ConfigDict(extra="forbid")

    key: str = Field(min_length=1, max_length=100)
    value: str = Field(max_length=500)


class _RetrievalPlanOutput(BaseModel):
    model_config = ConfigDict(extra="forbid")

    original_question: str = Field(min_length=1, max_length=2000)
    standalone_question: str = Field(min_length=1, max_length=2000)
    complexity: QueryComplexity
    sub_questions: list[SubQuestion] = Field(max_length=3)
    queries: list[PlannedQuery] = Field(min_length=1, max_length=4)
    exact_terms: list[str] = Field(max_length=20)
    filters: list[_RetrievalFilter] = Field(max_length=10)
    planner_version: str = Field(min_length=1, max_length=100)

    @model_validator(mode="after")
    def validate_filter_keys(self) -> "_RetrievalPlanOutput":
        filter_keys = [item.key for item in self.filters]
        if len(filter_keys) != len(set(filter_keys)):
            raise ValueError("retrieval filter keys must be unique")
        return self

    def to_draft(self) -> RetrievalPlanDraft:
        filter_pairs = [(item.key, item.value) for item in self.filters]
        return RetrievalPlanDraft(
            original_question=self.original_question,
            standalone_question=self.standalone_question,
            complexity=self.complexity,
            sub_questions=self.sub_questions,
            queries=self.queries,
            exact_terms=self.exact_terms,
            filters=dict(filter_pairs),
            planner_version=self.planner_version,
        )


class WenwenResponsesModel:
    def __init__(
        self,
        responses_url: str,
        api_key: str,
        model: str,
        reasoning_effort: ReasoningEffort = "medium",
        timeout_seconds: float = 90,
        max_output_tokens: int = 2000,
        analyzer_reasoning_effort: ReasoningEffort = "none",
        analyzer_timeout_seconds: float = 8.0,
        analyzer_max_output_tokens: int = 1000,
        analyzer_max_attempts: int = 1,
        input_cost_per_million: float = 0,
        output_cost_per_million: float = 0,
        transport: httpx.AsyncBaseTransport | None = None,
        metrics: AgentMetrics | None = None,
    ) -> None:
        if not api_key:
            raise ValueError("Model API key must not be blank")
        self._responses_url = responses_url
        self._model = model
        self._reasoning_effort = reasoning_effort
        self._timeout_seconds = timeout_seconds
        self._max_output_tokens = max_output_tokens
        self._analyzer_reasoning_effort = analyzer_reasoning_effort
        self._analyzer_timeout_seconds = analyzer_timeout_seconds
        self._analyzer_max_output_tokens = analyzer_max_output_tokens
        self._analyzer_max_attempts = analyzer_max_attempts
        self._input_cost_per_million = input_cost_per_million
        self._output_cost_per_million = output_cost_per_million
        self._metrics = metrics
        self._client = httpx.AsyncClient(
            headers={"Authorization": f"Bearer {api_key}"},
            timeout=httpx.Timeout(timeout_seconds, connect=5),
            transport=transport,
        )

    async def __aenter__(self) -> "WenwenResponsesModel":
        return self

    async def __aexit__(
        self,
        exc_type: type[BaseException] | None,
        exc_value: BaseException | None,
        traceback: TracebackType | None,
    ) -> None:
        await self.aclose()

    async def aclose(self) -> None:
        await self._client.aclose()

    async def classify(self, question: str) -> IntentDecision:
        return await self._structured(
            prompt=classify_prompt(question),
            output_type=IntentDecision,
            schema_name="intent_decision",
        )

    async def rewrite(self, question: str) -> QueryRewrite:
        rewrite, _attempts = await self.rewrite_with_usage(question)
        return rewrite

    async def rewrite_with_usage(self, question: str) -> tuple[QueryRewrite, int]:
        return await self._structured_with_usage(
            prompt=rewrite_prompt(question),
            output_type=QueryRewrite,
            schema_name="query_rewrite",
        )

    async def plan_retrieval(
        self,
        original_question: str,
        model_input: str,
        required_exact_terms: list[str],
    ) -> RetrievalPlanDraft:
        output, _attempts = await self.plan_retrieval_with_usage(
            original_question,
            model_input,
            required_exact_terms,
        )
        return output

    async def plan_retrieval_with_usage(
        self,
        original_question: str,
        model_input: str,
        required_exact_terms: list[str],
    ) -> tuple[RetrievalPlanDraft, int]:
        output, attempts = await self._structured_with_usage(
            prompt=retrieval_plan_prompt(
                original_question,
                model_input,
                required_exact_terms,
            ),
            output_type=_RetrievalPlanOutput,
            schema_name="retrieval_plan",
        )
        return output.to_draft(), attempts

    async def analyze_question(
        self,
        original_question: str,
        model_input: str,
        required_exact_terms: list[str],
    ) -> QuestionAnalysisDraft:
        analysis, _attempts = await self.analyze_question_with_usage(
            original_question,
            model_input,
            required_exact_terms,
        )
        return analysis

    async def analyze_question_with_usage(
        self,
        original_question: str,
        model_input: str,
        required_exact_terms: list[str],
    ) -> tuple[QuestionAnalysisDraft, int]:
        return await self._structured_with_usage(
            prompt=question_analysis_prompt(
                original_question,
                model_input,
                required_exact_terms,
            ),
            output_type=QuestionAnalysisDraft,
            schema_name="question_analysis",
            reasoning_effort=self._analyzer_reasoning_effort,
            timeout_seconds=self._analyzer_timeout_seconds,
            max_output_tokens=self._analyzer_max_output_tokens,
            max_attempts=self._analyzer_max_attempts,
        )

    async def grade(self, question: str, citations: list[KnowledgeCitation]) -> EvidenceAssessment:
        return await self._structured(
            prompt=grade_prompt(question, citations),
            output_type=EvidenceAssessment,
            schema_name="evidence_assessment",
        )

    async def refine(
        self,
        question: str,
        previous_query: str,
        citations: list[KnowledgeCitation],
    ) -> QueryRewrite:
        return await self._structured(
            prompt=refine_prompt(question, previous_query, citations),
            output_type=QueryRewrite,
            schema_name="query_refinement",
        )

    async def answer(self, question: str, citations: list[KnowledgeCitation]) -> GeneratedAnswer:
        return await self._structured(
            prompt=answer_prompt(question, citations),
            output_type=GeneratedAnswer,
            schema_name="grounded_answer",
        )

    async def converse(self, question: str) -> str:
        reply = await self._structured(
            prompt=conversational_prompt(question),
            output_type=_ConversationalReply,
            schema_name="conversational_reply",
        )
        return reply.answer

    async def review_answer(
        self,
        question: str,
        answer: str,
        citations: list[KnowledgeCitation],
    ) -> CitationReview:
        return await self._structured(
            prompt=review_answer_prompt(question, answer, citations),
            output_type=CitationReview,
            schema_name="citation_review",
        )

    async def repair_answer(
        self,
        question: str,
        answer: str,
        citations: list[KnowledgeCitation],
        issues: list[str],
    ) -> GeneratedAnswer:
        return await self._structured(
            prompt=repair_answer_prompt(question, answer, citations, issues),
            output_type=GeneratedAnswer,
            schema_name="repaired_grounded_answer",
        )

    async def _structured(
        self,
        prompt: str,
        output_type: type[StructuredModel],
        schema_name: str,
    ) -> StructuredModel:
        result, _attempts = await self._structured_with_usage(
            prompt,
            output_type,
            schema_name,
        )
        return result

    async def _structured_with_usage(
        self,
        prompt: str,
        output_type: type[StructuredModel],
        schema_name: str,
        *,
        reasoning_effort: ReasoningEffort | None = None,
        timeout_seconds: float | None = None,
        max_output_tokens: int | None = None,
        max_attempts: int = 2,
    ) -> tuple[StructuredModel, int]:
        last_error: ValueError | json.JSONDecodeError | ValidationError | None = None
        attempts = 0
        for attempt in range(max_attempts):
            attempts += 1
            try:
                text = await self._create_response(
                    prompt,
                    output_type,
                    schema_name,
                    reasoning_effort=reasoning_effort,
                    timeout_seconds=timeout_seconds,
                    max_output_tokens=max_output_tokens,
                )
            except (ModelServiceError, ModelRateLimitedError) as error:
                if attempt + 1 < max_attempts and error.retryable:
                    await asyncio.sleep(0.25)
                    continue
                error.model_calls = attempts
                raise
            try:
                return output_type.model_validate_json(text), attempts
            except (ValueError, json.JSONDecodeError, ValidationError) as error:
                last_error = error
        detail = _validation_error_summary(last_error)
        response_error = ModelResponseError(f"Model returned invalid structured output: {detail}")
        response_error.model_calls = attempts
        raise response_error from last_error

    async def _create_response(
        self,
        prompt: str,
        output_type: type[BaseModel],
        schema_name: str,
        *,
        reasoning_effort: ReasoningEffort | None = None,
        timeout_seconds: float | None = None,
        max_output_tokens: int | None = None,
    ) -> str:
        started_at = time.perf_counter()
        outcome = "error"
        with tracer.start_as_current_span("model.responses.create") as span:
            span.set_attribute("gen_ai.operation.name", schema_name)
            span.set_attribute("gen_ai.request.model", self._model)
            headers: dict[str, str] = {}
            propagate.inject(headers)
            try:
                response = await self._client.post(
                    self._responses_url,
                    headers=headers,
                    json={
                        "model": self._model,
                        "instructions": SYSTEM_RULES,
                        "input": prompt,
                        "reasoning": {"effort": reasoning_effort or self._reasoning_effort},
                        "max_output_tokens": max_output_tokens or self._max_output_tokens,
                        "text": {
                            "format": {
                                "type": "json_schema",
                                "name": schema_name,
                                "strict": True,
                                "schema": _strict_json_schema(output_type),
                            }
                        },
                    },
                    timeout=httpx.Timeout(
                        timeout_seconds or self._timeout_seconds,
                        connect=min(5, timeout_seconds or self._timeout_seconds),
                    ),
                )
                span.set_attribute("http.response.status_code", response.status_code)
                if response.status_code == 429:
                    raise ModelRateLimitedError()
                if response.status_code in {408, 504}:
                    raise ModelTimeoutError()
                if response.status_code != 200:
                    raise ModelServiceError(
                        f"Model service returned HTTP {response.status_code}",
                        retryable=response.status_code >= 500,
                    )
                payload = response.json()
                if payload.get("status") not in {None, "completed"}:
                    raise ModelResponseError("Model response did not complete")
                output = _extract_output_text(payload)
            except httpx.TimeoutException as error:
                span.set_status(Status(StatusCode.ERROR, type(error).__name__))
                raise ModelTimeoutError() from error
            except httpx.HTTPError as error:
                span.set_status(Status(StatusCode.ERROR, type(error).__name__))
                raise ModelServiceError("Model service is unavailable") from error
            except ValueError as error:
                span.set_status(Status(StatusCode.ERROR, type(error).__name__))
                raise ModelResponseError("Model response is not valid JSON") from error
            except (
                ModelServiceError,
                ModelResponseError,
                ModelRateLimitedError,
                ModelTimeoutError,
            ) as error:
                span.set_status(Status(StatusCode.ERROR, type(error).__name__))
                raise
            else:
                outcome = "success"
                request_id = payload.get("id")
                if isinstance(request_id, str):
                    span.set_attribute("gen_ai.response.id", request_id)
                self._record_usage(payload, span)
                return output
            finally:
                if self._metrics is not None:
                    self._metrics.observe_dependency(
                        "model",
                        schema_name,
                        outcome,
                        time.perf_counter() - started_at,
                    )

    def _record_usage(self, payload: dict[str, object], span: trace.Span) -> None:
        usage = payload.get("usage")
        if not isinstance(usage, dict):
            return
        input_tokens = _non_negative_int(usage.get("input_tokens"))
        output_tokens = _non_negative_int(usage.get("output_tokens"))
        span.set_attribute("gen_ai.usage.input_tokens", input_tokens)
        span.set_attribute("gen_ai.usage.output_tokens", output_tokens)
        estimated_cost = (
            input_tokens * self._input_cost_per_million
            + output_tokens * self._output_cost_per_million
        ) / 1_000_000
        if self._metrics is not None:
            self._metrics.observe_model_usage(input_tokens, output_tokens, estimated_cost)


def _extract_output_text(payload: object) -> str:
    if not isinstance(payload, dict):
        raise ModelResponseError("Model response is not an object")
    direct = payload.get("output_text")
    if isinstance(direct, str) and direct:
        return direct
    output = payload.get("output")
    if isinstance(output, list):
        for item in output:
            if not isinstance(item, dict):
                continue
            content = item.get("content")
            if not isinstance(content, list):
                continue
            for part in content:
                if isinstance(part, dict) and part.get("type") == "output_text":
                    text = part.get("text")
                    if isinstance(text, str) and text:
                        return text
    raise ModelResponseError("Model response has no output text")


def _strict_json_schema(output_type: type[BaseModel]) -> dict[str, object]:
    schema = output_type.model_json_schema()
    _normalize_strict_schema(schema)
    return schema


def _normalize_strict_schema(node: object) -> None:
    if isinstance(node, dict):
        node.pop("default", None)
        properties = node.get("properties")
        if isinstance(properties, dict):
            node["required"] = list(properties)
        for value in node.values():
            _normalize_strict_schema(value)
    elif isinstance(node, list):
        for value in node:
            _normalize_strict_schema(value)


def _non_negative_int(value: object) -> int:
    if isinstance(value, int) and not isinstance(value, bool) and value >= 0:
        return value
    return 0


def _validation_error_summary(
    error: ValueError | json.JSONDecodeError | ValidationError | None,
) -> str:
    if isinstance(error, ValidationError):
        summaries: list[str] = []
        for item in error.errors(include_input=False, include_url=False)[:3]:
            location = ".".join(str(part) for part in item["loc"]) or "root"
            summaries.append(f"{location}: {item['msg']}")
        return "; ".join(summaries)
    if isinstance(error, json.JSONDecodeError):
        return f"invalid JSON at line {error.lineno}, column {error.colno}"
    if error is not None:
        return type(error).__name__
    return "unknown validation error"
