"""Run frozen fact-group retrieval cases against the real Java HTTP boundary.

Usage: python -m yan_agent.evaluation.tech50_runner --help
This runner never changes gold, service configuration, or knowledge documents.
"""

import argparse
import asyncio
import hashlib
import json
import math
import time
import uuid
from collections import Counter
from datetime import UTC, datetime
from pathlib import Path
from typing import NotRequired, TypedDict, cast

import httpx

from yan_agent.application.retrieval_planning import deterministic_retrieval_mode
from yan_agent.evaluation.evidence_groups import EvidenceGroup, score_evidence_groups
from yan_agent.settings import Settings


class _RetrievalCase(TypedDict):
    caseId: str
    topic: str
    category: str
    question: str
    answerable: bool
    split: str
    evidenceGroups: list[dict[str, object]]


class _CaseResult(TypedDict):
    caseId: str
    topic: str
    category: str
    question: str
    answerable: bool
    requestedMode: str
    latencyMs: float
    error: str | None
    citations: list[dict[str, object]]
    metrics: dict[str, object] | None
    degraded: NotRequired[bool]
    selectedMode: NotRequired[str]


def percentile(values: list[float], q: float) -> float | None:
    return sorted(values)[max(0, math.ceil(len(values) * q) - 1)] if values else None


def summarize(results: list[_CaseResult]) -> dict[str, object]:
    answerable = [r for r in results if r["answerable"]]
    successful = [r for r in answerable if r["error"] is None]
    negatives = [r for r in results if not r["answerable"]]

    def mean(rows: list[_CaseResult], key: str) -> float | None:
        return (
            sum(cast(float, cast(dict[str, object], row["metrics"])[key]) for row in rows)
            / len(rows)
            if rows
            else None
        )

    return {
        "caseCount": len(results),
        "answerableCount": len(answerable),
        "negativeCount": len(negatives),
        "errorCount": sum(r["error"] is not None for r in results),
        "hitAt10": mean(answerable, "hitAt10"),
        "evidenceGroupRecallAt10": mean(answerable, "evidenceGroupRecallAt10"),
        "completeEvidenceHitAt10": mean(answerable, "completeEvidenceHitAt10"),
        "strictChunkRecallAt10": mean(answerable, "strictChunkRecallAt10"),
        "successfulOnlyGroupRecallAt10": mean(successful, "evidenceGroupRecallAt10"),
        "negativeAnyResultCount": sum(bool(r["citations"]) for r in negatives),
        "negativeAnyResultRate": sum(bool(r["citations"]) for r in negatives) / len(negatives)
        if negatives
        else None,
        "degradedCount": sum(bool(r.get("degraded")) for r in results),
        "selectedModes": dict(Counter(r.get("selectedMode", "ERROR") for r in results)),
        "latencyP50Ms": percentile([r["latencyMs"] for r in results], 0.50),
        "latencyP95Ms": percentile([r["latencyMs"] for r in results], 0.95),
        "latencyMaxMs": max((r["latencyMs"] for r in results), default=None),
    }


async def run(args: argparse.Namespace) -> None:
    settings = Settings()
    raw = args.dataset.read_bytes()
    manifest = json.loads(args.freeze.read_text(encoding="utf-8"))
    digest = hashlib.sha256(raw).hexdigest()
    if manifest["datasetSha256"] != digest:
        raise ValueError("dataset differs from frozen manifest")
    records = [
        cast(_RetrievalCase, json.loads(line))
        for line in raw.decode("utf-8").splitlines()
        if line.strip()
    ]
    cases = [c for c in records if c["split"] == args.split]
    if not cases:
        raise ValueError("no cases for split")
    if args.output.exists():
        raise ValueError("report exists; choose a new experiment output")
    semaphore = asyncio.Semaphore(args.concurrency)
    headers = {"X-Agent-Service-Key": settings.java_service_key.get_secret_value()}
    async with httpx.AsyncClient(
        base_url=args.base_url,
        headers=headers,
        timeout=httpx.Timeout(args.timeout, connect=3),
        trust_env=False,
    ) as client:

        async def evaluate(case: _RetrievalCase) -> _CaseResult:
            async with semaphore:
                mode = (
                    deterministic_retrieval_mode(case["question"]).value
                    if args.mode == "LEGACY_AUTO"
                    else args.mode
                )
                request_id = uuid.uuid4().hex
                started = time.perf_counter()
                response_data: dict[str, object] = {}
                error = None
                try:
                    response = await client.post(
                        "/internal/api/knowledge/retrievals",
                        json={
                            "retrievalRequestId": "tech50-" + request_id,
                            "runId": "tech50-" + request_id,
                            "userId": manifest["userId"],
                            "knowledgeBaseId": manifest["knowledgeBaseId"],
                            "query": case["question"],
                            "requestedMode": mode,
                            "topK": args.top_k,
                            "candidateK": args.candidate_k,
                        },
                    )
                    response.raise_for_status()
                    response_data = response.json()
                    if not isinstance(response_data.get("citations"), list):
                        raise ValueError("invalid citations response")
                except (httpx.HTTPError, ValueError) as exc:
                    error = type(exc).__name__
                elapsed = (time.perf_counter() - started) * 1000
                citations = cast(list[dict[str, object]], response_data.get("citations", []))
                groups = [EvidenceGroup.model_validate(g) for g in case["evidenceGroups"]]
                metrics = (
                    score_evidence_groups(
                        groups,
                        [cast(str, citation["chunkId"]) for citation in citations],
                        failed=error is not None,
                    )
                    if case["answerable"]
                    else None
                )
                return cast(
                    _CaseResult,
                    {
                        "caseId": case["caseId"],
                        "topic": case["topic"],
                        "category": case["category"],
                        "question": case["question"],
                        "answerable": case["answerable"],
                        "requestedMode": mode,
                        "latencyMs": elapsed,
                        "error": error,
                        **response_data,
                        "citations": citations,
                        "metrics": metrics,
                    },
                )

        results = await asyncio.gather(*(evaluate(c) for c in cases))
    report = {
        "schemaVersion": "tech50-evidence-groups.v1",
        "createdAt": datetime.now(UTC).isoformat(),
        "datasetSha256": digest,
        "freezeSha256": hashlib.sha256(args.freeze.read_bytes()).hexdigest(),
        "split": args.split,
        "requestedMode": args.mode,
        "candidateK": args.candidate_k,
        "topK": args.top_k,
        "concurrency": args.concurrency,
        "timeoutSeconds": args.timeout,
        "cacheMode": "no application query cache; ES/OS caches uncontrolled",
        "serverLabel": args.server_label,
        "metrics": summarize(results),
        "cases": results,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(json.dumps(report["metrics"], ensure_ascii=False))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument("--freeze", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--split", choices=["dev", "test", "negative"], required=True)
    parser.add_argument(
        "--mode", choices=["AUTO", "LEGACY_AUTO", "HYBRID", "KEYWORD", "VECTOR"], default="AUTO"
    )
    parser.add_argument("--candidate-k", type=int, choices=[20, 30, 50], default=30)
    parser.add_argument("--top-k", type=int, choices=[10, 20, 30, 50], default=10)
    parser.add_argument("--concurrency", type=int, default=4)
    parser.add_argument("--timeout", type=float, default=35)
    parser.add_argument("--base-url", default="http://127.0.0.1:8989")
    parser.add_argument("--server-label", required=True)
    args = parser.parse_args()
    if args.top_k > args.candidate_k or args.concurrency < 1 or args.timeout <= 0:
        parser.error("invalid bounds")
    asyncio.run(run(args))


if __name__ == "__main__":
    main()
