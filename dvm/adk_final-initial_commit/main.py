# main.py - Fixed version for adk_final-initial_commit directory

from __future__ import annotations

import sys
from pathlib import Path

# SET PATH BEFORE ANY OTHER IMPORTS
SRC_PATH = str(Path(__file__).resolve().parent / "src")
if SRC_PATH not in sys.path:
    sys.path.insert(0, SRC_PATH)

import json
import os
import time
import logging
import asyncio
import re

sys.stdout.reconfigure(encoding="utf-8")

logging.basicConfig(
    level=logging.DEBUG,
    format="%(asctime)s [%(levelname)s] %(name)s - %(message)s",
)

logger = logging.getLogger("dvm-generate-cli")
logger.debug("Logger initialized")

from adkpoc.api.agent import RunAgentRequest, run_agent_sse
from adkpoc.api.context import get_context, ServerContext, set_context
from adkpoc.api.session import create_session, CreateSessionRequest
# from adkpoc.main import app
from google.adk.cli.utils.agent_loader import AgentLoader
from google.adk.sessions.in_memory_session_service import InMemorySessionService
from google.adk.memory.in_memory_memory_service import InMemoryMemoryService
from google.adk.artifacts.in_memory_artifact_service import InMemoryArtifactService
from google.adk.auth.credential_service.in_memory_credential_service import InMemoryCredentialService

# initial_state = {
#     "Action_Type_Options": ["Data Ingestion", "Data Processing", "Data Analysis"],
#     "Workflow_Name_Options": ["Standard Workflow", "Custom Workflow"],
#     "current_selected_action_type": None,
#     "current_selected_workflow_name": None,
#     "current_file_path": None,
#     "label": ""
# }

initial_state = {
    "Action_Type_Options": ["Data Ingestion", "Data Processing", "Data Analysis"],
    "Workflow_Name_Options": ["Standard Workflow", "Custom Workflow"],
    "current_selected_action_type": None,
    "current_selected_workflow_name": None,
    "current_file_path": None,
    # Session state variables your agent expects
    "label": "",
    "document_name": "",
    "model": "",
    "value": "",  # ADD THIS
    "var_name": "",  # ADD THIS
}

APP_ROOT = Path(__file__).parent.resolve()
try:
    os.chdir(APP_ROOT)
except Exception:
    pass


def handle_query(query: str) -> dict:
    start_ns = time.perf_counter_ns()
    try:
        query_json = json.loads(query)
        logger.debug(f"query_json = {query_json}")
        fsm_payload = query_json.get("fsm")
        fsm_payload = json.loads(fsm_payload)
        model = query_json.get("model")
        logger.debug(f"fsm_payload in handle_query = {fsm_payload}")
        logger.debug(f"model in handle_query = {model}")

        # with open(fsm_path, "r", encoding="utf-8") as f:
        #     fsm_payload = json.load(f)

        logger.info("Entering document name extraction block")
        document_name = fsm_payload.get("document_name")
        logger.debug("Fetched document_name=%s", document_name)

        if not document_name:
            logger.warning("document_name is missing or empty")
            base_name = None
        else:
            base_name = document_name.split("_", 1)[0]
            logger.debug("Extracted base_name=%s", base_name)

        logger.info("Exiting document name extraction block")
        logger.debug(f"base_name = {base_name}")

        if base_name is None:
            logger.warning("base_name is None, skipping repository operations")
            return {
                "pid": "",
                "response": "",
                "response_payload": None,
                "error": "document_name is missing in FSM",
                "timings_ms": {"total": 0}
            }

        # DVM Functionality
        logger.debug("Invoking DVM Map generation functionality")
        bootstrap_context()
        logger.debug("bootstrapping context is done")
        generated_dvm = asyncio.run(run_agent_flow(fsm_payload))
        logger.info(f"generated_dvm = {generated_dvm}")
        logger.debug("DVM generation completed")

        # FIX: Check for None
        if generated_dvm is None:
            return {
                "pid": "",
                "response": "",
                "response_payload": None,
                "error": "Agent returned None - check agent logs",
                "timings_ms": {
                    "request_read": 0,
                    "python_startup": 0,
                    "agent": max(1, int((time.perf_counter_ns() - start_ns) / 1_000_000)),
                    "response_write": 0,
                    "python_shutdown": 0,
                    "total": max(1, int((time.perf_counter_ns() - start_ns) / 1_000_000)),
                },
            }

        logger.info("Returning DVM JSON for all entities")
        cleaned_dvm = generated_dvm.strip()
        logger.debug(f"After initial strip, length = {len(cleaned_dvm)}")

        if cleaned_dvm.startswith("```json"):
            logger.debug("Detected opening ```json fence, removing it")
            cleaned_dvm = cleaned_dvm[len("```json"):]
        elif cleaned_dvm.startswith("```"):
            logger.debug("Detected opening ``` fence, removing it")
            cleaned_dvm = cleaned_dvm[3:]

        if cleaned_dvm.endswith("```"):
            logger.debug("Detected closing ``` fence, removing it")
            cleaned_dvm = cleaned_dvm[:-3]

        cleaned_dvm = cleaned_dvm.strip()
        logger.debug(f"Final cleaned_dvm length = {len(cleaned_dvm)}")

        dvm_json = json.loads(cleaned_dvm)
        logger.debug(f"dvm_json = {dvm_json}")

        GENERATED_DIR = os.getenv("GENERATED_FILE_PATH")
        if not GENERATED_DIR:
            raise RuntimeError("GENERATED_DIR environment variable is not set")

        output_path = Path(GENERATED_DIR) / (base_name + "_final_dvm.json")
        logger.debug(f"Saving final DVM to {output_path}")

        with open(output_path, "w", encoding="utf-8") as f:
            json.dump(dvm_json, f, indent=2)

        logger.info(f"DVM saved successfully at {output_path}")

        dvm_response = {
            "dvm": dvm_json,
        }

        logger.debug(f"dvm_response = {dvm_response}")

        total_ms = max(1, int((time.perf_counter_ns() - start_ns) / 1_000_000))
        return {
            "pid": "",
            "response": dvm_response,
            "response_payload": {
                "dvm": dvm_json,
            },
            "error": "",
            "timings_ms": {
                "request_read": 0,
                "python_startup": 0,
                "agent": total_ms,
                "response_write": 0,
                "python_shutdown": 0,
                "total": total_ms,
            },
        }
    except Exception as exc:
        total_ms = max(1, int((time.perf_counter_ns() - start_ns) / 1_000_000))
        return {
            "pid": "",
            "response": "",
            "response_payload": None,
            "error": f"{type(exc).__name__}: {exc}",
            "timings_ms": {
                "request_read": 0,
                "python_startup": 0,
                "agent": total_ms,
                "response_write": 0,
                "python_shutdown": 0,
                "total": total_ms,
            },
        }


