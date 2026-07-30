from __future__ import annotations

from typing import Optional

from fastapi import APIRouter, Depends, HTTPException, Query
from google.genai import types

from adkpoc.api.context import ServerContext, get_context

router = APIRouter()


@router.get(
    "/apps/{app_name}/users/{user_id}/sessions/{session_id}/artifacts/{artifact_name}",
    response_model_exclude_none=True,
)
async def load_artifact(
    app_name: str,
    user_id: str,
    session_id: str,
    artifact_name: str,
    version: Optional[int] = Query(None),
    ctx: ServerContext = Depends(get_context),
) -> Optional[types.Part]:
    artifact = await ctx.artifact_service.load_artifact(
        app_name=app_name,
        user_id=user_id,
        session_id=session_id,
        filename=artifact_name,
        version=version,
    )
    if not artifact:
        raise HTTPException(status_code=404, detail="Artifact not found")
    return artifact


@router.get(
    "/apps/{app_name}/users/{user_id}/sessions/{session_id}/artifacts/{artifact_name}/versions/{version_id}",
    response_model_exclude_none=True,
)
async def load_artifact_version(
    app_name: str,
    user_id: str,
    session_id: str,
    artifact_name: str,
    version_id: int,
    ctx: ServerContext = Depends(get_context),
) -> Optional[types.Part]:
    artifact = await ctx.artifact_service.load_artifact(
        app_name=app_name,
        user_id=user_id,
        session_id=session_id,
        filename=artifact_name,
        version=version_id,
    )
    if not artifact:
        raise HTTPException(status_code=404, detail="Artifact not found")
    return artifact


@router.get(
    "/apps/{app_name}/users/{user_id}/sessions/{session_id}/artifacts",
    response_model_exclude_none=True,
)
async def list_artifact_names(
    app_name: str,
    user_id: str,
    session_id: str,
    ctx: ServerContext = Depends(get_context),
) -> list[str]:
    return await ctx.artifact_service.list_artifact_keys(
        app_name=app_name, user_id=user_id, session_id=session_id
    )


@router.get(
    "/apps/{app_name}/users/{user_id}/sessions/{session_id}/artifacts/{artifact_name}/versions",
    response_model_exclude_none=True,
)
async def list_artifact_versions(
    app_name: str,
    user_id: str,
    session_id: str,
    artifact_name: str,
    ctx: ServerContext = Depends(get_context),
) -> list[int]:
    return await ctx.artifact_service.list_versions(
        app_name=app_name,
        user_id=user_id,
        session_id=session_id,
        filename=artifact_name,
    )


@router.delete(
    "/apps/{app_name}/users/{user_id}/sessions/{session_id}/artifacts/{artifact_name}",
)
async def delete_artifact(
    app_name: str,
    user_id: str,
    session_id: str,
    artifact_name: str,
    ctx: ServerContext = Depends(get_context),
) -> None:
    await ctx.artifact_service.delete_artifact(
        app_name=app_name,
        user_id=user_id,
        session_id=session_id,
        filename=artifact_name,
    )
