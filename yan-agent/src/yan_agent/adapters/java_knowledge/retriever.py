from yan_agent.adapters.java_knowledge.client import JavaKnowledgeClient
from yan_agent.adapters.java_knowledge.contracts import (
    InternalRetrievalRequest,
)
from yan_agent.adapters.java_knowledge.contracts import (
    RetrievalMode as JavaRetrievalMode,
)
from yan_agent.domain.qa import (
    KnowledgeCitation,
    KnowledgeRetrievalRequest,
    KnowledgeRetrievalResult,
    RetrievalMode,
)


class JavaKnowledgeRetriever:
    def __init__(self, client: JavaKnowledgeClient) -> None:
        self._client = client

    async def retrieve(self, request: KnowledgeRetrievalRequest) -> KnowledgeRetrievalResult:
        response = await self._client.retrieve(
            InternalRetrievalRequest(
                retrieval_request_id=request.retrieval_request_id,
                run_id=request.run_id,
                user_id=request.user_id,
                knowledge_base_id=request.knowledge_base_id,
                query=request.query,
                requested_mode=JavaRetrievalMode(request.requested_mode.value),
            )
        )
        citations = [
            KnowledgeCitation(
                index=index,
                chunk_id=item.chunk_id,
                document_id=item.document_id,
                document_version_id=item.document_version_id,
                title=item.title,
                section=item.section,
                source=item.source,
                content=item.content,
                final_score=item.final_score,
            )
            for index, item in enumerate(response.citations, start=1)
        ]
        return KnowledgeRetrievalResult(
            query_id=response.query_id,
            selected_mode=RetrievalMode(response.selected_mode.value),
            citations=citations,
            degraded=response.degraded,
            degradation_reasons=[reason.value for reason in response.degradation_reasons],
        )
