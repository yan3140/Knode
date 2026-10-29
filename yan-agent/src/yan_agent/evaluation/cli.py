import argparse
import json
import sys
from pathlib import Path

from yan_agent.adapters.java_knowledge.client import JavaKnowledgeClient
from yan_agent.adapters.java_knowledge.contracts import RetrievalMode
from yan_agent.adapters.model.wenwen_responses import WenwenResponsesModel
from yan_agent.application.adaptive_retrieval_router import (
    AdaptiveRetrievalRouter,
    AdaptiveRoutingPolicy,
)
from yan_agent.application.question_analysis import StructuredQuestionAnalyzer
from yan_agent.application.retrieval_planning import (
    MultiQueryPlanner,
    SingleRewritePlanner,
)
from yan_agent.application.unified_retrieval_planning import UnifiedRetrievalPlanner
from yan_agent.asyncio_runtime import run
from yan_agent.evaluation.adaptive_comparison_report import (
    compare_adaptive_retrieval,
    write_adaptive_comparison,
)
from yan_agent.evaluation.adaptive_routing_runner import AdaptiveEvaluationPlanner
from yan_agent.evaluation.comparison_report import (
    compare_retrieval_reports,
    load_live_retrieval_report,
    write_live_retrieval_report,
    write_retrieval_comparison,
)
from yan_agent.evaluation.complex_comparison_report import (
    DEFAULT_COMPLEX_CATEGORIES,
    compare_complex_retrieval,
    write_complex_comparison,
)
from yan_agent.evaluation.live_retrieval_runner import (
    LiveEvaluationPlanner,
    LiveRetrievalReport,
    evaluate_live_dataset,
)
from yan_agent.evaluation.planning_runner import (
    PlanningSmokeReport,
    evaluate_planning_smoke,
    write_planning_smoke_report,
)
from yan_agent.evaluation.protocol import RetrievalStrategy, load_protocol
from yan_agent.evaluation.routing_annotations import load_routing_annotations
from yan_agent.evaluation.runner import evaluate_dataset, quality_gate
from yan_agent.settings import Settings


def main() -> None:
    arguments = sys.argv[1:]
    if arguments and arguments[0] == "retrieval":
        _main_live_retrieval(arguments[1:])
        return
    if arguments and arguments[0] == "compare":
        _main_compare(arguments[1:])
        return
    if arguments and arguments[0] == "planning":
        _main_planning(arguments[1:])
        return
    if arguments and arguments[0] == "adaptive-compare":
        _main_adaptive_compare(arguments[1:])
        return
    if arguments and arguments[0] == "complex-compare":
        _main_complex_compare(arguments[1:])
        return
    _main_scripted(arguments)


def _main_scripted(cli_arguments: list[str]) -> None:
    parser = argparse.ArgumentParser(description="Run deterministic Yan Agent evaluations")
    parser.add_argument(
        "--dataset",
        type=Path,
        default=Path("evals/datasets/smoke_v1.jsonl"),
    )
    parser.add_argument(
        "--baseline",
        type=Path,
        default=Path("evals/baselines/smoke_v1.json"),
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("evals/reports/current.json"),
    )
    arguments = parser.parse_args(cli_arguments)
    report = run(evaluate_dataset(arguments.dataset))
    arguments.output.parent.mkdir(parents=True, exist_ok=True)
    arguments.output.write_text(
        report.model_dump_json(indent=2),
        encoding="utf-8",
    )
    failures = quality_gate(report, arguments.baseline)
    summary = {
        "caseCount": report.case_count,
        "metrics": report.metrics,
        "qualityGate": "FAILED" if failures else "PASSED",
        "failures": failures,
        "report": str(arguments.output),
    }
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    if failures:
        raise SystemExit(1)


