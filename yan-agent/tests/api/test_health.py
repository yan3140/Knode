import httpx
import pytest

from yan_agent.main import create_app


@pytest.mark.asyncio
async def test_liveness_reports_service_up() -> None:
    transport = httpx.ASGITransport(app=create_app())
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.get("/health/live")

    assert response.status_code == 200
    assert response.json() == {"service": "yan-agent", "status": "UP"}


class RedisUnavailableProbe:
    async def check(self) -> dict[str, bool]:
        return {"postgres": True, "redis": False}


@pytest.mark.asyncio
async def test_readiness_degrades_but_stays_available_when_redis_is_down() -> None:
    transport = httpx.ASGITransport(app=create_app(readiness_probe=RedisUnavailableProbe()))
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.get("/health/ready")

    assert response.status_code == 200
    assert response.json() == {
        "dependencies": {"postgres": "UP", "redis": "DOWN"},
        "service": "yan-agent",
        "status": "DEGRADED",
    }


class PostgresUnavailableProbe:
    async def check(self) -> dict[str, bool]:
        return {"postgres": False, "redis": True}


@pytest.mark.asyncio
async def test_readiness_fails_when_postgres_is_down() -> None:
    transport = httpx.ASGITransport(app=create_app(readiness_probe=PostgresUnavailableProbe()))
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.get("/health/ready")

    assert response.status_code == 503
    assert response.json()["status"] == "DOWN"
