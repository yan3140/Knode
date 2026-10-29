import json

import httpx
import pytest

from yan_agent.adapters.model.wenwen_responses import WenwenResponsesModel
from yan_agent.domain.errors import (
    ModelRateLimitedError,
    ModelTimeoutError,
)
from yan_agent.domain.qa import IntentType
from yan_agent.domain.question_analysis import QuestionAnalysisDraft
from yan_agent.domain.retrieval_planning import QueryComplexity, QueryPurpose


def response_with_output_text(text: str) -> httpx.Response:
    return httpx.Response(
        200,
        json={
            "id": "resp-1",
            "status": "completed",
            "output": [
                {
                    "type": "message",
                    "content": [{"type": "output_text", "text": text}],
                }
            ],
        },
    )


@pytest.mark.asyncio
async def test_classifies_with_responses_structured_output_contract() -> None:
    async def handler(request: httpx.Request) -> httpx.Response:
        assert request.headers["Authorization"] == "Bearer model-key"
        payload = json.loads(request.content)
        assert payload["model"] == "gpt-5.6"
        assert payload["reasoning"] == {"effort": "medium"}
        assert payload["text"]["format"]["type"] == "json_schema"
        assert payload["text"]["format"]["strict"] is True
        assert payload["text"]["format"]["name"] == "intent_decision"
        schema = payload["text"]["format"]["schema"]
        assert schema["required"] == ["intent", "clarification_question"]
        assert "default" not in json.dumps(schema)
        return response_with_output_text('{"intent":"KNOWLEDGE","clarification_question":null}')

    async with WenwenResponsesModel(
        responses_url="https://api.wenwenai.org/v1/responses",
        api_key="model-key",
        model="gpt-5.6",
        reasoning_effort="medium",
        transport=httpx.MockTransport(handler),
    ) as model:
        decision = await model.classify("线程池队列满了怎么办？")

    assert decision.intent is IntentType.KNOWLEDGE


