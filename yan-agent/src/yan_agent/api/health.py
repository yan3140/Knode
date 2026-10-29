from typing import Literal

from fastapi import APIRouter, Response, status
from pydantic import BaseModel

from yan_agent.ports.readiness import ReadinessProbe

DependencyStatus = Literal["UP", "DOWN"]
ServiceStatus = Literal["UP", "DEGRADED", "DOWN"]


class LivenessResponse(BaseModel):
    service: Literal["yan-agent"] = "yan-agent"
    status: Literal["UP"] = "UP"


class ReadinessResponse(BaseModel):
    service: Literal["yan-agent"] = "yan-agent"
    status: ServiceStatus
    dependencies: dict[str, DependencyStatus]


def create_health_router(readiness_probe: ReadinessProbe) -> APIRouter:
    router = APIRouter(prefix="/health", tags=["health"])

    @router.get("/live", response_model=LivenessResponse)
    async def liveness() -> LivenessResponse:
        return LivenessResponse()

    @router.get("/ready", response_model=ReadinessResponse)
    async def readiness(response: Response) -> ReadinessResponse:
        checks = await readiness_probe.check()
        dependencies: dict[str, DependencyStatus] = {
            name: "UP" if available else "DOWN" for name, available in checks.items()
        }
        if not checks.get("postgres", False):
            response.status_code = status.HTTP_503_SERVICE_UNAVAILABLE
            service_status: ServiceStatus = "DOWN"
        elif not checks.get("redis", False):
            service_status = "DEGRADED"
        else:
            service_status = "UP"
        return ReadinessResponse(status=service_status, dependencies=dependencies)

    return router
