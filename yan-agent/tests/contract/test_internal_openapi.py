import json
from pathlib import Path

from yan_agent.adapters.java_knowledge.contracts import (
    InternalRetrievalRequest,
    InternalRetrievalResponse,
)


def test_internal_retrieval_examples_match_python_contracts() -> None:
    contract_path = (
        Path(__file__).resolve().parents[3] / "contracts" / "knowledge-agent-internal.openapi.json"
    )
    contract = json.loads(contract_path.read_text(encoding="utf-8"))
    schemas = contract["components"]["schemas"]

    request = InternalRetrievalRequest.model_validate(
        schemas["InternalRetrievalRequest"]["example"]
    )
    response = InternalRetrievalResponse.model_validate(
        schemas["InternalRetrievalResponse"]["example"]
    )

    assert request.knowledge_base_id == 5
    assert response.citations[0].document_version_id == "version-2"
