from __future__ import annotations

import json
import traceback
from typing import Any, AsyncGenerator, Optional

from fastapi import APIRouter, Depends, HTTPException, WebSocket
from fastapi.responses import StreamingResponse
from fastapi.websockets import WebSocketDisconnect
from google.adk.agents.run_config import RunConfig, StreamingMode
from google.adk.cli.utils import common
from google.adk.events.event import Event
from google.adk.utils.context_utils import Aclosing
from google.genai import types
from pydantic import ValidationError

from adkpoc.api.context import ServerContext, get_context
from adkpoc.utils.my_logger import get_logger

logger = get_logger(__name__)
router = APIRouter()

import sys
logger.debug(f"PYTHON EXECUTABLE at agent.py ={sys.executable}")


class RunAgentRequest(common.BaseModel):
    app_name: str
    user_id: str
    session_id: str
    new_message: types.Content
    streaming: bool = True
    state_delta: Optional[dict[str, Any]] = None


async def _run_agent_events(
    ctx: ServerContext,
    app_name: str,
    user_id: str,
    session_id: str,
    new_message: types.Content,
    state_delta: Optional[dict[str, Any]] = None,
    stream_mode: StreamingMode = StreamingMode.NONE,
) -> AsyncGenerator[Event, None]:
    runner = await ctx.get_runner_async(app_name)
    async with Aclosing(
        runner.run_async(
            user_id=user_id,
            session_id=session_id,
            new_message=new_message,
            state_delta=state_delta,
            run_config=RunConfig(streaming_mode=stream_mode),
        )
    ) as agen:
        async for event in agen:
            yield event


@router.get("/list-apps")
async def list_apps(ctx: ServerContext = Depends(get_context)) -> list[str]:
    return ctx.agent_loader.list_agents()


@router.post("/run", response_model_exclude_none=True)
async def run_agent(
    req: RunAgentRequest, ctx: ServerContext = Depends(get_context)
) -> list[Event]:
    session = await ctx.session_service.get_session(
        app_name=req.app_name, user_id=req.user_id, session_id=req.session_id
    )
    if not session:
        raise HTTPException(status_code=404, detail="Session not found")

    events = [
        event
        async for event in _run_agent_events(
            ctx,
            req.app_name,
            req.user_id,
            req.session_id,
            req.new_message,
            req.state_delta,
        )
    ]
    logger.info("Generated %s events in agent run", len(events))
    logger.debug("Events generated: %s", events)
    return events


@router.post("/run_sse")
async def run_agent_sse(
    req: RunAgentRequest, ctx: ServerContext = Depends(get_context)
) -> StreamingResponse:
    logger.info("Method run_agent_sse invoked")
    session = await ctx.session_service.get_session(
        app_name=req.app_name, user_id=req.user_id, session_id=req.session_id
    )
    logger.info(f"Session = {session}")

    if not session:
        raise HTTPException(status_code=404, detail="Session not found")

    async def event_generator() -> AsyncGenerator[str, None]:
        try:
            stream_mode = StreamingMode.SSE if req.streaming else StreamingMode.NONE
            async for event in _run_agent_events(
                ctx,
                req.app_name,
                req.user_id,
                req.session_id,
                req.new_message,
                req.state_delta,
                stream_mode,
            ):
                sse_event = event.model_dump_json(exclude_none=True, by_alias=True)
                logger.debug("Generated event in agent run streaming: %s", sse_event)
                yield f"data: {sse_event}\n\n"
        except Exception as exc:  # noqa: BLE001
            logger.exception("Error in event_generator: %s", exc)
            yield f'data: {{"error": "{str(exc)}"}}\n\n'

    return StreamingResponse(event_generator(), media_type="text/event-stream")


@router.websocket("/run_live")
async def run_agent_live(
    websocket: WebSocket,
    app_name: str,
    user_id: str,
    session_id: str,
    ctx: ServerContext = Depends(get_context),
) -> None:
    await websocket.accept()

    base_request: RunAgentRequest | None = None

    async def ensure_session(req: RunAgentRequest) -> bool:
        session = await ctx.session_service.get_session(
            app_name=req.app_name, user_id=req.user_id, session_id=req.session_id
        )
        return session is not None

    while True:
        try:
            message = await websocket.receive_text()
        except WebSocketDisconnect:
            logger.info("Client disconnected before sending payload")
            return

        try:
            payload = json.loads(message)
        except json.JSONDecodeError as exc:
            logger.error("Invalid websocket payload: %s", exc)
            await _send_error(websocket, "Invalid payload: expected JSON", 1007)
            continue

        if base_request is None:
            payload.setdefault("app_name", app_name)
            payload.setdefault("user_id", user_id)
            payload.setdefault("session_id", session_id)
        else:
            payload.setdefault("app_name", base_request.app_name)
            payload.setdefault("user_id", base_request.user_id)
            payload.setdefault("session_id", base_request.session_id)

        try:
            req = RunAgentRequest.model_validate(payload)
        except ValidationError as exc:
            logger.error("Invalid websocket payload: %s", exc)
            await _send_error(websocket, "Invalid payload: schema validation failed", 1007)
            continue

        if (
            base_request is None
            or req.session_id != base_request.session_id
            or req.user_id != base_request.user_id
            or req.app_name != base_request.app_name
        ):
            if not await ensure_session(req):
                await _send_error(websocket, "Session not found", 1002)
                continue

        base_request = req

        try:
            async for event in _run_agent_events(
                ctx,
                req.app_name,
                req.user_id,
                req.session_id,
                new_message=req.new_message,
                state_delta=req.state_delta,
                stream_mode=StreamingMode.SSE if req.streaming else StreamingMode.NONE,
            ):
                payload = event.model_dump_json(exclude_none=True, by_alias=True)
                await websocket.send_text(payload)
        except WebSocketDisconnect:
            logger.info("Client disconnected during websocket streaming")
            return
        except Exception as exc:  # noqa: BLE001
            traceback.print_exc()
            await _handle_websocket_exception(websocket, exc)
            continue


async def _handle_websocket_exception(websocket: WebSocket, exc: Exception) -> None:
    logger.exception("Error during websocket streaming: %s", exc)
    await _send_error(websocket, str(exc), 1011)


async def _send_error(websocket: WebSocket, message: str, code: int) -> None:
    await websocket.send_text(
        json.dumps({"type": "error", "message": message, "code": code})
    )