@pytest.mark.asyncio
async def test_retries_one_invalid_structured_response() -> None:
    calls = 0

    async def handler(request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        if calls == 1:
            return response_with_output_text("not-json")
        return httpx.Response(
            200,
            json={
                "id": "resp-2",
                "status": "completed",
                "output_text": '{"intent":"CONVERSATIONAL","clarification_question":null}',
            },
        )

    async with WenwenResponsesModel(
        responses_url="https://api.wenwenai.org/v1/responses",
        api_key="model-key",
        model="gpt-5.6",
        transport=httpx.MockTransport(handler),
    ) as model:
        decision = await model.classify("你好")

    assert decision.intent is IntentType.CONVERSATIONAL
    assert calls == 2


@pytest.mark.asyncio
async def test_retries_one_transient_model_service_failure() -> None:
    calls = 0

    async def handler(request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        if calls == 1:
            return httpx.Response(502, request=request)
        return response_with_output_text(
            '{"intent":"CONVERSATIONAL","clarification_question":null}'
        )

    async with WenwenResponsesModel(
        responses_url="https://api.wenwenai.org/v1/responses",
        api_key="model-key",
        model="gpt-5.6",
        transport=httpx.MockTransport(handler),
    ) as model:
        decision = await model.classify("你好")

    assert decision.intent is IntentType.CONVERSATIONAL
    assert calls == 2


@pytest.mark.asyncio
async def test_classifies_model_timeout() -> None:
    async def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ReadTimeout("timed out", request=request)

    async with WenwenResponsesModel(
        responses_url="https://api.wenwenai.org/v1/responses",
        api_key="model-key",
        model="gpt-5.6",
        transport=httpx.MockTransport(handler),
    ) as model:
        with pytest.raises(ModelTimeoutError):
            await model.classify("timeout")


@pytest.mark.asyncio
async def test_classifies_model_rate_limit() -> None:
    async def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(429, request=request)

    async with WenwenResponsesModel(
        responses_url="https://api.wenwenai.org/v1/responses",
        api_key="model-key",
        model="gpt-5.6",
        transport=httpx.MockTransport(handler),
    ) as model:
        with pytest.raises(ModelRateLimitedError):
            await model.classify("rate limited")


@pytest.mark.asyncio
async def test_plans_multi_query_with_strict_structured_output_contract() -> None:
    async def handler(request: httpx.Request) -> httpx.Response:
        payload = json.loads(request.content)
        assert payload["text"]["format"]["name"] == "retrieval_plan"
        assert payload["text"]["format"]["strict"] is True
        assert "最多 4 个" in payload["input"]
        assert 'original_question: "它为什么会失效？"' in payload["input"]
        return response_with_output_text(
            json.dumps(
                {
                    "original_question": "它为什么会失效？",
                    "standalone_question": "MySQL 联合索引为什么会失效？",
                    "complexity": "SIMPLE",
                    "sub_questions": [],
                    "queries": [
                        {
                            "query_id": "q-original",
                            "text": "它为什么会失效？",
                            "purpose": "ORIGINAL",
                            "mode": "AUTO",
                            "weight": 0.6,
                            "target_sub_question_ids": [],
                        },
                        {
                            "query_id": "q-semantic",
                            "text": "MySQL 联合索引失效原因",
                            "purpose": "SEMANTIC",
                            "mode": "HYBRID",
                            "weight": 1.0,
                            "target_sub_question_ids": [],
                        },
                    ],
                    "exact_terms": [],
                    "filters": [],
                    "planner_version": "multi-query.v1",
                },
                ensure_ascii=False,
            )
        )

    async with WenwenResponsesModel(
        responses_url="https://api.wenwenai.org/v1/responses",
        api_key="model-key",
        model="gpt-5.6",
        transport=httpx.MockTransport(handler),
    ) as model:
        plan = await model.plan_retrieval(
            "它为什么会失效？",
            "<conversation_context>上轮讨论联合索引</conversation_context>",
            [],
        )

    assert plan.complexity is QueryComplexity.SIMPLE
    assert plan.queries[0].purpose is QueryPurpose.ORIGINAL


@pytest.mark.asyncio
async def test_multi_query_planning_uses_model_timeout_contract() -> None:
    async def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ReadTimeout("timed out", request=request)

    async with WenwenResponsesModel(
        responses_url="https://api.wenwenai.org/v1/responses",
        api_key="model-key",
        model="gpt-5.6",
        transport=httpx.MockTransport(handler),
    ) as model:
        with pytest.raises(ModelTimeoutError):
            await model.plan_retrieval("问题", "问题", [])


@pytest.mark.asyncio
async def test_analyzes_question_with_unified_structured_output_contract() -> None:
    question = "MVCC 如何实现可重复读，它和幻读有什么关系？"

    async def handler(request: httpx.Request) -> httpx.Response:
        payload = json.loads(request.content)
        assert payload["text"]["format"]["name"] == "question_analysis"
        assert payload["text"]["format"]["strict"] is True
        assert payload["reasoning"] == {"effort": "none"}
        assert payload["max_output_tokens"] == 1000
        assert "不要选择 A/B/C" in payload["input"]
        return response_with_output_text(
            json.dumps(
                {
                    "original_question": question,
                    "standalone_question": question,
                    "complexity": "COMPOUND",
                    "context_dependent": False,
                    "ambiguous": False,
                    "keywords": ["MVCC", "可重复读", "幻读"],
                    "exact_terms": [],
                    "sub_questions": [
                        {"sub_question_id": "sq-1", "text": "MVCC 如何实现可重复读？"},
                        {"sub_question_id": "sq-2", "text": "MVCC 和幻读有什么关系？"},
                    ],
                    "candidate_queries": [
                        {
                            "query_id": "q-original",
                            "text": question,
                            "purpose": "ORIGINAL",
                            "mode": "AUTO",
                            "weight": 0.6,
                            "target_sub_question_ids": [],
                        },
                        {
                            "query_id": "q-1",
                            "text": "MVCC Read View 版本链 可重复读",
                            "purpose": "SUB_QUESTION",
                            "mode": "HYBRID",
                            "weight": 1.0,
                            "target_sub_question_ids": ["sq-1"],
                        },
                        {
                            "query_id": "q-2",
                            "text": "MVCC 幻读 快照读 当前读",
                            "purpose": "SUB_QUESTION",
                            "mode": "HYBRID",
                            "weight": 1.0,
                            "target_sub_question_ids": ["sq-2"],
                        },
                    ],
                    "confidence": 0.92,
                    "analyzer_version": "question-analysis.v1",
                },
                ensure_ascii=False,
            )
        )

    async with WenwenResponsesModel(
        responses_url="https://api.wenwenai.org/v1/responses",
        api_key="model-key",
        model="gpt-5.6",
        analyzer_reasoning_effort="none",
        analyzer_max_output_tokens=1000,
        transport=httpx.MockTransport(handler),
    ) as model:
        analysis = await model.analyze_question(question, question, [])

    assert isinstance(analysis, QuestionAnalysisDraft)
    assert analysis.complexity is QueryComplexity.COMPOUND
    assert len(analysis.candidate_queries) == 3


@pytest.mark.asyncio
async def test_question_analysis_reports_actual_structured_http_attempts() -> None:
    calls = 0

    async def handler(request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        if calls == 1:
            return response_with_output_text("not-json")
        return response_with_output_text(
            json.dumps(
                {
                    "original_question": "什么是覆盖索引？",
                    "standalone_question": "什么是覆盖索引？",
                    "complexity": "SIMPLE",
                    "context_dependent": False,
                    "ambiguous": False,
                    "keywords": ["覆盖索引"],
                    "exact_terms": [],
                    "sub_questions": [],
                    "candidate_queries": [
                        {
                            "query_id": "q-original",
                            "text": "什么是覆盖索引？",
                            "purpose": "ORIGINAL",
                            "mode": "AUTO",
                            "weight": 0.6,
                            "target_sub_question_ids": [],
                        }
                    ],
                    "confidence": 0.9,
                    "analyzer_version": "question-analysis.v1",
                },
                ensure_ascii=False,
            )
        )

    async with WenwenResponsesModel(
        responses_url="https://api.wenwenai.org/v1/responses",
        api_key="model-key",
        model="gpt-5.6",
        analyzer_max_attempts=2,
        transport=httpx.MockTransport(handler),
    ) as model:
        _analysis, attempts = await model.analyze_question_with_usage(
            "什么是覆盖索引？",
            "什么是覆盖索引？",
            [],
        )

    assert attempts == 2
