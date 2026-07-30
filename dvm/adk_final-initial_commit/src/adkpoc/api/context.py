from __future__ import annotations

import os
from dataclasses import dataclass, field
from typing import Callable, Dict, Optional, Sequence

from google.adk.agents.base_agent import BaseAgent
from google.adk.apps.app import App
from google.adk.artifacts.base_artifact_service import BaseArtifactService
from google.adk.auth.credential_service.base_credential_service import (
    BaseCredentialService,
)
from google.adk.cli.utils import cleanup, envs
from google.adk.cli.utils.base_agent_loader import BaseAgentLoader
from google.adk.cli.utils.shared_value import SharedValue
from google.adk.memory.base_memory_service import BaseMemoryService
from google.adk.runners import Runner
from google.adk.sessions.base_session_service import BaseSessionService
from opentelemetry import trace
from opentelemetry.sdk.trace import ReadableSpan, TracerProvider
from opentelemetry.sdk.trace import export as export_lib
from watchdog.observers import Observer


class ApiServerSpanExporter(export_lib.SpanExporter):
    """Span exporter that captures agent traces for the debug endpoints."""

    def __init__(self, trace_dict: dict[str, dict]):
        super().__init__()
        self.trace_dict = trace_dict

    def export(self, spans: Sequence[ReadableSpan]) -> export_lib.SpanExportResult:
        for span in spans:
            if (
                span.name == "call_llm"
                or span.name == "send_data"
                or span.name.startswith("execute_tool")
            ):
                attributes = dict(span.attributes)
                attributes["trace_id"] = span.get_span_context().trace_id
                attributes["span_id"] = span.get_span_context().span_id
                event_id = attributes.get("gcp.vertex.agent.event_id")
                if event_id:
                    self.trace_dict[event_id] = attributes
        return export_lib.SpanExportResult.SUCCESS

    def force_flush(self, timeout_millis: int = 30_000) -> bool:
        return True


class InMemoryExporter(export_lib.SpanExporter):
    """Span exporter that stores spans per session for later inspection."""

    def __init__(self, trace_dict: dict[str, list[int]]):
        super().__init__()
        self._spans: list[ReadableSpan] = []
        self.trace_dict = trace_dict

    def export(self, spans: Sequence[ReadableSpan]) -> export_lib.SpanExportResult:
        for span in spans:
            trace_id = span.context.trace_id
            if span.name == "call_llm":
                attributes = dict(span.attributes)
                session_id = attributes.get("gcp.vertex.agent.session_id")
                if session_id:
                    self.trace_dict.setdefault(session_id, []).append(trace_id)
            self._spans.append(span)
        return export_lib.SpanExportResult.SUCCESS

    def force_flush(self, timeout_millis: int = 30_000) -> bool:
        return True

    def get_finished_spans(self, session_id: str) -> list[ReadableSpan]:
        trace_ids = self.trace_dict.get(session_id)
        if not trace_ids:
            return []
        return [span for span in self._spans if span.context.trace_id in trace_ids]

    def clear(self) -> None:
        self._spans.clear()


@dataclass
class ServerContext:
    agent_loader: BaseAgentLoader
    session_service: BaseSessionService
    memory_service: BaseMemoryService
    artifact_service: BaseArtifactService
    credential_service: BaseCredentialService
    agents_dir: str
    register_processors: Callable[[TracerProvider], None] = lambda _: None
    setup_observer_callback: Callable[[Observer, "ServerContext"], None] = (
        lambda _observer, _context: None
    )
    tear_down_observer_callback: Callable[[Observer, "ServerContext"], None] = (
        lambda _observer, _context: None
    )

    runners_to_clean: set[str] = field(default_factory=set)
    runner_dict: Dict[str, Runner] = field(default_factory=dict)
    current_app_name_ref: SharedValue[str] = field(
        default_factory=lambda: SharedValue(value="")
    )
    trace_dict: dict[str, dict] = field(default_factory=dict)
    session_trace_dict: dict[str, list[int]] = field(default_factory=dict)
    observer: Observer = field(default_factory=Observer)
    tracer_provider: Optional[TracerProvider] = None
    memory_exporter: Optional[InMemoryExporter] = None

    def __post_init__(self) -> None:
        self._init_tracing()
        self._init_observer()

    def _init_tracing(self) -> None:
        provider = TracerProvider()
        provider.add_span_processor(
            export_lib.SimpleSpanProcessor(ApiServerSpanExporter(self.trace_dict))
        )
        memory_exporter = InMemoryExporter(self.session_trace_dict)
        provider.add_span_processor(export_lib.SimpleSpanProcessor(memory_exporter))
        self.register_processors(provider)
        trace.set_tracer_provider(provider)
        self.tracer_provider = provider
        self.memory_exporter = memory_exporter

    def _init_observer(self) -> None:
        self.setup_observer_callback(self.observer, self)

    async def get_runner_async(self, app_name: str) -> Runner:
        if app_name in self.runners_to_clean:
            self.runners_to_clean.remove(app_name)
            runner = self.runner_dict.pop(app_name, None)
            if runner:
                await cleanup.close_runners([runner])

        envs.load_dotenv_for_agent(os.path.basename(app_name), self.agents_dir)
        if app_name in self.runner_dict:
            return self.runner_dict[app_name]

        agent_or_app = self.agent_loader.load_agent(app_name)
        if isinstance(agent_or_app, BaseAgent):
            agentic_app = App(name=app_name, root_agent=agent_or_app)
        else:
            agentic_app = agent_or_app

        runner = Runner(
            app=agentic_app,
            artifact_service=self.artifact_service,
            session_service=self.session_service,
            memory_service=self.memory_service,
            credential_service=self.credential_service,
        )
        self.runner_dict[app_name] = runner
        return runner

    async def shutdown(self) -> None:
        try:
            await cleanup.close_runners(list(self.runner_dict.values()))
        finally:
            self.runner_dict.clear()
            self.tear_down_observer_callback(self.observer, self)


_context: Optional[ServerContext] = None


def set_context(context: ServerContext) -> None:
    global _context
    _context = context


def get_context() -> ServerContext:
    if _context is None:
        raise RuntimeError("ServerContext has not been initialized")
    return _context