def _main_live_retrieval(cli_arguments: list[str]) -> None:
    parser = argparse.ArgumentParser(
        prog="yan-agent-eval retrieval",
        description="Run retrieval-only evaluation against the live Java knowledge API",
    )
    parser.add_argument(
        "--dataset",
        type=Path,
        default=Path("evals/datasets/mysql_live_v1.dev.jsonl"),
    )
    parser.add_argument(
        "--dataset-manifest",
        type=Path,
        default=Path("evals/datasets/mysql_live_v1.manifest.json"),
    )
    parser.add_argument(
        "--knowledge-manifest",
        type=Path,
        default=Path("evals/manifests/mysql_kb_v1.json"),
    )
    parser.add_argument(
        "--protocol",
        type=Path,
        default=Path("evals/protocols/multi_query_v1.json"),
    )
    parser.add_argument(
        "--strategy",
        choices=[
            RetrievalStrategy.RAW_QUERY.value,
            RetrievalStrategy.SINGLE_REWRITE.value,
            RetrievalStrategy.MULTI_QUERY.value,
            "ADAPTIVE",
        ],
        default=RetrievalStrategy.RAW_QUERY.value,
    )
    parser.add_argument(
        "--requested-mode",
        choices=[mode.value for mode in RetrievalMode],
        default=RetrievalMode.AUTO.value,
    )
    parser.add_argument("--user-id", type=int, required=True)
    parser.add_argument("--top-k", type=int)
    parser.add_argument("--candidate-k", type=int)
    parser.add_argument("--max-concurrency", type=int)
    parser.add_argument("--experiment-id")
    parser.add_argument("--git-commit")
    parser.add_argument("--base-url")
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("evals/reports/mysql_raw_v1.json"),
    )
    parser.add_argument("--markdown-output", type=Path)
    arguments = parser.parse_args(cli_arguments)
    settings = Settings()
    strategy = (
        arguments.strategy
        if arguments.strategy == "ADAPTIVE"
        else RetrievalStrategy(arguments.strategy)
    )
    service_key = settings.java_service_key.get_secret_value()
    if not service_key:
        parser.error("YAN_AGENT_JAVA_SERVICE_KEY must be set for live retrieval evaluation")
    model_key = settings.model_api_key.get_secret_value()
    protocol = load_protocol(arguments.protocol)
    if strategy in {
        RetrievalStrategy.SINGLE_REWRITE,
        RetrievalStrategy.MULTI_QUERY,
        "ADAPTIVE",
    }:
        if not model_key:
            parser.error("YAN_AGENT_MODEL_API_KEY must be set for SINGLE_REWRITE evaluation")
        if settings.model_name != protocol.models.planner_model:
            parser.error(
                "YAN_AGENT_MODEL_NAME must match the frozen protocol plannerModel "
                f"({protocol.models.planner_model})"
            )
    markdown_output = arguments.markdown_output or arguments.output.with_suffix(".md")

    async def execute() -> LiveRetrievalReport:
        async with JavaKnowledgeClient(
            base_url=arguments.base_url or settings.java_base_url,
            service_key=service_key,
        ) as client:

            async def evaluate(
                planner: LiveEvaluationPlanner | None,
            ) -> LiveRetrievalReport:
                return await evaluate_live_dataset(
                    dataset_path=arguments.dataset,
                    dataset_manifest_path=arguments.dataset_manifest,
                    knowledge_manifest_path=arguments.knowledge_manifest,
                    protocol_path=arguments.protocol,
                    client=client,
                    user_id=arguments.user_id,
                    strategy=strategy,
                    requested_mode=RetrievalMode(arguments.requested_mode),
                    top_k=arguments.top_k,
                    candidate_k=arguments.candidate_k,
                    max_concurrency=arguments.max_concurrency,
                    max_context_chars=settings.agent_budget.max_context_chars,
                    experiment_id=arguments.experiment_id,
                    git_commit=arguments.git_commit,
                    planner=planner,
                )

            if strategy is RetrievalStrategy.RAW_QUERY:
                return await evaluate(None)
            async with WenwenResponsesModel(
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
            ) as model:
                planner: LiveEvaluationPlanner
                if strategy == "ADAPTIVE":
                    planner = AdaptiveEvaluationPlanner(
                        AdaptiveRetrievalRouter(
                            AdaptiveRoutingPolicy(
                                shadow_mode=False,
                                multi_query_enabled=True,
                                multi_query_rollout_percent=100,
                            )
                        ),
                        UnifiedRetrievalPlanner(
                            StructuredQuestionAnalyzer(model),
                            SingleRewritePlanner(model),
                        ),
                    )
                elif strategy is RetrievalStrategy.MULTI_QUERY:
                    planner = MultiQueryPlanner(model)
                else:
                    planner = SingleRewritePlanner(model)
                return await evaluate(planner)

    report = run(execute())
    write_live_retrieval_report(report, arguments.output, markdown_output)
    summary = {
        "experimentId": report.experiment_id,
        "caseCount": report.case_count,
        "successfulCaseCount": report.metrics.successful_case_count,
        "errorCount": report.metrics.error_count,
        "metrics": report.metrics.model_dump(by_alias=True, mode="json"),
        "jsonReport": str(arguments.output),
        "markdownReport": str(markdown_output),
    }
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    if report.metrics.error_count:
        raise SystemExit(2)


