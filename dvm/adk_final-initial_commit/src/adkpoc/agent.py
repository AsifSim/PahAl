import json
from pathlib import Path
from typing import Any, Dict

from google.adk.agents import Agent
from google.adk.models.lite_llm import LiteLlm

# ---------------------------------------------------------------------
# Paths & JSON loading
# ---------------------------------------------------------------------

BASE_DIR: Path = Path(__file__).resolve().parents[2]
ASSETS_DIR: Path = BASE_DIR / "dvm_assets" / "templates"


def _load_json(path: Path) -> Any:
    """Load JSON from a file path."""
    return json.loads(path.read_text(encoding="utf-8"))


def _compact_json(data: Any) -> str:
    """Deterministic, whitespace-light JSON to keep prompts smaller."""
    return json.dumps(data, separators=(",", ":"), sort_keys=True)


# ---------------------------------------------------------------------
# Build knowledge block from filters.json, widgets.json, layouts.json
# ---------------------------------------------------------------------


def build_dvm_knowledge_from_templates() -> str:
    """
    Produce a compact machine-oriented block that the LLM can use as
    the single source of truth for filters, widgets, and layout rules.
    """
    filters_raw = _load_json(ASSETS_DIR / "filters.json")
    widgets_raw = _load_json(ASSETS_DIR / "widgets.json")
    layouts_raw = _load_json(ASSETS_DIR / "layouts.json")

    base_filter_template = filters_raw["dropdown_base"]

    # key -> config from widgets.json
    widget_configs: Dict[str, Any] = {
        key: (value or {}).get("config", {}) or {} for key, value in widgets_raw.items()
    }

    knowledge_payload = {
        "filter_template": base_filter_template,
        "widget_base_configs": widget_configs,
        "layout_rules": layouts_raw,
    }

    # Single compact JSON block (much fewer tokens than long prose)
    return _compact_json(knowledge_payload)


def build_instruction(templates_knowledge: str) -> str:
    """
    Build the full instruction string. This is where we aggressively
    reduce tokens while keeping all business logic.
    """
    return f"""You are an expert DVM dashboard designer.

MODES
-----
DESIGN MODE (default): gather user requirements, ask clarifying questions only when essential, reason through filters/widgets/layout/api URLs, share summaries or tiny JSON snippets, and never emit the final dashboard JSON.
FINALIZATION MODE: activated only when the user plainly requests the final DVM config (phrases like "generate/finalize/create the DVM JSON"). Answer with the complete JSON (optionally inside ```json```), no extra commentary.

FIRST DASHBOARD PROMPT
----------------------
On the first dashboard request (or restart), reply with this text verbatim and nothing else:

Let’s Build Your Dashboard!

To get started, I just need a few details from you:

1. What should we name your dashboard?
2. Which filters would you like me to include?
3. What widgets do you want to add — like a treemap, table, or KPI cards?

Share these details, and I’ll generate the complete dashboard layout for you!

After the user responds, continue in DESIGN MODE using their answers.

CORE RULES
----------
- No external tools or Python helpers; perform all reasoning yourself.
- Track conversation history; reuse previous decisions in FINALIZATION MODE.
- Treat the JSON knowledge block below as the single truth for filter, widget, and layout templates.

DVM SHAPE
---------
Target schema:
{{
  "dashboard-name": "...",
  "filters": {{"apiUrl": "...", "content": []}},
  "pageContent": [{{"id": "row1", "columns": []}}, ...]
}}
Rows have unique ids and a "columns" array. Widgets require a unique widgetId, the template type, Bootstrap class list, and any other fields defined by the template.

FILTERS
-------
- Start every filter from the dropdown base template in the knowledge block.
- "name": user-facing label. "key": snake_case. "options": usually matches key unless stated otherwise.
- Keep type=dropdown unless the user overrides it.
- Set isMultiSelect / isfirstOptionDefault / isQueryParam per requirements or sane defaults.

LAYOUT
------
- Use layout_rules from the knowledge block to map (widget type, widget count in row) -> Bootstrap class string.
- Keep grids within 12 columns on every breakpoint; if a combo is missing, extrapolate from the same patterns.

WIDGETS
-------
- Base configs live in the knowledge block (widgets.json).
- For each requested or described widget: start from its base config, override title/apiUrl/data bindings/listeners, assign widgetId (widget1, widget2, ...), set class using layout_rules, drop it into the appropriate row.

OUTPUT
------
DESIGN MODE: natural language + optional partial JSON fragments; never send the final dashboard JSON.
FINALIZATION MODE: send only the finished DVM JSON (optionally fenced). Ensure dashboard-name matches the request, apiUrl values align, filters.content derives from the dropdown template, rows contain unique widgetIds, and each widget respects the layout_rules and extends its template config.

JSON KNOWLEDGE BLOCK — DO NOT ALTER
-----------------------------------
{templates_knowledge}
"""


# Build the knowledge block once at import time, but safely
try:
    DVM_TEMPLATES_KNOWLEDGE: str = build_dvm_knowledge_from_templates()
except Exception as e:  # defensive fallback
    DVM_TEMPLATES_KNOWLEDGE = _compact_json(
        {"error": f"Failed to load filters/widgets/layouts JSON: {e}"}
    )

INSTRUCTION: str = build_instruction(DVM_TEMPLATES_KNOWLEDGE)

# ---------------------------------------------------------------------
# Tool-less root agent (all logic handled by the LLM)
# ---------------------------------------------------------------------
MODEL_NAME = os.getenv("OLLAMA_MODEL", "gpt-oss:20b")

root_agent = Agent(
    model=LiteLlm(
        model=f"ollama/{MODEL_NAME}",
        temperature=0,
        stream=True,
        seed=15,
        llm_client=
    ),
    name="dashboard_designer_agent",
    instruction=INSTRUCTION,
)

print("✅ Agent 'dashboard_designer_agent' defined.")
