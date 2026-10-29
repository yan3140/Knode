import re

from yan_agent.domain.qa import KnowledgeCitation
from yan_agent.domain.security import PreparedEvidence

PROMPT_INJECTION_DETECTED = "PROMPT_INJECTION_DETECTED"
EVIDENCE_TRUNCATED = "EVIDENCE_TRUNCATED"

_INJECTION_PATTERNS = (
    re.compile(r"忽略.{0,20}(?:指令|规则|提示|要求)", re.IGNORECASE),
    re.compile(r"(?:泄露|显示|输出|透露).{0,20}(?:系统提示词|系统指令|密钥|秘密)", re.IGNORECASE),
    re.compile(r"你现在是.{0,40}(?:助手|模型|系统|开发者)", re.IGNORECASE),
    re.compile(r"ignore.{0,40}(?:previous|prior|system).{0,20}instructions?", re.IGNORECASE),
    re.compile(
        r"(?:reveal|show|print|leak).{0,30}(?:system prompt|instructions?|secret|key)",
        re.IGNORECASE,
    ),
    re.compile(r"you are now.{0,40}(?:assistant|system|developer|model)", re.IGNORECASE),
    re.compile(r"<\s*(?:system|assistant|developer)(?:\s|>)", re.IGNORECASE),
)


class EvidenceGuard:
    def __init__(self, *, max_total_chars: int, max_citation_chars: int) -> None:
        if max_total_chars <= 0 or max_citation_chars <= 0:
            raise ValueError("evidence limits must be positive")
        self._max_total_chars = max_total_chars
        self._max_citation_chars = max_citation_chars

    def prepare(self, citations: list[KnowledgeCitation]) -> PreparedEvidence:
        flags: list[str] = []
        prepared: list[KnowledgeCitation] = []
        remaining = self._max_total_chars
        original_chars = sum(len(citation.content) for citation in citations)

        for citation in citations:
            clean_fields: dict[str, str] = {}
            injection_found = False
            for field in ("title", "section", "source", "content"):
                clean_value, field_injection = self._remove_instructions(getattr(citation, field))
                clean_fields[field] = clean_value
                injection_found = injection_found or field_injection
            if injection_found:
                self._add_flag(flags, PROMPT_INJECTION_DETECTED)

            clean_content = clean_fields["content"]
            allowed = min(self._max_citation_chars, remaining)
            if len(clean_content) > allowed:
                clean_content = clean_content[:allowed]
                self._add_flag(flags, EVIDENCE_TRUNCATED)
            if remaining <= 0 and clean_content:
                clean_content = ""
                self._add_flag(flags, EVIDENCE_TRUNCATED)

            remaining -= len(clean_content)
            clean_fields["content"] = clean_content
            prepared.append(citation.model_copy(update=clean_fields))

        return PreparedEvidence(
            citations=prepared,
            flags=flags,
            original_chars=original_chars,
            prepared_chars=sum(len(citation.content) for citation in prepared),
        )

    @staticmethod
    def _remove_instructions(content: str) -> tuple[str, bool]:
        clean_lines: list[str] = []
        injection_found = False
        for line in content.splitlines():
            if any(pattern.search(line) for pattern in _INJECTION_PATTERNS):
                injection_found = True
                continue
            clean_lines.append(line)
        return "\n".join(clean_lines), injection_found

    @staticmethod
    def _add_flag(flags: list[str], flag: str) -> None:
        if flag not in flags:
            flags.append(flag)