def _main_compare(cli_arguments: list[str]) -> None:
    parser = argparse.ArgumentParser(
        prog="yan-agent-eval compare",
        description="Compare paired RAW_QUERY and SINGLE_REWRITE retrieval reports",
    )
    parser.add_argument("--reports", type=Path, nargs=2, required=True)
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("evals/reports/mysql_ab_comparison_v1.md"),
    )
    parser.add_argument("--json-output", type=Path)
    arguments = parser.parse_args(cli_arguments)
    reports = [load_live_retrieval_report(path) for path in arguments.reports]
    comparison = compare_retrieval_reports(reports)
    json_output = arguments.json_output or arguments.output.with_suffix(".json")
    write_retrieval_comparison(comparison, json_output, arguments.output)
    summary = {
        "caseCount": comparison.case_count,
        "baseline": comparison.baseline_experiment_id,
        "candidate": comparison.candidate_experiment_id,
        "recallAt10Delta": comparison.metric_deltas["recall_at_10"].absolute_delta,
        "mrrAt10Delta": comparison.metric_deltas["reciprocal_rank_at_10"].absolute_delta,
        "jsonReport": str(json_output),
        "markdownReport": str(arguments.output),
    }
    print(json.dumps(summary, ensure_ascii=False, indent=2))


def _main_planning(cli_arguments: list[str]) -> None:
    parser = argparse.ArgumentParser(
        prog="yan-agent-eval planning",
        description="Run a category-balanced Multi Query planning smoke evaluation",
    )
    parser.add_argument(
        "--dataset",
        type=Path,
        default=Path("evals/datasets/mysql_live_v1.dev.jsonl"),
    )
    parser.add_argument(
        "--knowledge-manifest",
        type=Path,
        default=Path("evals/manifests/mysql_kb_v1.json"),
    )
    parser.add_argument("--per-category", type=int, default=1)
    parser.add_argument("--max-concurrency", type=int, default=4)
    parser.add_argument("--experiment-id", default="phase4-planning-smoke")
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("evals/reports/mysql_multi_phase4_planning.json"),
    )
    arguments = parser.parse_args(cli_arguments)
    settings = Settings()
    model_key = settings.model_api_key.get_secret_value()
    if not model_key:
        parser.error(
            "OPENAI_API_KEY or YAN_AGENT_MODEL_API_KEY must be set for planning evaluation"
        )

    async def execute() -> PlanningSmokeReport:
        async with WenwenResponsesModel(
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
        ) as model:
            planner = MultiQueryPlanner(model)
            return await evaluate_planning_smoke(
                dataset_path=arguments.dataset,
                knowledge_manifest_path=arguments.knowledge_manifest,
                planner=planner,
                planner_model=settings.model_name,
                planner_version=planner.planner_version,
                prompt_version=planner.prompt_version,
                experiment_id=arguments.experiment_id,
                per_category=arguments.per_category,
                max_concurrency=arguments.max_concurrency,
                max_context_chars=settings.agent_budget.max_context_chars,
            )

    report = run(execute())
    write_planning_smoke_report(report, arguments.output)
    print(
        json.dumps(
            {
                "experimentId": report.experiment_id,
                "caseCount": report.metrics.case_count,
                "successfulCaseCount": report.metrics.successful_case_count,
                "errorCount": report.metrics.error_count,
                "averageQueryCount": report.metrics.average_query_count,
                "latencyP95Millis": report.metrics.latency_p95_millis,
                "report": str(arguments.output),
            },
            ensure_ascii=False,
            indent=2,
        )
    )
    if report.metrics.error_count:
        raise SystemExit(2)


