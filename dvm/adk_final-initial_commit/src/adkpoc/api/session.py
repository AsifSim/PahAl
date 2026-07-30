from __future__ import annotations

from typing import Any, Optional

from fastapi import APIRouter, Depends, HTTPException
from google.adk.cli.cli_eval import EVAL_SESSION_ID_PREFIX
from google.adk.cli.utils import common
from google.adk.events.event import Event
from google.adk.sessions.session import Session
from pydantic import Field

from adkpoc.api.context import ServerContext, get_context
# from adkpoc.core.registry import ContentRegistry
from adkpoc.constants.agent import ADK_USER

router = APIRouter()

# initial_state={"Action_Type_Options": ["Data Ingestion", "Data Processing", "Data Analysis"], "Workflow_Name_Options": ["Standard Workflow", "Custom Workflow"]}

initial_state={"Action_Type_Options": ["Data Ingestion", "Data Processing", "Data Analysis"], "Workflow_Name_Options": ["Standard Workflow", "Custom Workflow"], "current_selected_action_type": None, "current_selected_workflow_name": None, "current_file_path": None}
class CreateSessionRequest(common.BaseModel):
    session_id: Optional[str] = Field(
        default=None,
        description=(
            "The ID of the session to create. If not provided, a random session"
            " ID will be generated."
        ),
    )
    state: Optional[dict[str, Any]] = Field(
        default=None, description="The initial state of the session."
    )
    events: Optional[list[Event]] = Field(
        default=None,
        description="A list of events to initialize the session with.",
    )


@router.get(
    "/apps/{app_name}/users/{user_id}/sessions/{session_id}",
    response_model_exclude_none=True,
)
async def get_session(
    app_name: str,
    user_id: str,
    session_id: str,
    ctx: ServerContext = Depends(get_context),
) -> Session:
    session = await ctx.session_service.get_session(
        app_name=app_name, user_id=user_id, session_id=session_id
    )
    if not session:
        raise HTTPException(status_code=404, detail="Session not found")
    ctx.current_app_name_ref.value = app_name
    return session


@router.get(
    "/apps/{app_name}/users/{user_id}/sessions",
    response_model_exclude_none=True,
)
async def list_sessions(
    app_name: str,
    user_id: str,
    ctx: ServerContext = Depends(get_context),
) -> list[Session]:
    list_sessions_response = await ctx.session_service.list_sessions(
        app_name=app_name, user_id=user_id
    )
    return [
        session
        for session in list_sessions_response.sessions
        if not session.id.startswith(EVAL_SESSION_ID_PREFIX)
    ]


@router.post(
    "/apps/{app_name}/users/{user_id}/sessions",
    response_model_exclude_none=True,
)


async def create_session(
        app_name: str,
        user_id: str,
        req: Optional[CreateSessionRequest] = None,
        ctx: ServerContext = Depends(get_context),
    ) -> Session:
      if not req:
        return await ctx.session_service.create_session(
            app_name=app_name, user_id=user_id,state=initial_state
        )

      session = await ctx.session_service.create_session(
          app_name=app_name,
          user_id=user_id,
          state={**req.state, **initial_state} if req.state else initial_state,
          session_id=req.session_id,
      )

      if req.events:
        for event in req.events:
          await ctx.session_service.append_event(session=session, event=event)

      return session

@router.delete("/apps/{app_name}/users/{user_id}/sessions/{session_id}")
async def delete_session(
    app_name: str,
    user_id: str,
    session_id: str,
    ctx: ServerContext = Depends(get_context),
) -> None:
    session_kwargs = dict(app_name=app_name, user_id=user_id, session_id=session_id)
    await ctx.session_service.delete_session(**session_kwargs)
    # delete all artifacts for this
    artifact_keys = await ctx.artifact_service.list_artifact_keys(**session_kwargs)
    for artifact_key in artifact_keys:
        await ctx.artifact_service.delete_artifact(filename=artifact_key, **session_kwargs )