def _handle_file_mode(request_path: Path, response_path: Path) -> int:
    request_payload = json.loads(request_path.read_text(encoding="utf-8"))
    pid = str(request_payload.get("pid", ""))
    query = json.dumps(request_payload)
    # query = str(request_payload.get("query", "")).strip()
    result = handle_query(query)
    logger.debug(f"handle_query result = {result}")
    result["pid"] = pid
    result = _make_json_safe(result)
    response_path.parent.mkdir(parents=True, exist_ok=True)
    logger.debug(f"result = {result}")
    response_path.write_text(json.dumps(result, ensure_ascii=True, indent=2), encoding="utf-8")
    print(f"response_file={response_path}")
    return 0 if not str(result.get("error", "")).strip() else 1

def extract_relevant_fsm_section(fsm_logic,TARGET_SECTION,TARGET_SECTION2=None):
    """
    Extract ONLY raw_content from 'Entities & Attributes' section.
    """
    logger.info("Entered extract_relevant_fsm_section()")
    logger.debug("fsm_logic type = %s", type(fsm_logic))

    # TARGET_SECTION = "Entities & Attributes"
    logger.debug("TARGET_SECTION = %s", TARGET_SECTION)

    logger.info("Scanning FSM sections for target section")

    for section in fsm_logic.get("sections", []):
        logger.debug("Processing section = %s", section)

        title = section.get("section_title", "").strip()
        logger.debug("Extracted title = %s", title)

        if title.lower() == TARGET_SECTION.lower() or (TARGET_SECTION2 is not None and title.lower() == TARGET_SECTION2.lower()):
            logger.info("Matched target section: %s", TARGET_SECTION)

            raw_content = section.get("raw_content", [])
            logger.debug("Extracted raw_content = %s", raw_content)

            logger.info("Returning extracted raw_content")
            return raw_content

    logger.warning("Target section not found, returning empty list")
    return []


def main() -> int:
    if len(sys.argv) >= 3:
        req = Path(sys.argv[1])
        resp = Path(sys.argv[2])
        if req.suffix.lower() == ".json":
            return _handle_file_mode(req, resp)

    query = " ".join(sys.argv[1:]).strip() if len(sys.argv) > 1 else ""
    if not query:
        print("Usage:\n  python main.py <request.json> <response.json>\n")
        return 1
    print(json.dumps(handle_query(query), ensure_ascii=True))
    return 0


def _make_json_safe(obj):
    if isinstance(obj, Path):
        return str(obj)
    if isinstance(obj, dict):
        return {k: _make_json_safe(v) for k, v in obj.items()}
    if isinstance(obj, list):
        return [_make_json_safe(v) for v in obj]
    return obj


async def run_agent_flow(fsm_json):
    logger.debug("Starting run_agent_flow")
    logger.debug(f"fsm_json = {fsm_json}")

    session_id = await ensure_session()
    logger.debug(f"Session created = {session_id}")

    DVM_CONTENT = await call_run_agent(session_id, fsm_json)
    logger.debug(f"DVM_CONTENT = {DVM_CONTENT}")

    logger.debug("Finished run_agent_flow")
    return DVM_CONTENT


