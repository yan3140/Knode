import asyncio
from collections.abc import Awaitable
from typing import cast

import psycopg
from redis.asyncio import Redis
from redis.exceptions import RedisError

from yan_agent.settings import Settings


class InfrastructureReadinessProbe:
    def __init__(self, settings: Settings) -> None:
        self._settings = settings

    async def check(self) -> dict[str, bool]:
        postgres, redis = await asyncio.gather(
            self._postgres_available(),
            self._redis_available(),
        )
        return {"postgres": postgres, "redis": redis}

    async def _postgres_available(self) -> bool:
        try:
            connection = await psycopg.AsyncConnection.connect(
                self._settings.postgres_dsn,
                connect_timeout=self._settings.dependency_timeout_seconds,
            )
            async with connection:
                await connection.execute("SELECT 1")
            return True
        except (OSError, psycopg.Error, TimeoutError):
            return False

    async def _redis_available(self) -> bool:
        client = Redis.from_url(
            self._settings.redis_url,
            socket_connect_timeout=self._settings.dependency_timeout_seconds,
            socket_timeout=self._settings.dependency_timeout_seconds,
        )
        try:
            result = await cast(Awaitable[object], client.ping())
            return bool(result)
        except (OSError, RedisError, TimeoutError):
            return False
        finally:
            await client.aclose()
