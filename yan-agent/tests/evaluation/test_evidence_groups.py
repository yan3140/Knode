import pytest
from pydantic import ValidationError

from yan_agent.evaluation.evidence_groups import EvidenceGroup, score_evidence_groups


def test_equivalent_chunks_across_documents_are_alternatives():
    groups = [EvidenceGroup(fact="同一事实", alternatives=[["1:2:0"], ["8:9:3"]])]
    result = score_evidence_groups(groups, ["8:9:3"])
    assert result["evidenceGroupRecallAt10"] == 1
    assert result["completeEvidenceHitAt10"] == 1
    assert result["strictChunkRecallAt10"] == 0.5


def test_partial_answer_and_multi_chunk_sufficient_set():
    groups = [
        EvidenceGroup(fact="是什么", alternatives=[["a"]]),
        EvidenceGroup(fact="为什么", alternatives=[["b", "c"], ["d"]]),
    ]
    partial = score_evidence_groups(groups, ["a", "b"])
    assert partial["hitAt10"] == 1
    assert partial["evidenceGroupRecallAt10"] == 0.5
    assert partial["completeEvidenceHitAt10"] == 0
    assert partial["missingFacts"] == ["为什么"]
    assert score_evidence_groups(groups, ["a", "b", "c"])["completeEvidenceHitAt10"] == 1
    assert score_evidence_groups(groups, ["a", "d"])["completeEvidenceHitAt10"] == 1


def test_top10_boundary_and_errors_do_not_disappear_from_quality():
    groups = [EvidenceGroup(fact="答案", alternatives=[["gold"]])]
    assert score_evidence_groups(groups, [str(i) for i in range(10)] + ["gold"])["hitAt10"] == 0
    assert score_evidence_groups(groups, ["gold"], failed=True)["evidenceGroupRecallAt10"] == 0
    assert score_evidence_groups(groups, ["gold", "gold"])["evidenceGroupRecallAt10"] == 1


@pytest.mark.parametrize("alternatives", [[], [[]], [[""]], [["a", "a"]], [["a"], ["a"]]])
def test_reject_malformed_gold(alternatives):
    with pytest.raises(ValidationError):
        EvidenceGroup(fact="事实", alternatives=alternatives)
