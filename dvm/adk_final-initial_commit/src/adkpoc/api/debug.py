from __future__ import annotations

from typing import Any

import graphviz
from fastapi import APIRouter, Depends, HTTPException
from google.adk.cli import agent_graph
from google.adk.cli.adk_web_server import GetEventGraphResult

from adkpoc.api.context import ServerContext, get_context

TAG_DEBUG = "Debug"
router = APIRouter()


@router.get("/debug/trace/{event_id}", tags=[TAG_DEBUG])
async def get_trace_dict(
    event_id: str, ctx: ServerContext = Depends(get_context)
) -> Any:
    event_dict = ctx.trace_dict.get(event_id)
    if event_dict is None:
        raise HTTPException(status_code=404, detail="Trace not found")
    return event_dict


@router.get("/debug/trace/session/{session_id}", tags=[TAG_DEBUG])
async def get_session_trace(
    session_id: str, ctx: ServerContext = Depends(get_context)
) -> Any:
    if not ctx.memory_exporter:
        return []
    spans = ctx.memory_exporter.get_finished_spans(session_id)
    if not spans:
        return []
    return [
        {
            "name": span.name,
            "span_id": span.context.span_id,
            "trace_id": span.context.trace_id,
            "start_time": span.start_time,
            "end_time": span.end_time,
            "attributes": dict(span.attributes),
            "parent_span_id": span.parent.span_id if span.parent else None,
        }
        for span in spans
    ]


@router.get(
    "/apps/{app_name}/users/{user_id}/sessions/{session_id}/events/{event_id}/graph",
    response_model_exclude_none=True,
    tags=[TAG_DEBUG],
)
async def get_event_graph(
    app_name: str,
    user_id: str,
    session_id: str,
    event_id: str,
    ctx: ServerContext = Depends(get_context),
):
    session = await ctx.session_service.get_session(
        app_name=app_name, user_id=user_id, session_id=session_id
    )
    session_events = session.events if session else []
    event = next((event for event in session_events if event.id == event_id), None)
    if not event:
        return {}

    function_calls = event.get_function_calls()
    function_responses = event.get_function_responses()
    root_agent = ctx.agent_loader.load_agent(app_name)
    dot_graph = None
    if function_calls:
        function_call_highlights = []
        for function_call in function_calls:
            from_name = event.author
            to_name = function_call.name
            function_call_highlights.append((from_name, to_name))
            dot_graph = await agent_graph.get_agent_graph(
                root_agent, function_call_highlights
            )
    elif function_responses:
        function_responses_highlights = []
        for response in function_responses:
            from_name = response.name
            to_name = event.author
            function_responses_highlights.append((from_name, to_name))
            dot_graph = await agent_graph.get_agent_graph(
                root_agent, function_responses_highlights
            )
    else:
        from_name = event.author
        to_name = ""
        dot_graph = await agent_graph.get_agent_graph(
            root_agent, [(from_name, to_name)]
        )
    if dot_graph and isinstance(dot_graph, graphviz.Digraph):
        return GetEventGraphResult(dot_src=dot_graph.source)
    return {}