def _main_adaptive_compare(cli_arguments: list[str]) -> None:
    parser = argparse.ArgumentParser(
        prog="yan-agent-eval adaptive-compare",
        description="Compare fixed A/B/C reports with adaptive D reports",
    )
    parser.add_argument("--raw-reports", type=Path, nargs="+", required=True)
    parser.add_argument("--single-reports", type=Path, nargs="+", required=True)
    parser.add_argument("--multi-reports", type=Path, nargs="+", required=True)
    parser.add_argument("--adaptive-reports", type=Path, nargs="+", required=True)
    parser.add_argument(
        "--routing-annotations",
        type=Path,
        default=Path("evals/datasets/mysql_live_v1.routing.dev.jsonl"),
    )
    parser.add_argument("--require-repeated-runs", action="store_true")
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("evals/reports/mysql_abcd_stage55e_dev.md"),
    )
    parser.add_argument("--json-output", type=Path)
    arguments = parser.parse_args(cli_arguments)
    fixed = {
        RetrievalStrategy.RAW_QUERY: [
            load_live_retrieval_report(path) for path in arguments.raw_reports
        ],
        RetrievalStrategy.SINGLE_REWRITE: [
            load_live_retrieval_report(path) for path in arguments.single_reports
        ],
        RetrievalStrategy.MULTI_QUERY: [
            load_live_retrieval_report(path) for path in arguments.multi_reports
        ],
    }
    adaptive = [load_live_retrieval_report(path) for path in arguments.adaptive_reports]
    report = compare_adaptive_retrieval(
        fixed_reports=fixed,
        adaptive_reports=adaptive,
        annotations=load_routing_annotations(arguments.routing_annotations),
        require_repeated_runs=arguments.require_repeated_runs,
    )
    json_output = arguments.json_output or arguments.output.with_suffix(".json")
    write_adaptive_comparison(report, json_output, arguments.output)
    print(
        json.dumps(
            {
                "caseCount": report.case_count,
                "qualityGate": "PASSED" if report.quality_gate_passed else "FAILED",
                "meanRetrievalRegret": report.mean_retrieval_regret,
                "routingMetrics": report.routing_metrics.model_dump(
                    by_alias=True,
                    mode="json",
                ),
                "jsonReport": str(json_output),
                "markdownReport": str(arguments.output),
            },
            ensure_ascii=False,
            indent=2,
        )
    )


def _main_complex_compare(cli_arguments: list[str]) -> None:
    parser = argparse.ArgumentParser(
        prog="yan-agent-eval complex-compare",
        description="Compare repeated B/C retrieval runs on frozen complex cases",
    )
    parser.add_argument("--single-reports", type=Path, nargs="+", required=True)
    parser.add_argument("--multi-reports", type=Path, nargs="+", required=True)
    parser.add_argument(
        "--categories",
        nargs="+",
        default=list(DEFAULT_COMPLEX_CATEGORIES),
    )
    parser.add_argument("--allow-single-run", action="store_true")
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("evals/reports/mysql_bc_stage55g_complex_dev.md"),
    )
    parser.add_argument("--json-output", type=Path)
    arguments = parser.parse_args(cli_arguments)
    report = compare_complex_retrieval(
        baseline_reports=[load_live_retrieval_report(path) for path in arguments.single_reports],
        candidate_reports=[load_live_retrieval_report(path) for path in arguments.multi_reports],
        categories=tuple(arguments.categories),
        require_repeated_runs=not arguments.allow_single_run,
    )
    json_output = arguments.json_output or arguments.output.with_suffix(".json")
    write_complex_comparison(report, json_output, arguments.output)
    print(
        json.dumps(
            {
                "caseCount": report.case_count,
                "categories": report.categories,
                "qualityGate": "PASSED" if report.quality_gate_passed else "FAILED",
                "recommendation": report.recommendation,
                "qualityGates": [
                    gate.model_dump(by_alias=True, mode="json") for gate in report.quality_gates
                ],
                "jsonReport": str(json_output),
                "markdownReport": str(arguments.output),
            },
            ensure_ascii=False,
            indent=2,
        )
    )
