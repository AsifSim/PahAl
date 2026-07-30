from __future__ import annotations

from contextlib import asynccontextmanager
from pathlib import Path

from dotenv import load_dotenv

load_dotenv()

import google.adk.cli as adk_cli
import uvicorn
from fastapi import FastAPI
from google.adk.auth.credential_service.in_memory_credential_service import (
    InMemoryCredentialService,
)
from google.adk.cli.utils.agent_loader import AgentLoader
from google.adk.memory.in_memory_memory_service import InMemoryMemoryService
from google.adk.artifacts.in_memory_artifact_service import InMemoryArtifactService
from adkpoc.api import agent, artifact, debug, session
from adkpoc.api.adk_ui import configure_dev_ui
from adkpoc.api.context import ServerContext, set_context
# from adkpoc.database.artifact import artifact_service
from adkpoc.database.session import session_service
from adkpoc.utils.env import get_env
from adkpoc.utils.my_logger import get_log_config
from fastapi.middleware.cors import CORSMiddleware

ANGULAR_DIST_PATH = Path(adk_cli.__file__).parent / "browser"
AGENTS_DIR = Path(__file__).resolve().parent / "agents"

agent_loader = AgentLoader(str(AGENTS_DIR))
memory_service = InMemoryMemoryService()
artifact_service = InMemoryArtifactService()
credential_service = InMemoryCredentialService()


def _create_app() -> FastAPI:
    context = ServerContext(
        agent_loader=agent_loader,
        session_service=session_service,
        memory_service=memory_service,
        artifact_service=artifact_service,
        credential_service=credential_service,
        agents_dir=str(AGENTS_DIR),
    )
    set_context(context)

    @asynccontextmanager
    async def lifespan(app: FastAPI):  # noqa: F811
        try:
            yield
        finally:
            await context.shutdown()

    app = FastAPI(lifespan=lifespan)

    # Configure CORS
    app.add_middleware(
        CORSMiddleware,
        allow_origins=[
            "http://localhost:4200",
            "http://127.0.0.1:4200",
        ],
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )

    app.include_router(session.router)
    app.include_router(agent.router)
    app.include_router(artifact.router)
    app.include_router(debug.router)

    web_assets_dir = str(ANGULAR_DIST_PATH)
    configure_dev_ui(app, web_assets_dir=web_assets_dir)
    return app


app = _create_app()


def main(host: str, port: int, log_level: str) -> None:
    log_config = get_log_config(log_level)
    config = uvicorn.Config(app, host=host, port=port, log_level=log_level, log_config=log_config)
    server = uvicorn.Server(config)
    server.run()


if __name__ == "__main__":
    host = get_env("HOST", "127.0.0.1")
    port = int(get_env("PORT", "8081"))
    debug_flag = get_env("DEBUG", "False").lower() in ("true", "1", "t")
    log_level = "debug" if debug_flag else "info"
    main(host=host, port=port, log_level=log_level)