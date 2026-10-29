from datetime import UTC, datetime, timedelta

import jwt

from yan_agent.security.principal_token import PrincipalTokenVerifier


def test_verifies_java_principal_claims() -> None:
    secret = "0123456789abcdef0123456789abcdef"
    now = datetime.now(UTC)
    token = jwt.encode(
        {
            "aud": ["yan-agent"],
            "exp": now + timedelta(seconds=60),
            "iat": now,
            "iss": "yanblog",
            "jti": "principal-1",
            "knowledgeBaseId": 5,
            "sub": "3",
        },
        secret,
        algorithm="HS256",
    )

    principal = PrincipalTokenVerifier(secret).verify(token)

    assert principal.user_id == 3
    assert principal.knowledge_base_id == 5
    assert principal.token_id == "principal-1"
