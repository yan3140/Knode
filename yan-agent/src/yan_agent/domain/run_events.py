from yan_agent.domain.qa import AgentResult

RunEventSpec = tuple[str, dict[str, object]]


def completion_event_specs(result: AgentResult) -> list[RunEventSpec]:
    return [
        (
            "retrieval.completed",
            {
                "queryIds": result.query_ids,
                "selectedMode": result.selected_mode.value if result.selected_mode else None,
                "degraded": result.degraded,
                "degradationReasons": result.degradation_reasons,
                "retrievalAttempts": result.retrieval_attempts,
                "routingDecision": (
                    result.routing_decision.model_dump(mode="json", by_alias=True)
                    if result.routing_decision
                    else None
                ),
            },
        ),
        (
            "answer.delta",
            {
                "delta": result.answer,
                "final": True,
                "groundingRepaired": result.grounding_repaired,
            },
        ),
        (
            "citations.final",
            {"citations": [citation.model_dump(mode="json") for citation in result.citations]},
        ),
        (
            "run.completed",
            {
                "status": result.status.value,
                "queryIds": result.query_ids,
                "selectedMode": (result.selected_mode.value if result.selected_mode else None),
                "degraded": result.degraded,
                "degradationReasons": result.degradation_reasons,
                "securityFlags": result.security_flags,
                "groundingRepaired": result.grounding_repaired,
                "groundingIssues": result.grounding_issues,
                "modelCalls": result.model_calls,
                "routingDecision": (
                    result.routing_decision.model_dump(mode="json", by_alias=True)
                    if result.routing_decision
                    else None
                ),
            },
        ),
    ]