async def call_run_agent(session_id, fsm_json):
    logger.info("Entering call_run_agent")
    logger.info(f"fsm_json = {fsm_json}")

    if fsm_json == "Hi":
        extracted_fsm_string = "Hi"
    else:
        extracted_frontend_fsm = extract_relevant_fsm_section(fsm_json, "TBRD-05: Frontend & Screens")
        logger.info(f"extracted_frontend_fsm = {extracted_frontend_fsm}")

        frontend_started = False
        cleaned_frontend_fsm = []

        for item in extracted_frontend_fsm:
            value = item.strip()
            lower_value = value.lower()
            logger.debug(f"lower_value = {lower_value}")

            if "frontend" in lower_value:
                frontend_started = True
                continue

            if not frontend_started:
                continue

            if (
                    value
                    and len(value) > 2
                    and not re.fullmatch(r'[:{},\[\]]+', value)
                    and not value.startswith(":")
                    and value not in {"Operation", "Update", "None", "Data", "A", "B", "C"}
            ):
                cleaned_frontend_fsm.append(value)

        logger.info("Successfully cleaned frontend FSM section")
        logger.debug("Cleaned frontend FSM data: %s", cleaned_frontend_fsm)

        extracted_fsm_string = "\n".join(cleaned_frontend_fsm)

    logger.info(f"extracted_fsm_string = {extracted_fsm_string}")

    req = RunAgentRequest(
        app_name="user_workflow_agent",
        user_id="user1",
        session_id=session_id,
        new_message={
            "role": "user",
            "parts": [
                {
                    "text": extracted_fsm_string + "\n\nGive me the final dashboard configuration only.It should be a valid json, If multiple dashboard json then give me a Json list only, I will be validating it by doing json.loads() in python so it should be a correct json"
                }
            ]
        },
        state_delta=None,
        streaming=True
    )
    logger.debug("RunAgentRequest created: %s", req)

    logger.info("Calling get_context()")
    ctx = get_context()
    logger.info("Context received: %s", ctx)

    logger.info("Calling run_agent_sse")
    response = await run_agent_sse(req, ctx)
    logger.info("StreamingResponse received: %s", response)

    logger.info("Entering SSE response parsing block")
    final_text = None
    logger.debug("Initialized final_text to None")

    async for chunk in response.body_iterator:
        decoded = chunk.decode() if isinstance(chunk, bytes) else chunk

        if not decoded:
            continue

        if not decoded.startswith("data:"):
            continue

        try:
            payload_str = decoded.replace("data:", "").strip()

            if not payload_str:
                continue

            payload = json.loads(payload_str)

            parts = payload.get("content", {}).get("parts", [])

            for p in parts:
                if "text" in p:
                    final_text = p["text"]

            if payload.get("partial") is False:
                break

        except Exception as e:
            logger.warn(f"Exception in SSE parsing block. Exception message = {str(e)}", exc_info=True)

    logger.debug("Exited async for loop over response.body_iterator")

    if final_text:
        logger.debug("final_text is not None, proceeding to markdown cleanup")
        final_text = final_text.strip()
        logger.debug(f"Stripped final_text = {final_text}")

        if final_text.startswith("```"):
            logger.debug("Detected markdown code block, cleaning wrapper")
            split_blocks = final_text.split("```")
            logger.debug(f"Split markdown blocks = {split_blocks}")

            if len(split_blocks) > 1:
                final_text = split_blocks[1]
                logger.debug(f"Extracted inner block = {final_text}")

                if final_text.startswith("json"):
                    logger.debug("Detected 'json' language marker, removing it")
                    final_text = final_text[4:]
                    logger.debug(f"Removed json marker, updated final_text = {final_text}")

                final_text = final_text.strip()
                logger.debug(f"Final cleaned final_text after strip = {final_text}")
    else:
        logger.debug("final_text is None, skipping markdown cleanup")

    logger.info(f"Final agent response captured = {final_text}")
    logger.info("Exiting SSE response parsing block")

    return final_text


async def ensure_session():
    logger.debug("Ensuring session exists")
    ctx = get_context()

    session = await ctx.session_service.create_session(
        app_name="user_workflow_agent",
        user_id="user1",
        session_id="session1",
        state=initial_state
    )

    logger.info("Session created: %s", session.id)
    return session.id


def bootstrap_context():
    # FIXED: This file is already IN adk_final-initial_commit
    project_root = Path(__file__).resolve().parent  # adk_final-initial_commit
    logger.info(f"project root = {project_root}")

    # Agents path is directly in src/adkpoc/agents
    agents_path = project_root / "src" / "adkpoc" / "agents"

    if not agents_path.exists():
        raise RuntimeError(f"Agents directory not found: {agents_path}")

    agents_dir = str(agents_path)

    context = ServerContext(
        agent_loader=AgentLoader(agents_dir=agents_dir),
        session_service=InMemorySessionService(),
        memory_service=InMemoryMemoryService(),
        artifact_service=InMemoryArtifactService(),
        credential_service=InMemoryCredentialService(),
        agents_dir=agents_dir,
    )

    set_context(context)


if __name__ == "__main__":
    raise SystemExit(main())