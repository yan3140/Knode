from dataclasses import dataclass

import jwt


class InvalidPrincipalToken(ValueError):
    pass


@dataclass(frozen=True)
class AgentPrincipal:
    user_id: int
    knowledge_base_id: int
    token_id: str


class PrincipalTokenVerifier:
    def __init__(self, secret: str) -> None:
        if len(secret.encode("utf-8")) < 32:
            raise ValueError("Agent principal JWT secret must contain at least 32 bytes")
        self._secret = secret

    def verify(self, token: str) -> AgentPrincipal:
        try:
            payload = jwt.decode(
                token,
                self._secret,
                algorithms=["HS256"],
                audience="yan-agent",
                issuer="yanblog",
                options={"require": ["aud", "exp", "iat", "iss", "jti", "sub"]},
            )
            user_id = int(payload["sub"])
            knowledge_base_id = payload["knowledgeBaseId"]
            token_id = payload["jti"]
        except (KeyError, TypeError, ValueError, jwt.PyJWTError) as error:
            raise InvalidPrincipalToken("Invalid Agent principal token") from error
        if (
            user_id < 1
            or not isinstance(knowledge_base_id, int)
            or isinstance(knowledge_base_id, bool)
            or knowledge_base_id < 1
            or not isinstance(token_id, str)
            or not token_id
        ):
            raise InvalidPrincipalToken("Invalid Agent principal claims")
        return AgentPrincipal(
            user_id=user_id,
            knowledge_base_id=knowledge_base_id,
            token_id=token_id,
        )
