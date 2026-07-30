# from __future__ import annotations
#
# import json
# import re
# from datetime import datetime
# from pathlib import Path
# from typing import Any, Dict, List, Optional, Sequence, Tuple
#
# from google.adk.agents import Agent
# from google.adk.models.lite_llm import LiteLlm
# from pydantic import BaseModel, ConfigDict, Field, field_validator
#
# # ---------------------------------------------------------------------
# # Paths & JSON loading
# # ---------------------------------------------------------------------
#
# BASE_DIR = Path(__file__).resolve().parents[2]
# ASSETS_DIR = BASE_DIR / "dvm_assets" / "templates"
# DVM_OUTPUT_DIR = BASE_DIR / "dvm_output"
#
#
# class FlexibleModel(BaseModel):
#     """Base Pydantic model that tolerates unknown DVM fields."""
#
#     model_config = ConfigDict(extra="allow", populate_by_name=True)
#
#
# class WidgetPlacement(FlexibleModel):
#     id: Optional[str] = None
#     type: Optional[str] = None
#     position: Optional[str] = None
#     class_: Optional[str] = Field(default=None, alias="class")
#
#     @field_validator("position")
#     @classmethod
#     def _normalize_position(cls, value: Optional[str]) -> Optional[str]:
#         return value.lower() if isinstance(value, str) else value
#
#
# class WidgetRow(FlexibleModel):
#     widgets: List[WidgetPlacement] = Field(default_factory=list)
#
#
# class AbstractLayout(FlexibleModel):
#     rows: List[WidgetRow] = Field(default_factory=list)
#
#
# class ResponsiveLayoutEntry(FlexibleModel):
#     row: int
#     id: Optional[str] = None
#     position: Optional[str] = None
#     class_: str = Field(alias="class")
#
#
# class LayoutFromInstructionsInput(BaseModel):
#     instructions: str = ""
#
#     @field_validator("instructions", mode="before")
#     @classmethod
#     def _ensure_text(cls, value: Any) -> str:
#         if value is None:
#             return ""
#         if not isinstance(value, str):
#             raise TypeError("instructions must be a string.")
#         return value.strip()
#
#
# class LayoutFromInstructionsOutput(BaseModel):
#     rows: List[WidgetRow]
#     layout: List[ResponsiveLayoutEntry]
#
#
# class DvmRow(FlexibleModel):
#     id: Optional[str] = None
#     columns: List["DvmColumn"] = Field(default_factory=list)
#
#
# class DvmColumn(FlexibleModel):
#     widgetId: Optional[str] = Field(default=None, alias="widgetId")
#     type: Optional[str] = None
#     position: Optional[str] = None
#     class_: Optional[str] = Field(default=None, alias="class")
#     rows: Optional[List[DvmRow]] = None
#
#
# class DvmConfig(FlexibleModel):
#     dashboard_name: Optional[str] = Field(default=None, alias="dashboard-name")
#     filters: Optional[Dict[str, Any]] = None
#     pageContent: List[DvmRow] = Field(default_factory=list)
#
#
# class SaveDvmConfigInput(BaseModel):
#     dvm_json: Any
#     file_prefix: str = Field(default="dvm", min_length=1)
#     change_summary: str = Field(default="")
#
#     @field_validator("file_prefix")
#     @classmethod
#     def _sanitize_prefix(cls, value: str) -> str:
#         cleaned = re.sub(r"[^a-zA-Z0-9_-]", "-", value.strip())
#         if not cleaned:
#             raise ValueError("file_prefix must include alphanumeric characters.")
#         return cleaned.lower()
#
#     @field_validator("change_summary")
#     @classmethod
#     def _trim_summary(cls, value: str) -> str:
#         return value.strip() if isinstance(value, str) else ""
#
#
# class SaveDvmConfigOutput(BaseModel):
#     saved_path: str
#     version: int
#     metadata_path: str
#     lastModified: str
#     changeSummary: str
#
#
# class LoadDvmConfigInput(BaseModel):
#     config_name: str
#     version: int = 0
#
#     @field_validator("config_name")
#     @classmethod
#     def _non_empty(cls, value: str) -> str:
#         if not value or not value.strip():
#             raise ValueError("config_name is required.")
#         return value.strip()
#
#
# class LoadDvmConfigOutput(BaseModel):
#     config_name: str
#     version: int
#     path: str
#     content: DvmConfig
#     lastModified: str
#     changeSummary: str
#     metadata_path: str
#
#
# class CompareDvmVersionsInput(BaseModel):
#     config_name: str
#     version_a: int
#     version_b: int
#
#     @field_validator("config_name")
#     @classmethod
#     def _check_name(cls, value: str) -> str:
#         if not value or not value.strip():
#             raise ValueError("config_name is required.")
#         return value.strip()
#
#     @field_validator("version_a", "version_b")
#     @classmethod
#     def _positive(cls, value: int) -> int:
#         if value <= 0:
#             raise ValueError("Versions must be positive integers.")
#         return value
#
#
# class VersionedConfig(BaseModel):
#     version: int
#     path: str
#     content: DvmConfig
#     lastModified: str
#     changeSummary: str
#     metadata_path: str
#
#
# class CompareDvmVersionsOutput(BaseModel):
#     a: VersionedConfig
#     b: VersionedConfig
#
#
# class DvmJsonInput(BaseModel):
#     dvm_json: Any
#
#
# class DvmConfigWrapper(BaseModel):
#     fixed_config: DvmConfig
#
#
# DvmRow.model_rebuild()
# DvmColumn.model_rebuild()
# DvmConfig.model_rebuild()
#
#
# def _load_json(path: Path):
#     with path.open("r", encoding="utf-8") as f:
#         return json.load(f)
#
#
# def _normalize_dvm_payload(raw: Any) -> Dict[str, Any]:
#     """Accept raw string or dict and return a parsed DVM JSON object."""
#     if isinstance(raw, dict):
#         return raw
#     if not isinstance(raw, str):
#         raise ValueError("DVM payload must be a JSON string or dict.")
#
#     candidate = raw.strip()
#     if candidate.startswith("```"):
#         candidate = candidate.strip("`")
#     # Extract the first balanced-looking JSON object if there is surrounding text
#     first_brace = candidate.find("{")
#     last_brace = candidate.rfind("}")
#     if first_brace != -1 and last_brace != -1:
#         candidate = candidate[first_brace : last_brace + 1]
#
#     try:
#         return json.loads(candidate)
#     except json.JSONDecodeError as exc:
#         raise ValueError(
#             "Invalid DVM JSON provided; please supply a valid JSON object."
#         ) from exc
#
#
# def _coerce_dvm_config(raw: Any) -> DvmConfig:
#     """Convert arbitrary payload to validated DvmConfig."""
#     return DvmConfig.model_validate(_normalize_dvm_payload(raw))
#
#
# def _dump_dvm_config(config: DvmConfig) -> Dict[str, Any]:
#     """Return alias-friendly dict representation of a config."""
#     return config.model_dump(by_alias=True, exclude_none=True)
#
#
# def _get_widget_field(widget: Any, field_name: str) -> Any:
#     """Read a possibly-aliased field from BaseModel/dict objects."""
#     alias = "class_" if field_name == "class" else field_name
#     if isinstance(widget, BaseModel):
#         return getattr(widget, alias, None)
#     if isinstance(widget, dict):
#         return widget.get(field_name)
#     return getattr(widget, alias, None)
#
#
# def _next_dvm_path(prefix: str = "dvm") -> Tuple[Path, int]:
#     """Compute the next incremental DVM output path."""
#     DVM_OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
#     pattern = re.compile(rf"^{re.escape(prefix)}-(\d+)\.json$")
#     max_id = 0
#     for path in DVM_OUTPUT_DIR.glob(f"{prefix}-*.json"):
#         match = pattern.match(path.name)
#         if match:
#             max_id = max(max_id, int(match.group(1)))
#     next_id = max_id + 1
#     return DVM_OUTPUT_DIR / f"{prefix}-{next_id}.json", next_id
#
#
# def _list_saved_versions(prefix: str) -> List[Tuple[int, Path]]:
#     """List saved versions for a given prefix as (version, path)."""
#     pattern = re.compile(rf"^{re.escape(prefix)}-(\d+)\.json$", re.IGNORECASE)
#     results: List[Tuple[int, Path]] = []
#     if not DVM_OUTPUT_DIR.exists():
#         return results
#     for path in DVM_OUTPUT_DIR.glob("*.json"):
#         match = pattern.match(path.name)
#         if match:
#             results.append((int(match.group(1)), path))
#     return sorted(results, key=lambda x: x[0])
#
#
# def classify_widget_type(widget_type: str) -> str:
#     """
#     Classify widget type into LARGE | MEDIUM | SMALL based on rules:
#     - LARGE: treemap, bubble, waterfall, heatmap, map, combined
#     - SMALL: KPI, number-stats, counters
#     - MEDIUM: default (unknown types included)
#     """
#     t = (widget_type or "").lower()
#     if any(
#         k in t for k in ["treemap", "bubble", "waterfall", "heatmap", "map", "combined"]
#     ):
#         return "LARGE"
#     if any(k in t for k in ["kpi", "number-stats", "counter", "counters"]):
#         return "SMALL"
#     return "MEDIUM"
#
#
# def compute_width_for_row(widgets: Sequence[Any]) -> List[int]:
#     """
#     Compute widths for a row per the specified rules.
#     Accepts widget dictionaries or Pydantic models.
#     """
#     widgets = list(widgets)
#     if not widgets:
#         return []
#
#     def _pos(widget: Any) -> str:
#         return (_get_widget_field(widget, "position") or "").lower()
#
#     def _type(widget: Any) -> str:
#         return (_get_widget_field(widget, "type") or "") or ""
#
#     # Rule 6/7: full or stacked -> width 12 each
#     if any(_pos(w) in {"full", "stacked"} for w in widgets):
#         return [12 for _ in widgets]
#
#     n = len(widgets)
#     if n == 1:
#         return [12]
#
#     sizes = [classify_widget_type(_type(w)) for w in widgets]
#     has_large = "LARGE" in sizes
#     all_small = all(s == "SMALL" for s in sizes)
#     all_medium = all(s == "MEDIUM" for s in sizes)
#
#     # Rule 2: LARGE + other widgets
#     if has_large and n >= 2:
#         widths: List[int] = []
#         large_assigned = False
#         for s in sizes:
#             if s == "LARGE" and not large_assigned:
#                 widths.append(7)
#                 large_assigned = True
#             else:
#                 widths.append(0)  # placeholder
#         non_large_count = n - 1
#         share = 5 // non_large_count if non_large_count else 0
#         remainder = 5 - share * non_large_count
#         for idx in range(n):
#             if widths[idx] == 0:
#                 widths[idx] = share
#                 if remainder > 0:
#                     widths[idx] += 1
#                     remainder -= 1
#         return widths
#
#     # Rule 3 & 4: all small or all medium
#     if all_small or all_medium:
#         base = 12 // n
#         widths = [base for _ in widgets]
#         remainder = 12 - base * n
#         if remainder > 0:
#             widths[0] += remainder
#         return widths
#
#     # Rule 5: mix of medium & small without large -> treat all as medium
#     base = 12 // n
#     widths = [base for _ in widgets]
#     remainder = 12 - base * n
#     if remainder > 0:
#         widths[0] += remainder
#     return widths
#
#
# def compute_bootstrap_class(width: int) -> str:
#     """
#     Convert a width to the required Bootstrap class string.
#     Always uses format: "col-12 col-sm-12 col-md-W col-lg-W"
#     """
#     w = max(1, min(12, int(round(width or 12))))
#     return f"col-12 col-sm-12 col-md-{w} col-lg-{w}"
#
#
# def _parse_layout_instructions(text: str) -> AbstractLayout:
#     """
#     Very lightweight parser for positional words.
#     Returns an AbstractLayout made of WidgetRow entries.
#     Assumes widgets are described in order; unknowns default to full-width rows.
#     """
#     tokens = text.lower().replace(",", " ").split()
#     rows: List[WidgetRow] = []
#     current_row: List[WidgetPlacement] = []
#     widget_counter = 1
#
#     def flush_row():
#         nonlocal current_row
#         if current_row:
#             rows.append(WidgetRow(widgets=list(current_row)))
#             current_row = []
#
#     i = 0
#     while i < len(tokens):
#         t = tokens[i]
#         if t in {"below", "under", "stack", "stacked"}:
#             flush_row()
#         elif t in {"top", "full"}:
#             flush_row()
#             current_row.append(
#                 WidgetPlacement(id=f"widget{widget_counter}", position="full")
#             )
#             widget_counter += 1
#         elif t == "left":
#             current_row.append(
#                 WidgetPlacement(id=f"widget{widget_counter}", position="left")
#             )
#             widget_counter += 1
#         elif t == "right":
#             current_row.append(
#                 WidgetPlacement(id=f"widget{widget_counter}", position="right")
#             )
#             widget_counter += 1
#         i += 1
#
#     flush_row()
#
#     if not rows:
#         rows = [
#             WidgetRow(
#                 widgets=[WidgetPlacement(id="widget1", position="full")],
#             )
#         ]
#
#     return AbstractLayout(rows=rows)
#
#
# def layout_from_instructions(instructions: str) -> Dict[str, Any]:
#     """
#     Tool: Convert natural-language layout instructions into row-wise layout with classes,
#     using the defined classification and width rules.
#     """
#     payload = LayoutFromInstructionsInput(instructions=instructions)
#     parsed = _parse_layout_instructions(payload.instructions)
#     layout_entries = generate_final_layout(parsed)
#     output = LayoutFromInstructionsOutput(rows=parsed.rows, layout=layout_entries)
#     return output.model_dump(by_alias=True)
#
#
# def generate_final_layout(
#     abstract_layout: AbstractLayout | Dict[str, Any],
# ) -> List[ResponsiveLayoutEntry]:
#     """
#     Generate the final layout JSON from an abstract layout.
#     Preserves widget order and row structure.
#     """
#     if not isinstance(abstract_layout, AbstractLayout):
#         abstract_layout = AbstractLayout.model_validate(abstract_layout or {})
#     if not abstract_layout.rows:
#         return []
#
#     final: List[ResponsiveLayoutEntry] = []
#     for row_idx, row in enumerate(abstract_layout.rows, start=1):
#         widgets = row.widgets or []
#         widths = compute_width_for_row(widgets)
#         for widget, width in zip(widgets, widths):
#             final.append(
#                 ResponsiveLayoutEntry(
#                     row=row_idx,
#                     id=widget.id,
#                     position=widget.position,
#                     class_=compute_bootstrap_class(width),
#                 )
#             )
#     return final
#
#
# def save_dvm_config(
#     dvm_json: Any, file_prefix: str = "dvm", change_summary: str = ""
# ) -> Dict[str, Any]:
#     """
#     Tool: Persist the finalized DVM JSON to disk with an incremental id.
#
#     - Accepts a JSON string (with or without ``` fences) or a dict.
#     - Saves to dvm_output/<file_prefix>-<version>.json where version increments from existing files.
#     - Does NOT inject version metadata into the main config payload; metadata is stored separately.
#     """
#     payload = SaveDvmConfigInput(
#         dvm_json=dvm_json, file_prefix=file_prefix, change_summary=change_summary
#     )
#     config = _coerce_dvm_config(payload.dvm_json)
#     normalized = _dump_dvm_config(config)
#     versions = _list_saved_versions(payload.file_prefix)
#     next_version = versions[-1][0] + 1 if versions else 1
#     path = DVM_OUTPUT_DIR / f"{payload.file_prefix}-{next_version}.json"
#     timestamp = datetime.utcnow().isoformat() + "Z"
#     path.write_text(json.dumps(normalized, indent=2), encoding="utf-8")
#     metadata = {
#         "saved_path": str(path),
#         "version": next_version,
#         "timestamp": timestamp,
#         "change_summary": payload.change_summary or "saved configuration",
#     }
#     meta_path = path.with_suffix(".meta.json")
#     meta_path.write_text(json.dumps(metadata, indent=2), encoding="utf-8")
#     return SaveDvmConfigOutput(
#         saved_path=str(path),
#         version=next_version,
#         metadata_path=str(meta_path),
#         lastModified=timestamp,
#         changeSummary=payload.change_summary or "saved configuration",
#     ).model_dump(by_alias=True)
#
#
# def load_dvm_config(config_name: str, version: int = 0) -> Dict[str, Any]:
#     """
#     Tool: Load a saved DVM JSON by name (prefix) from dvm_output.
#
#     - Matches files like <config_name>-<version>.json (case-insensitive).
#     - If version=0 (default), returns the highest version.
#     """
#     payload = LoadDvmConfigInput(config_name=config_name, version=version)
#     if not DVM_OUTPUT_DIR.exists():
#         raise FileNotFoundError(
#             "No saved configurations found (dvm_output is missing)."
#         )
#
#     versions = _list_saved_versions(payload.config_name)
#     if not versions:
#         raise FileNotFoundError(
#             f"No saved configuration found matching name '{payload.config_name}'."
#         )
#
#     if payload.version and all(v[0] != payload.version for v in versions):
#         raise FileNotFoundError(
#             f"No saved configuration found for '{payload.config_name}' at version {payload.version}."
#         )
#
#     target_version, target_path = next(
#         (v for v in versions if v[0] == payload.version), versions[-1]
#     )
#     content = DvmConfig.model_validate_json(target_path.read_text(encoding="utf-8"))
#     meta_path = target_path.with_suffix(".meta.json")
#     meta: Dict[str, Any] = {}
#     if meta_path.exists():
#         try:
#             meta = json.loads(meta_path.read_text(encoding="utf-8"))
#         except json.JSONDecodeError:
#             meta = {}
#     last_modified = content.lastModified if hasattr(content, "lastModified") else ""
#     if not last_modified:
#         last_modified = meta.get("timestamp", "")
#     change_summary = meta.get("change_summary", "")
#     return LoadDvmConfigOutput(
#         config_name=payload.config_name,
#         version=target_version,
#         path=str(target_path),
#         content=content,
#         lastModified=last_modified,
#         changeSummary=change_summary,
#         metadata_path=str(meta_path),
#     ).model_dump(by_alias=True)
#
#
# def compare_dvm_versions(
#     config_name: str, version_a: int, version_b: int
# ) -> Dict[str, Any]:
#     """
#     Tool: Load two versions for comparison; returns both payloads for agent-side diffing.
#     """
#     payload = CompareDvmVersionsInput(
#         config_name=config_name, version_a=version_a, version_b=version_b
#     )
#     versions = _list_saved_versions(payload.config_name)
#     if not versions:
#         raise FileNotFoundError(
#             f"No saved configuration found matching name '{payload.config_name}'."
#         )
#
#     def _load_version(v: int) -> VersionedConfig:
#         matches = [item for item in versions if item[0] == v]
#         if not matches:
#             raise FileNotFoundError(
#                 f"No saved configuration found for '{payload.config_name}' at version {v}."
#             )
#         _, path = matches[0]
#         content = DvmConfig.model_validate_json(path.read_text(encoding="utf-8"))
#         meta_path = path.with_suffix(".meta.json")
#         meta: Dict[str, Any] = {}
#         if meta_path.exists():
#             try:
#                 meta = json.loads(meta_path.read_text(encoding="utf-8"))
#             except json.JSONDecodeError:
#                 meta = {}
#         last_modified = meta.get("timestamp", "")
#         change_summary = meta.get("change_summary", "")
#         return VersionedConfig(
#             version=v,
#             path=str(path),
#             content=content,
#             lastModified=last_modified,
#             changeSummary=change_summary,
#             metadata_path=str(meta_path),
#         )
#
#     result = CompareDvmVersionsOutput(
#         a=_load_version(payload.version_a),
#         b=_load_version(payload.version_b),
#     )
#     return result.model_dump(by_alias=True)
#
#
# def fix_layout_classes(dvm_json: Any) -> Dict[str, Any]:
#     """
#     Tool: Apply Bootstrap classes to widgets based on row counts and type rules.
#     - Accepts a JSON string (with or without ``` fences) or a dict.
#     - For each row in pageContent, assigns class if missing, using compute_width_for_row.
#     """
#     payload = DvmJsonInput(dvm_json=dvm_json)
#     config = _coerce_dvm_config(payload.dvm_json)
#     for row in config.pageContent:
#         columns = row.columns or []
#         widths = compute_width_for_row(columns)
#         for col, width in zip(columns, widths):
#             if not col.class_:
#                 col.class_ = compute_bootstrap_class(width)
#     return DvmConfigWrapper(fixed_config=config).model_dump(by_alias=True)
#
#
# def nest_container_children(dvm_json: Any) -> Dict[str, Any]:
#     """
#     Tool: Ensure container widget children remain nested under the container, not at pageContent level.
#     - Finds the first layout-container in pageContent.
#     - Moves any subsequent top-level rows into that container's `rows` array.
#     """
#     payload = DvmJsonInput(dvm_json=dvm_json)
#     config = _coerce_dvm_config(payload.dvm_json)
#     page_content = config.pageContent
#     if not page_content:
#         return DvmConfigWrapper(fixed_config=config).model_dump(by_alias=True)
#
#     new_page: List[DvmRow] = []
#     container_rows: List[DvmRow] = []
#     container_col: Optional[DvmColumn] = None
#     container_found = False
#
#     for row in page_content:
#         if container_found:
#             container_rows.append(row)
#             continue
#
#         cols = row.columns or []
#         new_page.append(row)
#         for col in cols:
#             col_type = (col.type or "").lower()
#             if col_type == "layout-container":
#                 container_found = True
#                 container_col = col
#                 existing = col.rows or []
#                 container_rows = existing
#                 col.rows = container_rows
#                 break
#
#     if container_found and container_col:
#         container_col.rows = container_rows
#         config.pageContent = new_page
#
#     return DvmConfigWrapper(fixed_config=config).model_dump(by_alias=True)
#
#
# # ---------------------------------------------------------------------
# # Build knowledge from filters.json, widgets.json, actions.json, layouts.json
# # ---------------------------------------------------------------------
#
#
# def build_dvm_knowledge_from_templates() -> str:
#     """
#     Read filters.json, widgets.json, and actions.json and convert them into a textual
#     description for the LLM, so component definitions live in JSON and the agent reads
#     the summary in its prompt.
#     """
#     filters_raw = _load_json(ASSETS_DIR / "filters.json")
#     widgets_raw = _load_json(ASSETS_DIR / "widgets.json")
#     actions_raw = _load_json(ASSETS_DIR / "actions.json")
#
#     # filters.json has a single base object: "dropdown_base"
#     base_filter_template = filters_raw["dropdown_base"]
#
#     parts: List[str] = []
#
#     # ---------------- FILTER TEMPLATE ----------------
#     parts.append("FILTER TEMPLATE (from filters.json)\n")
#     parts.append(
#         "Use this base filter object as the starting point for each filter in "
#         "the `filters.content` array. Adapt the fields based on the user's description:\n"
#     )
#     parts.append(json.dumps(base_filter_template, indent=2))
#     parts.append(
#         "\nNotes:\n"
#         "- `name`: display name shown to the user.\n"
#         "- `key`: internal key in snake_case.\n"
#         "- `options`: key used to fetch / map dropdown options.\n"
#         "- `type`: should remain 'dropdown' unless explicitly changed by the user.\n"
#         "- `isMultiSelect`, `isfirstOptionDefault`, `isQueryParam`: set according to requirements.\n\n"
#     )
#
#     # ---------------- WIDGET TEMPLATES (FULLY GENERIC) ----------------
#     parts.append("WIDGET TEMPLATES (from widgets.json)\n")
#     parts.append(
#         "Below are ALL available widget base configs. When the user asks for a component by key "
#         "or describes a component that matches one of these, start from that widget's base config "
#         "and adapt fields like `title`, `apiUrl`, `key`, data bindings, and listeners according to "
#         "the user's instructions. Always override `widgetId` with a unique value like 'widget1', 'widget2', ...\n\n"
#     )
#
#     for key, value in widgets_raw.items():
#         cfg = value.get("config", {}) or {}
#         w_type = cfg.get("type", "unknown")
#
#         parts.append(f"Widget key: {key}\n")
#         parts.append(f"Widget type (from config.type): {w_type}\n")
#         parts.append("Base config JSON:\n")
#         parts.append(json.dumps(cfg, indent=2))
#         parts.append("\n")
#         parts.append(
#             "Usage notes: This widget is defined in widgets.json and has the type shown above. "
#             "Whenever the user requests this widget key explicitly, or describes a component that "
#             "matches this type and purpose, you should start from this base config, modify fields "
#             "(such as title, apiUrl, data bindings, filter listeners, etc.) to match the user's description, "
#             "and ensure `widgetId` is set to a unique value.\n\n"
#         )
#
#     # ---------------- ACTION TEMPLATES ----------------
#     parts.append("ACTION TEMPLATES (from actions.json)\n")
#     parts.append(
#         "Use these base action configs (button/icon). When the user requests download, submit, "
#         "or other actions, start from the matching template and adjust `label`, `key`, `apiUrl`, "
#         "or visibility rules to fit the request. Attach the resulting objects to the relevant "
#         "widget's `actions` array or action slot in the final JSON.\n\n"
#     )
#
#     for key, value in actions_raw.items():
#         parts.append(f"Action key: {key}\n")
#         parts.append("Base action JSON:\n")
#         parts.append(json.dumps(value, indent=2))
#         parts.append(
#             "\nUsage notes: Begin with this template when adding an action of this type. "
#             "Override fields to match the user's intent (e.g., change apiUrl, label, key). "
#             "Keep type aligned with the template unless the user explicitly changes it.\n\n"
#         )
#
#     return "\n".join(parts)
#
#
# # Build the knowledge block once at import time, but safely
# try:
#     DVM_TEMPLATES_KNOWLEDGE = build_dvm_knowledge_from_templates()
# except Exception as e:
#     # Fail-safe: agent still loads, but you’ll see the error in the prompt text
#     DVM_TEMPLATES_KNOWLEDGE = (
#         f"WARNING: Failed to load filters.json/widgets.json/actions.json. Error: {e}"
#     )
#
#
# # ---------------------------------------------------------------------
# # Root agent (all logic handled by the LLM; optional save/load tools)
# # ---------------------------------------------------------------------
# import os
# model_name = os.getenv("MODEL_NAME_KAVERI", "gpt-oss:20b")
# root_agent = Agent(
#     model=LiteLlm(
#         model=f"ollama_chat/{model_name}",  # your Ollama routed model
#         temperature=0,
#         stream=True,
#         seed=15,
#         timeout=1800
#     ),
#     name="dashboard_designer_agent",
#     instruction=f"""You are an expert DVM dashboard designer.
#
# MODES
# - DESIGN (default): discuss filters/widgets/layout/APIs; no full JSON yet.
# - FINALIZATION: triggered when the user clearly asks for the final DVM JSON; return ONLY the full JSON (```json``` ok). If saving/loading, include the tool result path/id.
# - EXPLAIN: when the user asks for explanation/chain-of-thought, provide a concise user-facing change log and rationale (no raw internal reasoning).
#  - VERSIONED SAVE/LOAD: when saving or loading versions, follow the versioning rules below.
#
# CORE RULES
# - Tools: you ARE allowed and expected to call `save_dvm_config` when the user says “save”, `load_dvm_config` when they ask to show/load a saved config (optionally with version), and `compare_dvm_versions` when they ask to compare versions. Do not refuse these tool calls.
# - Layout fixing: when the user asks to fix/auto-assign layout classes, call `fix_layout_classes` with the current config.
# - Source of truth: filters.json, widgets.json, actions.json; use tools when requested (do not avoid them) and keep other logic in the model.
# - Remember conversation history.
# - On explicit "save"/"store"/"persist" instructions, you must invoke `save_dvm_config` with the current config (do not just reply with JSON).
# - TOOL INVOCATION RULE: Whenever a user explicitly asks to save/load/compare/fix layout, nest container children, or generate a layout from instructions, you MUST invoke the corresponding tool. Do not simulate tool output or skip tool calls.
#
# DVM EDIT WORKFLOW
# - Step-by-step edits: on each user step, fetch the current config (last version), apply only requested changes, append to a human-readable change history, and return the updated JSON plus a one-line description of what changed.
# - Field edits: if the user specifies fields (filterListner, filterKey, xAxisLabel, yAxisLabel, class, title, series, colors, or any valid field), change ONLY those fields after selecting the base widget config from widgets.json; leave everything else unchanged.
# - Full replacement: if the user provides a full config JSON, validate structure; if valid, adopt it as the current config and confirm (or report structural issues).
# - Versioning: persist version/timestamp/changeSummary in metadata (sidecar) when saving. Config payload itself should stay as the user provided unless they explicitly include metadata. Do not overwrite older versions; always increment version (+1) on save.
# - Retrieval/comparison: be able to show the latest version, load a specific version on request, and compare two versions when asked.
#
# DVM SHAPE (FINAL MODE)
# {{
#   "dashboard-name": "<string>",
#   "filters": {{ "apiUrl": "<string>", "content": [...] }},
#   "pageContent": [ {{ "id": "row1", "columns": [ ... ] }}, ... ]
# }}
# Each widget needs: widgetId, type (from template config.type), class (Bootstrap), plus template fields.
#
# FILTERS
# - Start from the dropdown base template; set name/key/options/type/multi/default/query flags per intent.
# - Place filter-level/global actions (if any) only at the END of filters.content (after all filters), using actions.json templates.
# - Prompt mapping: when the user supplies a "List of Filters" and "Pred-defined filter" list, generate filters in that exact order, with the exact names/keys/options given. If the user says "Filters supporting multi-selection: All", set `isMultiSelect: true` for every filter unless the user explicitly overrides a specific filter.
# - Pred-defined filter options: if the prompt provides inline options (e.g., Pricing Strategy: [Total Opportunity, Short Term]), set the filter options/defaultOptions accordingly and do NOT replace with an API config unless explicitly requested.
#
# ACTIONS
# - Use actions.json as templates; adjust label/key/apiUrl/visibility per request.
# - Attach to widget.actions when widget-specific; otherwise append at the end of filters.content (never before filters).
#
# LAYOUT
# - Assign Bootstrap classes sensibly so rows do not exceed 12 columns per breakpoint; default to widget template classes when present. No external layout mappings are used.
# - Size classification (by intent, not name):
#   - LARGE if: many data points (50+), dense 2D visuals (treemap/heatmap/map/scatter with many points), hierarchical/multi-level, interactive drilldowns inside the chart, multiple dimensions (x/y/size/color/hierarchy), wide-aspect benefit, or user calls it primary/main/big/overview/left-side big chart.
#   - MEDIUM if: single main axis, small/moderate data (5–40), readable at half-width, analytical but not dense. Unknown types default to MEDIUM unless user says otherwise.
#   - SMALL if: single metric/KPI/stat/compact tile, lightweight, intended in groups; if user says metric/summary/stat/card → SMALL.
#   - If user says “big chart” → LARGE; “small metric” → SMALL; otherwise default to MEDIUM when unknown.
#   - When a layout-container is requested, place it as a column in the specified parent row (e.g., row1) and keep all child rows/columns inside `widget.rows`; do not wrap it in an extra pageContent row or duplicate row ids.
#
# WIDGETS
# - All widgets come from widgets.json.
# - If user names a widget key, copy that config from widgets.json as the base; otherwise choose the best match.
# - If user provides rowId/widgetId, set them; else generate unique ids.
# - If user requests edits to specific fields, change ONLY those; leave everything else unchanged; keep structure/formatting.
# - Only the top-level layout uses `pageContent`; any layout container must nest its children under a `rows` array (with row objects holding `columns`), and you must never place `pageContent` inside a widget or emit a second pageContent block.
# - For layout container widgets (grouping KPIs/charts without their own data), include only nested `rows`/`columns` and omit data fields like apiUrl/series/value; they orchestrate children only.
# - Do not add data fields to containers; include `rows`/`columns` and optional title/sectionName/class only when the user provides them, and do not auto-insert a `type` the user didn’t supply.
# - Keep a container’s child rows/columns nested under that container (`pageContent[].columns[] -> widget2 -> rows[...]`); never lift them to top-level `pageContent`.
# - When a parent widgetId defines the container context, do NOT repeat that parent widgetId on child rows/columns; children keep their own widgetIds and inherit the parent implicitly.
# - Treat the layout-container as a hierarchy boundary keyed by its widgetId: rows/columns live inside that widget only—never alongside it at the same level.
# - Respect the user’s placement for containers: if the prompt says "Position: Row X Widget Y", place the container as a column in that row and nest its child rows/columns inside it. Do not move the container into a new row or elevate its child rows to pageContent.
# - Any widget that defines `rows` is a layout container; all of its rows and columns must stay strictly nested inside that widget and must never be moved, duplicated, or promoted to `pageContent` or any parallel layout level.
# - If the user asks for a layout container (or nested container), you MUST create that widget as a column in the specified parent row and keep all child rows/columns inside its `rows`; do not emit extra pageContent rows for those children.
#
# TEMPLATES
# Use these as the single source of truth:
# {DVM_TEMPLATES_KNOWLEDGE}
#
# TOOLS
# - save_dvm_config: when the user says “save” (or similar), call this tool with the current config, passing a file_prefix derived from dashboard name (snake/kebab ok) and a brief change_summary. Writes dvm_output/<prefix>-<version>.json; metadata (version, timestamp, change summary) is stored separately, not inside the main config. Always call this tool on explicit save requests.
# - load_dvm_config: only on explicit load/show + name (and optional version); return JSON (```json``` ok) and file path/version metadata.
# - compare_dvm_versions: only when asked to compare versions; loads two versions for agent-side diffing.
# - fix_layout_classes: when asked to fix/auto-assign layout classes, pass the current config; it will fill missing `class` fields per row using width heuristics (no external layout mapping).
# - layout_from_instructions: when the user gives natural-language layout positions (top/left/right/below/stack, etc.), call this to generate rows + responsive classes.
# - nest_container_children: when a layout-container exists and child rows were emitted at pageContent level, call this to re-nest them under the container before replying.
# - If a tool is requested and the payload is missing, ask the user for the minimum required fields, then call the tool. Do not respond with a non-tool answer when a tool invocation is requested.
# - If a user asks to save/load/compare/fix layout, you MUST use the corresponding tool; do not inline or simulate the result.
#
# OUTPUT
# - DESIGN mode: natural language / partial snippets only.
# - Step responses: return updated JSON and a one-line summary of what changed; update change history.
# - FINAL mode: only the full JSON (```json``` ok), with: dashboard-name; filters.apiUrl + filters.content; filter-level actions (if any) appended last; pageContent rows with unique widgetIds and sensible layout classes; widgets/actions from templates. Include save/load info if a tool was invoked (metadata stays external); only include version/lastModified/changeSummary if already present in the payload.
# - EXPLAIN mode: concise change log and rationales for each explicit edit; no internal chain-of-thought.
# """,
#     tools=[
#         save_dvm_config,
#         load_dvm_config,
#         compare_dvm_versions,
#         fix_layout_classes,
#         layout_from_instructions,
#         nest_container_children,
#     ],
# )
#
# print("✅ Agent 'dashboard_designer_agent' defined.")


#
# from __future__ import annotations
#
#
# import json
# import re
# from datetime import datetime
# from pathlib import Path
# from typing import Any, Dict, List, Optional, Sequence, Tuple
#
#
# from google.adk.agents import Agent
# from google.adk.models.lite_llm import LiteLlm
# from pydantic import BaseModel, ConfigDict, Field, field_validator
#
#
# # ---------------------------------------------------------------------
# # Paths & JSON loading
# # ---------------------------------------------------------------------
#
#
# BASE_DIR = Path(__file__).resolve().parents[2]
# ASSETS_DIR = BASE_DIR / "dvm_assets" / "templates"
# DVM_OUTPUT_DIR = BASE_DIR / "dvm_output"
#
#
#
#
# class FlexibleModel(BaseModel):
#     """Base Pydantic model that tolerates unknown DVM fields."""
#
#
#     model_config = ConfigDict(extra="allow", populate_by_name=True)
#
#
#
#
# class WidgetPlacement(FlexibleModel):
#     id: Optional[str] = None
#     type: Optional[str] = None
#     position: Optional[str] = None
#     class_: Optional[str] = Field(default=None, alias="class")
#
#
#     @field_validator("position")
#     @classmethod
#     def _normalize_position(cls, value: Optional[str]) -> Optional[str]:
#         return value.lower() if isinstance(value, str) else value
#
#
#
#
# class WidgetRow(FlexibleModel):
#     widgets: List[WidgetPlacement] = Field(default_factory=list)
#
#
#
#
# class AbstractLayout(FlexibleModel):
#     rows: List[WidgetRow] = Field(default_factory=list)
#
#
#
#
# class ResponsiveLayoutEntry(FlexibleModel):
#     row: int
#     id: Optional[str] = None
#     position: Optional[str] = None
#     class_: str = Field(alias="class")
#
#
#
#
# class LayoutFromInstructionsInput(BaseModel):
#     instructions: str = ""
#
#
#     @field_validator("instructions", mode="before")
#     @classmethod
#     def _ensure_text(cls, value: Any) -> str:
#         if value is None:
#             return ""
#         if not isinstance(value, str):
#             raise TypeError("instructions must be a string.")
#         return value.strip()
#
#
#
#
# class LayoutFromInstructionsOutput(BaseModel):
#     rows: List[WidgetRow]
#     layout: List[ResponsiveLayoutEntry]
#
#
#
#
# class DvmRow(FlexibleModel):
#     id: Optional[str] = None
#     columns: List["DvmColumn"] = Field(default_factory=list)
#
#
#
#
# class DvmColumn(FlexibleModel):
#     widgetId: Optional[str] = Field(default=None, alias="widgetId")
#     type: Optional[str] = None
#     position: Optional[str] = None
#     class_: Optional[str] = Field(default=None, alias="class")
#     rows: Optional[List[DvmRow]] = None
#
#
#
#
# class DvmConfig(FlexibleModel):
#     dashboard_name: Optional[str] = Field(default=None, alias="dashboard-name")
#     filters: Optional[Dict[str, Any]] = None
#     pageContent: List[DvmRow] = Field(default_factory=list)
#
#
#
#
# class SaveDvmConfigInput(BaseModel):
#     dvm_json: Any
#     file_prefix: str = Field(default="dvm", min_length=1)
#     change_summary: str = Field(default="")
#
#
#     @field_validator("file_prefix")
#     @classmethod
#     def _sanitize_prefix(cls, value: str) -> str:
#         cleaned = re.sub(r"[^a-zA-Z0-9_-]", "-", value.strip())
#         if not cleaned:
#             raise ValueError("file_prefix must include alphanumeric characters.")
#         return cleaned.lower()
#
#
#     @field_validator("change_summary")
#     @classmethod
#     def _trim_summary(cls, value: str) -> str:
#         return value.strip() if isinstance(value, str) else ""
#
#
#
#
# class SaveDvmConfigOutput(BaseModel):
#     saved_path: str
#     version: int
#     metadata_path: str
#     lastModified: str
#     changeSummary: str
#
#
#
#
# class LoadDvmConfigInput(BaseModel):
#     config_name: str
#     version: int = 0
#
#
#     @field_validator("config_name")
#     @classmethod
#     def _non_empty(cls, value: str) -> str:
#         if not value or not value.strip():
#             raise ValueError("config_name is required.")
#         return value.strip()
#
#
#
#
# class LoadDvmConfigOutput(BaseModel):
#     config_name: str
#     version: int
#     path: str
#     content: DvmConfig
#     lastModified: str
#     changeSummary: str
#     metadata_path: str
#
#
#
#
# class CompareDvmVersionsInput(BaseModel):
#     config_name: str
#     version_a: int
#     version_b: int
#
#
#     @field_validator("config_name")
#     @classmethod
#     def _check_name(cls, value: str) -> str:
#         if not value or not value.strip():
#             raise ValueError("config_name is required.")
#         return value.strip()
#
#
#     @field_validator("version_a", "version_b")
#     @classmethod
#     def _positive(cls, value: int) -> int:
#         if value <= 0:
#             raise ValueError("Versions must be positive integers.")
#         return value
#
#
#
#
# class VersionedConfig(BaseModel):
#     version: int
#     path: str
#     content: DvmConfig
#     lastModified: str
#     changeSummary: str
#     metadata_path: str
#
#
#
#
# class CompareDvmVersionsOutput(BaseModel):
#     a: VersionedConfig
#     b: VersionedConfig
#
#
#
#
# class DvmJsonInput(BaseModel):
#     dvm_json: Any
#
#
#
#
# class DvmConfigWrapper(BaseModel):
#     fixed_config: DvmConfig
#
#
#
#
# DvmRow.model_rebuild()
# DvmColumn.model_rebuild()
# DvmConfig.model_rebuild()
#
#
#
#
# def _load_json(path: Path):
#     with path.open("r", encoding="utf-8") as f:
#         return json.load(f)
#
#
#
#
# def _normalize_dvm_payload(raw: Any) -> Dict[str, Any]:
#     """Accept raw string or dict and return a parsed DVM JSON object."""
#     if isinstance(raw, dict):
#         return raw
#     if not isinstance(raw, str):
#         raise ValueError("DVM payload must be a JSON string or dict.")
#
#
#     candidate = raw.strip()
#     if candidate.startswith("```"):
#         candidate = candidate.strip("`")
#     # Extract the first balanced-looking JSON object if there is surrounding text
#     first_brace = candidate.find("{")
#     last_brace = candidate.rfind("}")
#     if first_brace != -1 and last_brace != -1:
#         candidate = candidate[first_brace : last_brace + 1]
#
#
#     try:
#         return json.loads(candidate)
#     except json.JSONDecodeError as exc:
#         raise ValueError(
#             "Invalid DVM JSON provided; please supply a valid JSON object."
#         ) from exc
#
#
#
#
# def _coerce_dvm_config(raw: Any) -> DvmConfig:
#     """Convert arbitrary payload to validated DvmConfig."""
#     return DvmConfig.model_validate(_normalize_dvm_payload(raw))
#
#
#
#
# def _dump_dvm_config(config: DvmConfig) -> Dict[str, Any]:
#     """Return alias-friendly dict representation of a config."""
#     return config.model_dump(by_alias=True, exclude_none=True)
#
#
#
#
# def _get_widget_field(widget: Any, field_name: str) -> Any:
#     """Read a possibly-aliased field from BaseModel/dict objects."""
#     alias = "class_" if field_name == "class" else field_name
#     if isinstance(widget, BaseModel):
#         return getattr(widget, alias, None)
#     if isinstance(widget, dict):
#         return widget.get(field_name)
#     return getattr(widget, alias, None)
#
#
#
#
# def _next_dvm_path(prefix: str = "dvm") -> Tuple[Path, int]:
#     """Compute the next incremental DVM output path."""
#     DVM_OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
#     pattern = re.compile(rf"^{re.escape(prefix)}-(\d+)\.json$")
#     max_id = 0
#     for path in DVM_OUTPUT_DIR.glob(f"{prefix}-*.json"):
#         match = pattern.match(path.name)
#         if match:
#             max_id = max(max_id, int(match.group(1)))
#     next_id = max_id + 1
#     return DVM_OUTPUT_DIR / f"{prefix}-{next_id}.json", next_id
#
#
#
#
# def _list_saved_versions(prefix: str) -> List[Tuple[int, Path]]:
#     """List saved versions for a given prefix as (version, path)."""
#     pattern = re.compile(rf"^{re.escape(prefix)}-(\d+)\.json$", re.IGNORECASE)
#     results: List[Tuple[int, Path]] = []
#     if not DVM_OUTPUT_DIR.exists():
#         return results
#     for path in DVM_OUTPUT_DIR.glob("*.json"):
#         match = pattern.match(path.name)
#         if match:
#             results.append((int(match.group(1)), path))
#     return sorted(results, key=lambda x: x[0])
#
#
#
#
# def classify_widget_type(widget_type: str) -> str:
#     """
#     Classify widget type into LARGE | MEDIUM | SMALL based on rules:
#     - LARGE: treemap, bubble, waterfall, heatmap, map, combined
#     - SMALL: KPI, number-stats, counters
#     - MEDIUM: default (unknown types included)
#     """
#     t = (widget_type or "").lower()
#     if any(
#             k in t for k in ["treemap", "bubble", "waterfall", "heatmap", "map", "combined"]
#     ):
#         return "LARGE"
#     if any(k in t for k in ["kpi", "number-stats", "counter", "counters"]):
#         return "SMALL"
#     return "MEDIUM"
#
#
#
#
# def compute_width_for_row(widgets: Sequence[Any]) -> List[int]:
#     """
#     Compute widths for a row per the specified rules.
#     Accepts widget dictionaries or Pydantic models.
#     """
#     widgets = list(widgets)
#     if not widgets:
#         return []
#
#
#     def _pos(widget: Any) -> str:
#         return (_get_widget_field(widget, "position") or "").lower()
#
#
#     def _type(widget: Any) -> str:
#         return (_get_widget_field(widget, "type") or "") or ""
#
#
#     # Rule 6/7: full or stacked -> width 12 each
#     if any(_pos(w) in {"full", "stacked"} for w in widgets):
#         return [12 for _ in widgets]
#
#
#     n = len(widgets)
#     if n == 1:
#         return [12]
#
#
#     sizes = [classify_widget_type(_type(w)) for w in widgets]
#     has_large = "LARGE" in sizes
#     all_small = all(s == "SMALL" for s in sizes)
#     all_medium = all(s == "MEDIUM" for s in sizes)
#
#
#     # Rule 2: LARGE + other widgets
#     if has_large and n >= 2:
#         widths: List[int] = []
#         large_assigned = False
#         for s in sizes:
#             if s == "LARGE" and not large_assigned:
#                 widths.append(7)
#                 large_assigned = True
#             else:
#                 widths.append(0)  # placeholder
#         non_large_count = n - 1
#         share = 5 // non_large_count if non_large_count else 0
#         remainder = 5 - share * non_large_count
#         for idx in range(n):
#             if widths[idx] == 0:
#                 widths[idx] = share
#                 if remainder > 0:
#                     widths[idx] += 1
#                     remainder -= 1
#         return widths
#
#
#     # Rule 3 & 4: all small or all medium
#     if all_small or all_medium:
#         base = 12 // n
#         widths = [base for _ in widgets]
#         remainder = 12 - base * n
#         if remainder > 0:
#             widths[0] += remainder
#         return widths
#
#
#     # Rule 5: mix of medium & small without large -> treat all as medium
#     base = 12 // n
#     widths = [base for _ in widgets]
#     remainder = 12 - base * n
#     if remainder > 0:
#         widths[0] += remainder
#     return widths
#
#
#
#
# def compute_bootstrap_class(width: int) -> str:
#     """
#     Convert a width to the required Bootstrap class string.
#     Always uses format: "col-12 col-sm-12 col-md-W col-lg-W"
#     """
#     w = max(1, min(12, int(round(width or 12))))
#     return f"col-12 col-sm-12 col-md-{w} col-lg-{w}"
#
#
#
#
# def _parse_layout_instructions(text: str) -> AbstractLayout:
#     """
#     Very lightweight parser for positional words.
#     Returns an AbstractLayout made of WidgetRow entries.
#     Assumes widgets are described in order; unknowns default to full-width rows.
#     """
#     tokens = text.lower().replace(",", " ").split()
#     rows: List[WidgetRow] = []
#     current_row: List[WidgetPlacement] = []
#     widget_counter = 1
#
#
#     def flush_row():
#         nonlocal current_row
#         if current_row:
#             rows.append(WidgetRow(widgets=list(current_row)))
#             current_row = []
#
#
#     i = 0
#     while i < len(tokens):
#         t = tokens[i]
#         if t in {"below", "under", "stack", "stacked"}:
#             flush_row()
#         elif t in {"top", "full"}:
#             flush_row()
#             current_row.append(
#                 WidgetPlacement(id=f"widget{widget_counter}", position="full")
#             )
#             widget_counter += 1
#         elif t == "left":
#             current_row.append(
#                 WidgetPlacement(id=f"widget{widget_counter}", position="left")
#             )
#             widget_counter += 1
#         elif t == "right":
#             current_row.append(
#                 WidgetPlacement(id=f"widget{widget_counter}", position="right")
#             )
#             widget_counter += 1
#         i += 1
#
#
#     flush_row()
#
#
#     if not rows:
#         rows = [
#             WidgetRow(
#                 widgets=[WidgetPlacement(id="widget1", position="full")],
#             )
#         ]
#
#
#     return AbstractLayout(rows=rows)
#
#
#
#
# def layout_from_instructions(instructions: str) -> Dict[str, Any]:
#     """
#     Tool: Convert natural-language layout instructions into row-wise layout with classes,
#     using the defined classification and width rules.
#     """
#     payload = LayoutFromInstructionsInput(instructions=instructions)
#     parsed = _parse_layout_instructions(payload.instructions)
#     layout_entries = generate_final_layout(parsed)
#     output = LayoutFromInstructionsOutput(rows=parsed.rows, layout=layout_entries)
#     return output.model_dump(by_alias=True)
#
#
#
#
# def generate_final_layout(
#         abstract_layout: AbstractLayout | Dict[str, Any],
# ) -> List[ResponsiveLayoutEntry]:
#     """
#     Generate the final layout JSON from an abstract layout.
#     Preserves widget order and row structure.
#     """
#     if not isinstance(abstract_layout, AbstractLayout):
#         abstract_layout = AbstractLayout.model_validate(abstract_layout or {})
#     if not abstract_layout.rows:
#         return []
#
#
#     final: List[ResponsiveLayoutEntry] = []
#     for row_idx, row in enumerate(abstract_layout.rows, start=1):
#         widgets = row.widgets or []
#         widths = compute_width_for_row(widgets)
#         for widget, width in zip(widgets, widths):
#             final.append(
#                 ResponsiveLayoutEntry(
#                     row=row_idx,
#                     id=widget.id,
#                     position=widget.position,
#                     class_=compute_bootstrap_class(width),
#                 )
#             )
#     return final
#
#
#
#
# def save_dvm_config(
#         dvm_json: Any, file_prefix: str = "dvm", change_summary: str = ""
# ) -> Dict[str, Any]:
#     """
#     Tool: Persist the finalized DVM JSON to disk with an incremental id.
#
#
#     - Accepts a JSON string (with or without ``` fences) or a dict.
#     - Saves to dvm_output/<file_prefix>-<version>.json where version increments from existing files.
#     - Does NOT inject version metadata into the main config payload; metadata is stored separately.
#     """
#     payload = SaveDvmConfigInput(
#         dvm_json=dvm_json, file_prefix=file_prefix, change_summary=change_summary
#     )
#     config = _coerce_dvm_config(payload.dvm_json)
#     normalized = _dump_dvm_config(config)
#     versions = _list_saved_versions(payload.file_prefix)
#     next_version = versions[-1][0] + 1 if versions else 1
#     path = DVM_OUTPUT_DIR / f"{payload.file_prefix}-{next_version}.json"
#     timestamp = datetime.utcnow().isoformat() + "Z"
#     path.write_text(json.dumps(normalized, indent=2), encoding="utf-8")
#     metadata = {
#         "saved_path": str(path),
#         "version": next_version,
#         "timestamp": timestamp,
#         "change_summary": payload.change_summary or "saved configuration",
#     }
#     meta_path = path.with_suffix(".meta.json")
#     meta_path.write_text(json.dumps(metadata, indent=2), encoding="utf-8")
#     return SaveDvmConfigOutput(
#         saved_path=str(path),
#         version=next_version,
#         metadata_path=str(meta_path),
#         lastModified=timestamp,
#         changeSummary=payload.change_summary or "saved configuration",
#     ).model_dump(by_alias=True)
#
#
#
#
# def load_dvm_config(config_name: str, version: int = 0) -> Dict[str, Any]:
#     """
#     Tool: Load a saved DVM JSON by name (prefix) from dvm_output.
#
#
#     - Matches files like <config_name>-<version>.json (case-insensitive).
#     - If version=0 (default), returns the highest version.
#     """
#     payload = LoadDvmConfigInput(config_name=config_name, version=version)
#     if not DVM_OUTPUT_DIR.exists():
#         raise FileNotFoundError(
#             "No saved configurations found (dvm_output is missing)."
#         )
#
#
#     versions = _list_saved_versions(payload.config_name)
#     if not versions:
#         raise FileNotFoundError(
#             f"No saved configuration found matching name '{payload.config_name}'."
#         )
#
#
#     if payload.version and all(v[0] != payload.version for v in versions):
#         raise FileNotFoundError(
#             f"No saved configuration found for '{payload.config_name}' at version {payload.version}."
#         )
#
#
#     target_version, target_path = next(
#         (v for v in versions if v[0] == payload.version), versions[-1]
#     )
#     content = DvmConfig.model_validate_json(target_path.read_text(encoding="utf-8"))
#     meta_path = target_path.with_suffix(".meta.json")
#     meta: Dict[str, Any] = {}
#     if meta_path.exists():
#         try:
#             meta = json.loads(meta_path.read_text(encoding="utf-8"))
#         except json.JSONDecodeError:
#             meta = {}
#     last_modified = content.lastModified if hasattr(content, "lastModified") else ""
#     if not last_modified:
#         last_modified = meta.get("timestamp", "")
#     change_summary = meta.get("change_summary", "")
#     return LoadDvmConfigOutput(
#         config_name=payload.config_name,
#         version=target_version,
#         path=str(target_path),
#         content=content,
#         lastModified=last_modified,
#         changeSummary=change_summary,
#         metadata_path=str(meta_path),
#     ).model_dump(by_alias=True)
#
#
#
#
# def compare_dvm_versions(
#         config_name: str, version_a: int, version_b: int
# ) -> Dict[str, Any]:
#     """
#     Tool: Load two versions for comparison; returns both payloads for agent-side diffing.
#     """
#     payload = CompareDvmVersionsInput(
#         config_name=config_name, version_a=version_a, version_b=version_b
#     )
#     versions = _list_saved_versions(payload.config_name)
#     if not versions:
#         raise FileNotFoundError(
#             f"No saved configuration found matching name '{payload.config_name}'."
#         )
#
#
#     def _load_version(v: int) -> VersionedConfig:
#         matches = [item for item in versions if item[0] == v]
#         if not matches:
#             raise FileNotFoundError(
#                 f"No saved configuration found for '{payload.config_name}' at version {v}."
#             )
#         _, path = matches[0]
#         content = DvmConfig.model_validate_json(path.read_text(encoding="utf-8"))
#         meta_path = path.with_suffix(".meta.json")
#         meta: Dict[str, Any] = {}
#         if meta_path.exists():
#             try:
#                 meta = json.loads(meta_path.read_text(encoding="utf-8"))
#             except json.JSONDecodeError:
#                 meta = {}
#         last_modified = meta.get("timestamp", "")
#         change_summary = meta.get("change_summary", "")
#         return VersionedConfig(
#             version=v,
#             path=str(path),
#             content=content,
#             lastModified=last_modified,
#             changeSummary=change_summary,
#             metadata_path=str(meta_path),
#         )
#
#
#     result = CompareDvmVersionsOutput(
#         a=_load_version(payload.version_a),
#         b=_load_version(payload.version_b),
#     )
#     return result.model_dump(by_alias=True)
#
#
#
#
# def fix_layout_classes(dvm_json: Any) -> Dict[str, Any]:
#     """
#     Tool: Apply Bootstrap classes to widgets based on row counts and type rules.
#     - Accepts a JSON string (with or without ``` fences) or a dict.
#     - For each row in pageContent, assigns class if missing, using compute_width_for_row.
#     """
#     payload = DvmJsonInput(dvm_json=dvm_json)
#     config = _coerce_dvm_config(payload.dvm_json)
#     for row in config.pageContent:
#         columns = row.columns or []
#         widths = compute_width_for_row(columns)
#         for col, width in zip(columns, widths):
#             if not col.class_:
#                 col.class_ = compute_bootstrap_class(width)
#     return DvmConfigWrapper(fixed_config=config).model_dump(by_alias=True)
#
#
#
#
# def nest_container_children(dvm_json: Any) -> Dict[str, Any]:
#     """
#     Tool: Ensure container widget children remain nested under the container, not at pageContent level.
#     - Finds the first layout-container in pageContent.
#     - Moves any subsequent top-level rows into that container's `rows` array.
#     """
#     payload = DvmJsonInput(dvm_json=dvm_json)
#     config = _coerce_dvm_config(payload.dvm_json)
#     page_content = config.pageContent
#     if not page_content:
#         return DvmConfigWrapper(fixed_config=config).model_dump(by_alias=True)
#
#
#     new_page: List[DvmRow] = []
#     container_rows: List[DvmRow] = []
#     container_col: Optional[DvmColumn] = None
#     container_found = False
#
#
#     for row in page_content:
#         if container_found:
#             container_rows.append(row)
#             continue
#
#
#         cols = row.columns or []
#         new_page.append(row)
#         for col in cols:
#             col_type = (col.type or "").lower()
#             if col_type == "layout-container":
#                 container_found = True
#                 container_col = col
#                 existing = col.rows or []
#                 container_rows = existing
#                 col.rows = container_rows
#                 break
#
#
#     if container_found and container_col:
#         container_col.rows = container_rows
#         config.pageContent = new_page
#
#
#     return DvmConfigWrapper(fixed_config=config).model_dump(by_alias=True)
#
#
#
#
# # ---------------------------------------------------------------------
# # Build knowledge from filters.json, widgets.json, actions.json, layouts.json
# # ---------------------------------------------------------------------
#
#
#
#
# def build_dvm_knowledge_from_templates() -> str:
#     """
#     Read filters.json, widgets.json, and actions.json and convert them into a textual
#     description for the LLM, so component definitions live in JSON and the agent reads
#     the summary in its prompt.
#     """
#     filters_raw = _load_json(ASSETS_DIR / "filters.json")
#     widgets_raw = _load_json(ASSETS_DIR / "widgets.json")
#     actions_raw = _load_json(ASSETS_DIR / "actions.json")
#
#
#     # filters.json has a single base object: "dropdown_base"
#     base_filter_template = filters_raw["dropdown_base"]
#
#
#     parts: List[str] = []
#
#
#     # ---------------- FILTER TEMPLATE ----------------
#     parts.append("FILTER TEMPLATE (from filters.json)\n")
#     parts.append(
#         "Use this base filter object as the starting point for each filter in "
#         "the `filters.content` array. Adapt the fields based on the user's description:\n"
#     )
#     parts.append(json.dumps(base_filter_template, indent=2))
#     parts.append(
#         "\nNotes:\n"
#         "- `name`: display name shown to the user.\n"
#         "- `key`: internal key in snake_case.\n"
#         "- `options`: key used to fetch / map dropdown options.\n"
#         "- `type`: should remain 'dropdown' unless explicitly changed by the user.\n"
#         "- `isMultiSelect`, `isfirstOptionDefault`, `isQueryParam`: set according to requirements.\n\n"
#     )
#
#
#     # ---------------- WIDGET TEMPLATES (FULLY GENERIC) ----------------
#     parts.append("WIDGET TEMPLATES (from widgets.json)\n")
#     parts.append(
#         "Below are ALL available widget base configs. When the user asks for a component by key "
#         "or describes a component that matches one of these, start from that widget's base config "
#         "and adapt fields like `title`, `apiUrl`, `key`, data bindings, and listeners according to "
#         "the user's instructions. Always override `widgetId` with a unique value like 'widget1', 'widget2', ...\n\n"
#     )
#
#
#     for key, value in widgets_raw.items():
#         cfg = value.get("config", {}) or {}
#         w_type = cfg.get("type", "unknown")
#
#
#         parts.append(f"Widget key: {key}\n")
#         parts.append(f"Widget type (from config.type): {w_type}\n")
#         parts.append("Base config JSON:\n")
#         parts.append(json.dumps(cfg, indent=2))
#         parts.append("\n")
#         parts.append(
#             "Usage notes: This widget is defined in widgets.json and has the type shown above. "
#             "Whenever the user requests this widget key explicitly, or describes a component that "
#             "matches this type and purpose, you should start from this base config, modify fields "
#             "(such as title, apiUrl, data bindings, filter listeners, etc.) to match the user's description, "
#             "and ensure `widgetId` is set to a unique value.\n\n"
#         )
#
#
#     # ---------------- ACTION TEMPLATES ----------------
#     parts.append("ACTION TEMPLATES (from actions.json)\n")
#     parts.append(
#         "Use these base action configs (button/icon). When the user requests download, submit, "
#         "or other actions, start from the matching template and adjust `label`, `key`, `apiUrl`, "
#         "or visibility rules to fit the request. Attach the resulting objects to the relevant "
#         "widget's `actions` array or action slot in the final JSON.\n\n"
#     )
#
#
#     for key, value in actions_raw.items():
#         parts.append(f"Action key: {key}\n")
#         parts.append("Base action JSON:\n")
#         parts.append(json.dumps(value, indent=2))
#         parts.append(
#             "\nUsage notes: Begin with this template when adding an action of this type. "
#             "Override fields to match the user's intent (e.g., change apiUrl, label, key). "
#             "Keep type aligned with the template unless the user explicitly changes it.\n\n"
#         )
#
#
#     return "\n".join(parts)
#
#
#
#
# # Build the knowledge block once at import time, but safely
# try:
#     DVM_TEMPLATES_KNOWLEDGE = build_dvm_knowledge_from_templates()
# except Exception as e:
#     # Fail-safe: agent still loads, but you’ll see the error in the prompt text
#     DVM_TEMPLATES_KNOWLEDGE = (
#         f"WARNING: Failed to load filters.json/widgets.json/actions.json. Error: {e}"
#     )
#
#
#
#
# # ---------------------------------------------------------------------
# # Root agent (all logic handled by the LLM; optional save/load tools)
# # ---------------------------------------------------------------------
# import os
#
#
# model_name = os.getenv("MODEL_NAME_KAVERI", "gpt-oss:20b")
# root_agent = Agent(
#     model=LiteLlm(
#         model=f"ollama_chat/{model_name}",  # your Ollama routed model
#         temperature=0,
#         stream=True,
#         seed=15,
#         timeout=1800,
#     ),
#     name="dashboard_designer_agent",
#     instruction=f"""You are an expert DVM dashboard designer.
#
#
# MODES
# - DESIGN (default): discuss filters/widgets/layout/APIs; no full JSON yet.
# - FINALIZATION: triggered when the user clearly asks for the final DVM JSON; return ONLY the full JSON (```json``` ok). If saving/loading, include the tool result path/id.
# - EXPLAIN: when the user asks for explanation/chain-of-thought, provide a concise user-facing change log and rationale (no raw internal reasoning).
#  - VERSIONED SAVE/LOAD: when saving or loading versions, follow the versioning rules below.
#
#
# CORE RULES
# - Tools: you ARE allowed and expected to call `save_dvm_config` when the user says “save”, `load_dvm_config` when they ask to show/load a saved config (optionally with version), and `compare_dvm_versions` when they ask to compare versions. Do not refuse these tool calls.
# - Layout fixing: when the user asks to fix/auto-assign layout classes, call `fix_layout_classes` with the current config.
# - Source of truth: filters.json, widgets.json, actions.json; use tools when requested (do not avoid them) and keep other logic in the model.
# - Remember conversation history.
# - On explicit "save"/"store"/"persist" instructions, you must invoke `save_dvm_config` with the current config (do not just reply with JSON).
# - TOOL INVOCATION RULE: Whenever a user explicitly asks to save/load/compare/fix layout, nest container children, or generate a layout from instructions, you MUST invoke the corresponding tool. Do not simulate tool output or skip tool calls.
#
#
# DVM EDIT WORKFLOW
# - Step-by-step edits: on each user step, fetch the current config (last version), apply only requested changes, append to a human-readable change history, and return the updated JSON plus a one-line description of what changed.
# - Field edits: if the user specifies fields (filterListner, filterKey, xAxisLabel, yAxisLabel, class, title, series, colors, or any valid field), change ONLY those fields after selecting the base widget config from widgets.json; leave everything else unchanged.
# - Full replacement: if the user provides a full config JSON, validate structure; if valid, adopt it as the current config and confirm (or report structural issues).
# - Versioning: persist version/timestamp/changeSummary in metadata (sidecar) when saving. Config payload itself should stay as the user provided unless they explicitly include metadata. Do not overwrite older versions; always increment version (+1) on save.
# - Retrieval/comparison: be able to show the latest version, load a specific version on request, and compare two versions when asked.
#
#
# DVM SHAPE (FINAL MODE)
# {{
#   "dashboard-name": "<string>",
#   "filters": {{ "apiUrl": "<string>", "content": [...] }},
#   "pageContent": [ {{ "id": "row1", "columns": [ ... ] }}, ... ]
# }}
# Each widget needs: widgetId, type (from template config.type), class (Bootstrap), plus template fields.
#
#
# FILTERS
# - Start from the dropdown base template; set name/key/options/type/multi/default/query flags per intent.
# - Place filter-level/global actions (if any) only at the END of filters.content (after all filters), using actions.json templates.
# - Prompt mapping: when the user supplies a "List of Filters" and "Pred-defined filter" list, generate filters in that exact order, with the exact names/keys/options given. If the user says "Filters supporting multi-selection: All", set `isMultiSelect: true` for every filter unless the user explicitly overrides a specific filter.
# - Pred-defined filter options: if the prompt provides inline options (e.g., Pricing Strategy: [Total Opportunity, Short Term]), set the filter options/defaultOptions accordingly and do NOT replace with an API config unless explicitly requested.
#
#
# ACTIONS
# - Use actions.json as templates; adjust label/key/apiUrl/visibility per request.
# - Attach to widget.actions when widget-specific; otherwise append at the end of filters.content (never before filters).
#
#
# LAYOUT
# - Assign Bootstrap classes sensibly so rows do not exceed 12 columns per breakpoint; default to widget template classes when present. No external layout mappings are used.
# - Size classification (by intent, not name):
#   - LARGE if: many data points (50+), dense 2D visuals (treemap/heatmap/map/scatter with many points), hierarchical/multi-level, interactive drilldowns inside the chart, multiple dimensions (x/y/size/color/hierarchy), wide-aspect benefit, or user calls it primary/main/big/overview/left-side big chart.
#   - MEDIUM if: single main axis, small/moderate data (5–40), readable at half-width, analytical but not dense. Unknown types default to MEDIUM unless user says otherwise.
#   - SMALL if: single metric/KPI/stat/compact tile, lightweight, intended in groups; if user says metric/summary/stat/card → SMALL.
#   - If user says “big chart” → LARGE; “small metric” → SMALL; otherwise default to MEDIUM when unknown.
#   - When a layout-container is requested, place it as a column in the specified parent row (e.g., row1) and keep all child rows/columns inside `widget.rows`; do not wrap it in an extra pageContent row or duplicate row ids.
#
#
# WIDGETS
# - All widgets come from widgets.json.
# - If user names a widget key, copy that config from widgets.json as the base; otherwise choose the best match.
# - If user provides rowId/widgetId, set them; else generate unique ids.
# - If user requests edits to specific fields, change ONLY those; leave everything else unchanged; keep structure/formatting.
# - Only the top-level layout uses `pageContent`; any layout container must nest its children under a `rows` array (with row objects holding `columns`), and you must never place `pageContent` inside a widget or emit a second pageContent block.
# - For layout container widgets (grouping KPIs/charts without their own data), include only nested `rows`/`columns` and omit data fields like apiUrl/series/value; they orchestrate children only.
# - Do not add data fields to containers; include `rows`/`columns` and optional title/sectionName/class only when the user provides them, and do not auto-insert a `type` the user didn’t supply.
# - Keep a container’s child rows/columns nested under that container (`pageContent[].columns[] -> widget2 -> rows[...]`); never lift them to top-level `pageContent`.
# - When a parent widgetId defines the container context, do NOT repeat that parent widgetId on child rows/columns; children keep their own widgetIds and inherit the parent implicitly.
# - Treat the layout-container as a hierarchy boundary keyed by its widgetId: rows/columns live inside that widget only—never alongside it at the same level.
# - Respect the user’s placement for containers: if the prompt says "Position: Row X Widget Y", place the container as a column in that row and nest its child rows/columns inside it. Do not move the container into a new row or elevate its child rows to pageContent.
# - Any widget that defines `rows` is a layout container; all of its rows and columns must stay strictly nested inside that widget and must never be moved, duplicated, or promoted to `pageContent` or any parallel layout level.
# - If the user asks for a layout container (or nested container), you MUST create that widget as a column in the specified parent row and keep all child rows/columns inside its `rows`; do not emit extra pageContent rows for those children.
#
#
# TEMPLATES
# Use these as the single source of truth:
# {DVM_TEMPLATES_KNOWLEDGE}
#
#
# TOOLS
# - save_dvm_config: when the user says “save” (or similar), call this tool with the current config, passing a file_prefix derived from dashboard name (snake/kebab ok) and a brief change_summary. Writes dvm_output/<prefix>-<version>.json; metadata (version, timestamp, change summary) is stored separately, not inside the main config. Always call this tool on explicit save requests.
# - load_dvm_config: only on explicit load/show + name (and optional version); return JSON (```json``` ok) and file path/version metadata.
# - compare_dvm_versions: only when asked to compare versions; loads two versions for agent-side diffing.
# - fix_layout_classes: when asked to fix/auto-assign layout classes, pass the current config; it will fill missing `class` fields per row using width heuristics (no external layout mapping).
# - layout_from_instructions: when the user gives natural-language layout positions (top/left/right/below/stack, etc.), call this to generate rows + responsive classes.
# - nest_container_children: when a layout-container exists and child rows were emitted at pageContent level, call this to re-nest them under the container before replying.
# - If a tool is requested and the payload is missing, ask the user for the minimum required fields, then call the tool. Do not respond with a non-tool answer when a tool invocation is requested.
# - If a user asks to save/load/compare/fix layout, you MUST use the corresponding tool; do not inline or simulate the result.
#
#
# OUTPUT
# - DESIGN mode: natural language / partial snippets only.
# - Step responses: return updated JSON and a one-line summary of what changed; update change history.
# - FINAL mode: only the full JSON (```json``` ok), with: dashboard-name; filters.apiUrl + filters.content; filter-level actions (if any) appended last; pageContent rows with unique widgetIds and sensible layout classes; widgets/actions from templates. Include save/load info if a tool was invoked (metadata stays external); only include version/lastModified/changeSummary if already present in the payload.
# - EXPLAIN mode: concise change log and rationales for each explicit edit; no internal chain-of-thought.
# """,
#     tools=[
#         save_dvm_config,
#         load_dvm_config,
#         compare_dvm_versions,
#         fix_layout_classes,
#         layout_from_instructions,
#         nest_container_children,
#     ],
# )
#
#
# print("✅ Agent 'dashboard_designer_agent' defined.")
#




from __future__ import annotations


import json
import re
from copy import deepcopy
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, List, Optional, Sequence, Tuple


from google.adk.agents import Agent
from google.adk.models.lite_llm import LiteLlm
from google.genai import types
from pydantic import BaseModel, ConfigDict, Field, field_validator


# ---------------------------------------------------------------------
# Paths & JSON loading
# ---------------------------------------------------------------------


BASE_DIR = Path(__file__).resolve().parents[2]
ASSETS_DIR = BASE_DIR / "dvm_assets" / "templates"
DVM_OUTPUT_DIR = BASE_DIR / "dvm_output"




class FlexibleModel(BaseModel):
    """Base Pydantic model that tolerates unknown DVM fields."""


    model_config = ConfigDict(extra="allow", populate_by_name=True)




class WidgetPlacement(FlexibleModel):
    id: Optional[str] = None
    type: Optional[str] = None
    position: Optional[str] = None
    class_: Optional[str] = Field(default=None, alias="class")


    @field_validator("position")
    @classmethod
    def _normalize_position(cls, value: Optional[str]) -> Optional[str]:
        return value.lower() if isinstance(value, str) else value




class WidgetRow(FlexibleModel):
    widgets: List[WidgetPlacement] = Field(default_factory=list)




class AbstractLayout(FlexibleModel):
    rows: List[WidgetRow] = Field(default_factory=list)




class ResponsiveLayoutEntry(FlexibleModel):
    row: int
    id: Optional[str] = None
    position: Optional[str] = None
    class_: str = Field(alias="class")




class LayoutFromInstructionsInput(BaseModel):
    instructions: str = ""


    @field_validator("instructions", mode="before")
    @classmethod
    def _ensure_text(cls, value: Any) -> str:
        if value is None:
            return ""
        if not isinstance(value, str):
            raise TypeError("instructions must be a string.")
        return value.strip()




class LayoutFromInstructionsOutput(BaseModel):
    rows: List[WidgetRow]
    layout: List[ResponsiveLayoutEntry]




class DvmRow(FlexibleModel):
    id: Optional[str] = None
    columns: List["DvmColumn"] = Field(default_factory=list)




class DvmColumn(FlexibleModel):
    widgetId: Optional[str] = Field(default=None, alias="widgetId")
    type: Optional[str] = None
    position: Optional[str] = None
    class_: Optional[str] = Field(default=None, alias="class")
    rows: Optional[List[DvmRow]] = None




class DvmConfig(FlexibleModel):
    dashboard_name: Optional[str] = Field(default=None, alias="dashboard-name")
    filters: Optional[Dict[str, Any]] = None
    pageContent: List[DvmRow] = Field(default_factory=list)




class SaveDvmConfigInput(BaseModel):
    dvm_json: Any
    file_prefix: str = Field(default="dvm", min_length=1)
    change_summary: str = Field(default="")


    @field_validator("file_prefix")
    @classmethod
    def _sanitize_prefix(cls, value: str) -> str:
        cleaned = re.sub(r"[^a-zA-Z0-9_-]", "-", value.strip())
        if not cleaned:
            raise ValueError("file_prefix must include alphanumeric characters.")
        return cleaned.lower()


    @field_validator("change_summary")
    @classmethod
    def _trim_summary(cls, value: str) -> str:
        return value.strip() if isinstance(value, str) else ""




class SaveDvmConfigOutput(BaseModel):
    saved_path: str
    version: int
    metadata_path: str
    lastModified: str
    changeSummary: str




class LoadDvmConfigInput(BaseModel):
    config_name: str
    version: int = 0


    @field_validator("config_name")
    @classmethod
    def _non_empty(cls, value: str) -> str:
        if not value or not value.strip():
            raise ValueError("config_name is required.")
        return value.strip()




class LoadDvmConfigOutput(BaseModel):
    config_name: str
    version: int
    path: str
    content: DvmConfig
    lastModified: str
    changeSummary: str
    metadata_path: str




class CompareDvmVersionsInput(BaseModel):
    config_name: str
    version_a: int
    version_b: int


    @field_validator("config_name")
    @classmethod
    def _check_name(cls, value: str) -> str:
        if not value or not value.strip():
            raise ValueError("config_name is required.")
        return value.strip()


    @field_validator("version_a", "version_b")
    @classmethod
    def _positive(cls, value: int) -> int:
        if value <= 0:
            raise ValueError("Versions must be positive integers.")
        return value




class VersionedConfig(BaseModel):
    version: int
    path: str
    content: DvmConfig
    lastModified: str
    changeSummary: str
    metadata_path: str




class CompareDvmVersionsOutput(BaseModel):
    a: VersionedConfig
    b: VersionedConfig




class DvmJsonInput(BaseModel):
    dvm_json: Any




class DvmConfigWrapper(BaseModel):
    fixed_config: DvmConfig




DvmRow.model_rebuild()
DvmColumn.model_rebuild()
DvmConfig.model_rebuild()




def _load_json(path: Path):
    with path.open("r", encoding="utf-8") as f:
        return json.load(f)




def _normalize_dvm_payload(raw: Any) -> Dict[str, Any]:
    """Accept raw string or dict and return a parsed DVM JSON object."""
    if isinstance(raw, dict):
        return raw
    if not isinstance(raw, str):
        raise ValueError("DVM payload must be a JSON string or dict.")


    candidate = raw.strip()
    if candidate.startswith("```"):
        candidate = candidate.strip("`")
    # Extract the first balanced-looking JSON object if there is surrounding text
    first_brace = candidate.find("{")
    last_brace = candidate.rfind("}")
    if first_brace != -1 and last_brace != -1:
        candidate = candidate[first_brace : last_brace + 1]


    try:
        return json.loads(candidate)
    except json.JSONDecodeError as exc:
        raise ValueError(
            "Invalid DVM JSON provided; please supply a valid JSON object."
        ) from exc




def _coerce_dvm_config(raw: Any) -> DvmConfig:
    """Convert arbitrary payload to validated DvmConfig."""
    return DvmConfig.model_validate(_normalize_dvm_payload(raw))




def _dump_dvm_config(config: DvmConfig) -> Dict[str, Any]:
    """Return alias-friendly dict representation of a config."""
    return config.model_dump(by_alias=True, exclude_none=True)




def _get_widget_field(widget: Any, field_name: str) -> Any:
    """Read a possibly-aliased field from BaseModel/dict objects."""
    alias = "class_" if field_name == "class" else field_name
    if isinstance(widget, BaseModel):
        return getattr(widget, alias, None)
    if isinstance(widget, dict):
        return widget.get(field_name)
    return getattr(widget, alias, None)




def _extract_widget_base_config(value: Any) -> Dict[str, Any]:
    """Return the full widget base config for wrapped or unwrapped templates."""
    if isinstance(value, dict) and isinstance(value.get("config"), dict):
        return value["config"]
    if isinstance(value, dict):
        return value
    return {}




def _next_dvm_path(prefix: str = "dvm") -> Tuple[Path, int]:
    """Compute the next incremental DVM output path."""
    DVM_OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    pattern = re.compile(rf"^{re.escape(prefix)}-(\d+)\.json$")
    max_id = 0
    for path in DVM_OUTPUT_DIR.glob(f"{prefix}-*.json"):
        match = pattern.match(path.name)
        if match:
            max_id = max(max_id, int(match.group(1)))
    next_id = max_id + 1
    return DVM_OUTPUT_DIR / f"{prefix}-{next_id}.json", next_id




def _list_saved_versions(prefix: str) -> List[Tuple[int, Path]]:
    """List saved versions for a given prefix as (version, path)."""
    pattern = re.compile(rf"^{re.escape(prefix)}-(\d+)\.json$", re.IGNORECASE)
    results: List[Tuple[int, Path]] = []
    if not DVM_OUTPUT_DIR.exists():
        return results
    for path in DVM_OUTPUT_DIR.glob("*.json"):
        match = pattern.match(path.name)
        if match:
            results.append((int(match.group(1)), path))
    return sorted(results, key=lambda x: x[0])




def classify_widget_type(widget_type: str) -> str:
    """
    Classify widget type into LARGE | MEDIUM | SMALL based on rules:
    - LARGE: treemap, bubble, waterfall, heatmap, map, combined
    - SMALL: KPI, number-stats, counters
    - MEDIUM: default (unknown types included)
    """
    t = (widget_type or "").lower()
    if any(
            k in t for k in ["treemap", "bubble", "waterfall", "heatmap", "map", "combined"]
    ):
        return "LARGE"
    if any(k in t for k in ["kpi", "number-stats", "counter", "counters"]):
        return "SMALL"
    return "MEDIUM"




def compute_width_for_row(widgets: Sequence[Any]) -> List[int]:
    """
    Compute widths for a row per the specified rules.
    Accepts widget dictionaries or Pydantic models.
    """
    widgets = list(widgets)
    if not widgets:
        return []


    def _pos(widget: Any) -> str:
        return (_get_widget_field(widget, "position") or "").lower()


    def _type(widget: Any) -> str:
        return (_get_widget_field(widget, "type") or "") or ""


    # Rule 6/7: full or stacked -> width 12 each
    if any(_pos(w) in {"full", "stacked"} for w in widgets):
        return [12 for _ in widgets]


    n = len(widgets)
    if n == 1:
        return [12]


    sizes = [classify_widget_type(_type(w)) for w in widgets]
    has_large = "LARGE" in sizes
    all_small = all(s == "SMALL" for s in sizes)
    all_medium = all(s == "MEDIUM" for s in sizes)


    # Rule 2: LARGE + other widgets
    if has_large and n >= 2:
        widths: List[int] = []
        large_assigned = False
        for s in sizes:
            if s == "LARGE" and not large_assigned:
                widths.append(7)
                large_assigned = True
            else:
                widths.append(0)  # placeholder
        non_large_count = n - 1
        share = 5 // non_large_count if non_large_count else 0
        remainder = 5 - share * non_large_count
        for idx in range(n):
            if widths[idx] == 0:
                widths[idx] = share
                if remainder > 0:
                    widths[idx] += 1
                    remainder -= 1
        return widths


    # Rule 3 & 4: all small or all medium
    if all_small or all_medium:
        base = 12 // n
        widths = [base for _ in widgets]
        remainder = 12 - base * n
        if remainder > 0:
            widths[0] += remainder
        return widths


    # Rule 5: mix of medium & small without large -> treat all as medium
    base = 12 // n
    widths = [base for _ in widgets]
    remainder = 12 - base * n
    if remainder > 0:
        widths[0] += remainder
    return widths




def compute_bootstrap_class(width: int) -> str:
    """
    Convert a width to the required Bootstrap class string.
    Always uses format: "col-12 col-sm-12 col-md-W col-lg-W"
    """
    w = max(1, min(12, int(round(width or 12))))
    return f"col-12 col-sm-12 col-md-{w} col-lg-{w}"




def _parse_layout_instructions(text: str) -> AbstractLayout:
    """
    Very lightweight parser for positional words.
    Returns an AbstractLayout made of WidgetRow entries.
    Assumes widgets are described in order; unknowns default to full-width rows.
    """
    tokens = text.lower().replace(",", " ").split()
    rows: List[WidgetRow] = []
    current_row: List[WidgetPlacement] = []
    widget_counter = 1


    def flush_row():
        nonlocal current_row
        if current_row:
            rows.append(WidgetRow(widgets=list(current_row)))
            current_row = []


    i = 0
    while i < len(tokens):
        t = tokens[i]
        if t in {"below", "under", "stack", "stacked"}:
            flush_row()
        elif t in {"top", "full"}:
            flush_row()
            current_row.append(
                WidgetPlacement(id=f"widget{widget_counter}", position="full")
            )
            widget_counter += 1
        elif t == "left":
            current_row.append(
                WidgetPlacement(id=f"widget{widget_counter}", position="left")
            )
            widget_counter += 1
        elif t == "right":
            current_row.append(
                WidgetPlacement(id=f"widget{widget_counter}", position="right")
            )
            widget_counter += 1
        i += 1


    flush_row()


    if not rows:
        rows = [
            WidgetRow(
                widgets=[WidgetPlacement(id="widget1", position="full")],
            )
        ]


    return AbstractLayout(rows=rows)




def layout_from_instructions(instructions: str) -> Dict[str, Any]:
    """
    Tool: Convert natural-language layout instructions into row-wise layout with classes,
    using the defined classification and width rules.
    """
    payload = LayoutFromInstructionsInput(instructions=instructions)
    parsed = _parse_layout_instructions(payload.instructions)
    layout_entries = generate_final_layout(parsed)
    output = LayoutFromInstructionsOutput(rows=parsed.rows, layout=layout_entries)
    return output.model_dump(by_alias=True)




def generate_final_layout(
        abstract_layout: AbstractLayout | Dict[str, Any],
) -> List[ResponsiveLayoutEntry]:
    """
    Generate the final layout JSON from an abstract layout.
    Preserves widget order and row structure.
    """
    if not isinstance(abstract_layout, AbstractLayout):
        abstract_layout = AbstractLayout.model_validate(abstract_layout or {})
    if not abstract_layout.rows:
        return []


    final: List[ResponsiveLayoutEntry] = []
    for row_idx, row in enumerate(abstract_layout.rows, start=1):
        widgets = row.widgets or []
        widths = compute_width_for_row(widgets)
        for widget, width in zip(widgets, widths):
            final.append(
                ResponsiveLayoutEntry(
                    row=row_idx,
                    id=widget.id,
                    position=widget.position,
                    class_=compute_bootstrap_class(width),
                )
            )
    return final




def save_dvm_config(
        dvm_json: Any, file_prefix: str = "dvm", change_summary: str = ""
) -> Dict[str, Any]:
    """
    Tool: Persist the finalized DVM JSON to disk with an incremental id.


    - Accepts a JSON string (with or without ``` fences) or a dict.
    - Saves to dvm_output/<file_prefix>-<version>.json where version increments from existing files.
    - Does NOT inject version metadata into the main config payload; metadata is stored separately.
    """
    payload = SaveDvmConfigInput(
        dvm_json=dvm_json, file_prefix=file_prefix, change_summary=change_summary
    )
    config = _coerce_dvm_config(payload.dvm_json)
    normalized = _dump_dvm_config(config)
    versions = _list_saved_versions(payload.file_prefix)
    next_version = versions[-1][0] + 1 if versions else 1
    path = DVM_OUTPUT_DIR / f"{payload.file_prefix}-{next_version}.json"
    timestamp = datetime.utcnow().isoformat() + "Z"
    path.write_text(json.dumps(normalized, indent=2), encoding="utf-8")
    metadata = {
        "saved_path": str(path),
        "version": next_version,
        "timestamp": timestamp,
        "change_summary": payload.change_summary or "saved configuration",
    }
    meta_path = path.with_suffix(".meta.json")
    meta_path.write_text(json.dumps(metadata, indent=2), encoding="utf-8")
    return SaveDvmConfigOutput(
        saved_path=str(path),
        version=next_version,
        metadata_path=str(meta_path),
        lastModified=timestamp,
        changeSummary=payload.change_summary or "saved configuration",
    ).model_dump(by_alias=True)




def load_dvm_config(config_name: str, version: int = 0) -> Dict[str, Any]:
    """
    Tool: Load a saved DVM JSON by name (prefix) from dvm_output.


    - Matches files like <config_name>-<version>.json (case-insensitive).
    - If version=0 (default), returns the highest version.
    """
    payload = LoadDvmConfigInput(config_name=config_name, version=version)
    if not DVM_OUTPUT_DIR.exists():
        raise FileNotFoundError(
            "No saved configurations found (dvm_output is missing)."
        )


    versions = _list_saved_versions(payload.config_name)
    if not versions:
        raise FileNotFoundError(
            f"No saved configuration found matching name '{payload.config_name}'."
        )


    if payload.version and all(v[0] != payload.version for v in versions):
        raise FileNotFoundError(
            f"No saved configuration found for '{payload.config_name}' at version {payload.version}."
        )


    target_version, target_path = next(
        (v for v in versions if v[0] == payload.version), versions[-1]
    )
    content = DvmConfig.model_validate_json(target_path.read_text(encoding="utf-8"))
    meta_path = target_path.with_suffix(".meta.json")
    meta: Dict[str, Any] = {}
    if meta_path.exists():
        try:
            meta = json.loads(meta_path.read_text(encoding="utf-8"))
        except json.JSONDecodeError:
            meta = {}
    last_modified = content.lastModified if hasattr(content, "lastModified") else ""
    if not last_modified:
        last_modified = meta.get("timestamp", "")
    change_summary = meta.get("change_summary", "")
    return LoadDvmConfigOutput(
        config_name=payload.config_name,
        version=target_version,
        path=str(target_path),
        content=content,
        lastModified=last_modified,
        changeSummary=change_summary,
        metadata_path=str(meta_path),
    ).model_dump(by_alias=True)




def compare_dvm_versions(
        config_name: str, version_a: int, version_b: int
) -> Dict[str, Any]:
    """
    Tool: Load two versions for comparison; returns both payloads for agent-side diffing.
    """
    payload = CompareDvmVersionsInput(
        config_name=config_name, version_a=version_a, version_b=version_b
    )
    versions = _list_saved_versions(payload.config_name)
    if not versions:
        raise FileNotFoundError(
            f"No saved configuration found matching name '{payload.config_name}'."
        )


    def _load_version(v: int) -> VersionedConfig:
        matches = [item for item in versions if item[0] == v]
        if not matches:
            raise FileNotFoundError(
                f"No saved configuration found for '{payload.config_name}' at version {v}."
            )
        _, path = matches[0]
        content = DvmConfig.model_validate_json(path.read_text(encoding="utf-8"))
        meta_path = path.with_suffix(".meta.json")
        meta: Dict[str, Any] = {}
        if meta_path.exists():
            try:
                meta = json.loads(meta_path.read_text(encoding="utf-8"))
            except json.JSONDecodeError:
                meta = {}
        last_modified = meta.get("timestamp", "")
        change_summary = meta.get("change_summary", "")
        return VersionedConfig(
            version=v,
            path=str(path),
            content=content,
            lastModified=last_modified,
            changeSummary=change_summary,
            metadata_path=str(meta_path),
        )


    result = CompareDvmVersionsOutput(
        a=_load_version(payload.version_a),
        b=_load_version(payload.version_b),
    )
    return result.model_dump(by_alias=True)




def fix_layout_classes(dvm_json: Any) -> Dict[str, Any]:
    """
    Tool: Apply Bootstrap classes to widgets based on row counts and type rules.
    - Accepts a JSON string (with or without ``` fences) or a dict.
    - For each row in pageContent, assigns class if missing, using compute_width_for_row.
    """
    payload = DvmJsonInput(dvm_json=dvm_json)
    config = _coerce_dvm_config(payload.dvm_json)
    for row in config.pageContent:
        columns = row.columns or []
        widths = compute_width_for_row(columns)
        for col, width in zip(columns, widths):
            if not col.class_:
                col.class_ = compute_bootstrap_class(width)
    return DvmConfigWrapper(fixed_config=config).model_dump(by_alias=True)




def nest_container_children(dvm_json: Any) -> Dict[str, Any]:
    """
    Tool: Ensure container widget children remain nested under the container, not at pageContent level.
    - Finds the first layout-container in pageContent.
    - Moves any subsequent top-level rows into that container's `rows` array.
    """
    payload = DvmJsonInput(dvm_json=dvm_json)
    config = _coerce_dvm_config(payload.dvm_json)
    page_content = config.pageContent
    if not page_content:
        return DvmConfigWrapper(fixed_config=config).model_dump(by_alias=True)


    new_page: List[DvmRow] = []
    container_rows: List[DvmRow] = []
    container_col: Optional[DvmColumn] = None
    container_found = False


    for row in page_content:
        if container_found:
            container_rows.append(row)
            continue


        cols = row.columns or []
        new_page.append(row)
        for col in cols:
            col_type = (col.type or "").lower()
            if col_type == "layout-container":
                container_found = True
                container_col = col
                existing = col.rows or []
                container_rows = existing
                col.rows = container_rows
                break


    if container_found and container_col:
        container_col.rows = container_rows
        config.pageContent = new_page


    return DvmConfigWrapper(fixed_config=config).model_dump(by_alias=True)




# ---------------------------------------------------------------------
# Build knowledge from filters.json, widgets.json, actions.json, layouts.json
# ---------------------------------------------------------------------




def build_dvm_knowledge_from_templates() -> str:
    """
    Read filters.json, widgets.json, and actions.json and convert them into a textual
    description for the LLM, so component definitions live in JSON and the agent reads
    the summary in its prompt.
    """
    filters_raw = _load_json(ASSETS_DIR / "filters.json")
    widgets_raw = _load_json(ASSETS_DIR / "widgets.json")
    actions_raw = _load_json(ASSETS_DIR / "actions.json")


    # filters.json has a single base object: "dropdown_base"
    base_filter_template = filters_raw["dropdown_base"]


    parts: List[str] = []


    # ---------------- FILTER TEMPLATE ----------------
    parts.append("FILTER TEMPLATE (from filters.json)\n")
    parts.append(
        "Use this base filter object as the starting point for each filter in "
        "the `filters.content` array. Adapt the fields based on the user's description:\n"
    )
    parts.append(json.dumps(base_filter_template, indent=2))
    parts.append(
        "\nNotes:\n"
        "- `name`: display name shown to the user.\n"
        "- `key`: internal key in snake_case.\n"
        "- `options`: key used to fetch / map dropdown options.\n"
        "- `type`: should remain 'dropdown' unless explicitly changed by the user.\n"
        "- `isMultiSelect`, `isfirstOptionDefault`, `isQueryParam`: set according to requirements.\n\n"
    )


    # ---------------- WIDGET TEMPLATES (FULLY GENERIC) ----------------
    parts.append("WIDGET TEMPLATES (from widgets.json)\n")
    parts.append(
        "Below are ALL available widget base configs. When the user asks for a component by key "
        "or describes a component that matches one of these, start from that widget's base config "
        "and adapt fields like `title`, `apiUrl`, `key`, data bindings, and listeners according to "
        "the user's instructions. Always override `widgetId` with a unique value like 'widget1', 'widget2', ...\n\n"
    )


    for key, value in widgets_raw.items():
        cfg = _extract_widget_base_config(value)
        w_type = cfg.get("type", "unknown")


        parts.append(f"Widget key: {key}\n")
        parts.append(f"Widget type (from config.type): {w_type}\n")
        parts.append("Base config JSON:\n")
        parts.append(json.dumps(cfg, indent=2))
        parts.append("\n")
        parts.append(
            "Usage notes: This widget is defined in widgets.json and has the type shown above. "
            "Whenever the user requests this widget key explicitly, or describes a component that "
            "matches this type and purpose, you should start from this base config, modify fields "
            "(such as title, apiUrl, data bindings, filter listeners, etc.) to match the user's description, "
            "and ensure `widgetId` is set to a unique value.\n\n"
        )


    # ---------------- ACTION TEMPLATES ----------------
    parts.append("ACTION TEMPLATES (from actions.json)\n")
    parts.append(
        "Use these base action configs (button/icon). When the user requests download, submit, "
        "or other actions, start from the matching template and adjust `label`, `key`, `apiUrl`, "
        "or visibility rules to fit the request. Attach the resulting objects to the relevant "
        "widget's `actions` array or action slot in the final JSON.\n\n"
    )


    for key, value in actions_raw.items():
        parts.append(f"Action key: {key}\n")
        parts.append("Base action JSON:\n")
        parts.append(json.dumps(value, indent=2))
        parts.append(
            "\nUsage notes: Begin with this template when adding an action of this type. "
            "Override fields to match the user's intent (e.g., change apiUrl, label, key). "
            "Keep type aligned with the template unless the user explicitly changes it.\n\n"
        )


    return "\n".join(parts)




# Build the knowledge block once at import time, but safely
try:
    DVM_TEMPLATES_KNOWLEDGE = build_dvm_knowledge_from_templates()
except Exception as e:
    # Fail-safe: agent still loads, but you’ll see the error in the prompt text
    DVM_TEMPLATES_KNOWLEDGE = (
        f"WARNING: Failed to load filters.json/widgets.json/actions.json. Error: {e}"
    )




# ---------------------------------------------------------------------
# Deterministic structured-spec handling
# ---------------------------------------------------------------------




def _extract_request_text(user_content: types.Content | None) -> str:
    if not user_content or not user_content.parts:
        return ""
    texts = [part.text for part in user_content.parts if getattr(part, "text", None)]
    return "\n".join(texts).strip()




def _clean_structured_value(value: str) -> str:
    cleaned = (value or "").strip()
    while len(cleaned) >= 2 and cleaned.startswith("{") and cleaned.endswith("}"):
        cleaned = cleaned[1:-1].strip()
    return cleaned.strip("{} \t")




def _to_snake_key(value: str) -> str:
    key = re.sub(r"[^a-zA-Z0-9]+", "_", value.strip().lower())
    return key.strip("_")




def _parse_filter_names(value: str) -> List[Dict[str, str]]:
    cleaned = _clean_structured_value(value)
    if not cleaned:
        return []
    if cleaned in {"{}", "[]"}:
        return []
    return [
        {"name": item.strip()} for item in re.split(r"[,;\n]+", cleaned) if item.strip()
    ]




def _strip_list_marker(value: str) -> str:
    return re.sub(r"^\s*[-*]\s*", "", value).strip()




def _parse_filter_field_line(line: str) -> Optional[Tuple[str, str]]:
    cleaned = _strip_list_marker(line)
    if ":" not in cleaned:
        return None
    key, value = cleaned.split(":", 1)
    key = key.strip().lower()
    if key not in {"name", "key", "options"}:
        return None
    return key, _clean_structured_value(value)




def _is_section_label(value: str) -> bool:
    label = value.strip().rstrip(":").lower()
    return label in {"filters", "actions", "widgets"}




def _parse_structured_dashboard_spec(text: str) -> Optional[Dict[str, Any]]:
    lines = [line.strip() for line in text.splitlines() if line.strip()]
    if not lines:
        return None


    dashboard_name = ""
    filter_specs: List[Dict[str, str]] = []
    current_filter: Optional[Dict[str, str]] = None
    in_filters_section = False
    widgets: List[Dict[str, Any]] = []
    current: Optional[Dict[str, Any]] = None
    position_re = re.compile(
        r"^position\s*:\s*row\s*\{?(\d+)\}?\s*widget\s*\{?(\d+)\}?\s*$",
        re.IGNORECASE,
    )


    for index, line in enumerate(lines):
        lowered = line.lower()


        if lowered.startswith("dashboard name"):
            value = ""
            if ":" in line:
                value = line.split(":", 1)[1]
            if not value.strip() and index + 1 < len(lines):
                next_value = lines[index + 1]
                if not _is_section_label(next_value):
                    value = next_value
            dashboard_name = _clean_structured_value(value)
            continue


        if lowered == "filters:" or lowered == "filters":
            in_filters_section = True
            continue


        if lowered == "actions:" or lowered.startswith("actions:"):
            if current_filter:
                filter_specs.append(current_filter)
                current_filter = None
            in_filters_section = False
            continue


        if lowered == "widgets:" or lowered == "widgets":
            if current_filter:
                filter_specs.append(current_filter)
                current_filter = None
            in_filters_section = False
            continue


        if lowered.startswith("list of filters"):
            value = ""
            if ":" in line:
                value = line.split(":", 1)[1]
            if not value.strip() and index + 1 < len(lines):
                next_value = lines[index + 1]
                if not _is_section_label(next_value):
                    value = next_value
            filter_specs.extend(_parse_filter_names(value))
            continue


        filter_field = _parse_filter_field_line(line) if in_filters_section else None
        if filter_field:
            field_name, field_value = filter_field
            if field_name == "name":
                if current_filter:
                    filter_specs.append(current_filter)
                current_filter = {"name": field_value}
            elif current_filter is not None:
                current_filter[field_name] = field_value
            continue


        position_match = position_re.match(line)
        if position_match:
            if current:
                widgets.append(current)
            current = {
                "row": int(position_match.group(1)),
                "order": int(position_match.group(2)),
                "widget_key": "",
                "type": "",
                "title": "",
            }
            continue


        if current and ":" in line:
            key, value = line.split(":", 1)
            key = key.strip().lower()
            value = _clean_structured_value(value)
            if key == "widget key":
                current["widget_key"] = value
            elif key == "type":
                current["type"] = value
            elif key == "title":
                current["title"] = value


    if current:
        widgets.append(current)


    usable_widgets = [
        widget for widget in widgets if widget.get("widget_key") or widget.get("type")
    ]
    if not dashboard_name or not usable_widgets:
        return None


    return {
        "dashboard_name": dashboard_name,
        "filters": filter_specs,
        "widgets": usable_widgets,
    }




def _normalized_widget_lookup_key(value: str) -> str:
    return (value or "").strip().lower().replace("_", "-")




def _find_widget_template(
        widgets_raw: Dict[str, Any], widget_key: str, widget_type: str
) -> Dict[str, Any]:
    candidates = [item for item in [widget_key, widget_type] if item]
    for candidate in candidates:
        if candidate in widgets_raw:
            return deepcopy(_extract_widget_base_config(widgets_raw[candidate]))


    normalized_candidates = {_normalized_widget_lookup_key(item) for item in candidates}
    for key, value in widgets_raw.items():
        cfg = _extract_widget_base_config(value)
        normalized_key = _normalized_widget_lookup_key(key)
        normalized_type = _normalized_widget_lookup_key(str(cfg.get("type", "")))
        if (
                normalized_key in normalized_candidates
                or normalized_type in normalized_candidates
        ):
            return deepcopy(cfg)


    fallback_type = widget_type or widget_key or "unknown"
    return {"widgetId": "auto", "type": fallback_type}




def _apply_widget_title(widget: Dict[str, Any], title: str) -> None:
    if not title:
        return
    header = widget.get("header")
    if "title" in widget:
        widget["title"] = title
    elif isinstance(header, dict) and "title" in header:
        header["title"] = title
    else:
        widget["title"] = title




def _build_filters_from_structured_spec(
        filter_specs: Sequence[Dict[str, str]],
) -> Dict[str, Any]:
    filters_raw = _load_json(ASSETS_DIR / "filters.json")
    base_filter = filters_raw.get("dropdown_base", {})
    content: List[Dict[str, Any]] = []


    for spec in filter_specs:
        cleaned_name = _clean_structured_value(spec.get("name", ""))
        if not cleaned_name:
            continue
        filter_config = deepcopy(base_filter)
        key_source = spec.get("key") or cleaned_name
        filter_key = _to_snake_key(_clean_structured_value(key_source))
        options_source = spec.get("options")
        options_value = (
            _to_snake_key(_clean_structured_value(options_source))
            if options_source
            else filter_key
        )
        filter_config["name"] = cleaned_name
        filter_config["key"] = filter_key
        filter_config["options"] = options_value
        content.append(filter_config)


    return {"apiUrl": "", "content": content}




def _build_dvm_from_structured_spec(parsed: Dict[str, Any]) -> Dict[str, Any]:
    widgets_raw = _load_json(ASSETS_DIR / "widgets.json")
    rows: Dict[int, List[Tuple[int, Dict[str, Any]]]] = {}
    generated_widget_count = 0


    for requested_widget in parsed["widgets"]:
        generated_widget_count += 1
        widget = _find_widget_template(
            widgets_raw,
            requested_widget.get("widget_key", ""),
            requested_widget.get("type", ""),
        )
        if not widget.get("widgetId") or widget.get("widgetId") == "auto":
            widget["widgetId"] = f"widget{generated_widget_count}"
        if requested_widget.get("type") and not widget.get("type"):
            widget["type"] = requested_widget["type"]
        _apply_widget_title(widget, requested_widget.get("title", ""))
        row_number = int(requested_widget.get("row") or 1)
        widget_order = int(requested_widget.get("order") or generated_widget_count)
        rows.setdefault(row_number, []).append((widget_order, widget))


    page_content = []
    for row_number in sorted(rows):
        ordered_widgets = [
            widget for _, widget in sorted(rows[row_number], key=lambda item: item[0])
        ]
        page_content.append({"id": f"row{row_number}", "columns": ordered_widgets})


    return {
        "dashboard-name": parsed["dashboard_name"],
        "filters": _build_filters_from_structured_spec(parsed.get("filters", [])),
        "pageContent": page_content,
    }




def _dashboard_spec_before_agent(callback_context: Any):
    request_text = _extract_request_text(
        getattr(callback_context, "user_content", None)
    )
    parsed = _parse_structured_dashboard_spec(request_text)
    if not parsed:
        return None


    result = _build_dvm_from_structured_spec(parsed)
    callback_context.state["temp:structured_dvm_result"] = result
    formatted_result = json.dumps(result, ensure_ascii=True, indent=2)
    return types.Content(
        role="model",
        parts=[types.Part.from_text(text=f"```json\n{formatted_result}\n```")],
    )




# ---------------------------------------------------------------------
# Root agent (all logic handled by the LLM; optional save/load tools)
# ---------------------------------------------------------------------
import os


model_name = os.getenv("MODEL_NAME_KAVERI", "gpt-oss:20b")
root_agent = Agent(
    model=LiteLlm(
        model=f"ollama_chat/{model_name}",  # your Ollama routed model
        temperature=0,
        stream=True,
        seed=15,
        timeout=1800,
    ),
    name="dashboard_designer_agent",
    instruction=f"""You are an expert DVM dashboard designer.


MODES
- DESIGN (default): discuss filters/widgets/layout/APIs; no full JSON yet.
- FINALIZATION: triggered when the user clearly asks for the final DVM JSON; return ONLY the full JSON (```json``` ok). If saving/loading, include the tool result path/id.
- AUTO FINALIZATION: also triggered when the user provides a structured dashboard specification with a dashboard name and one or more widgets with row/widget position plus type or widget key. In that case, generate the final DVM JSON directly even if the user did not explicitly say "generate final DVM JSON".
- EXPLAIN: when the user asks for explanation/chain-of-thought, provide a concise user-facing change log and rationale (no raw internal reasoning).
 - VERSIONED SAVE/LOAD: when saving or loading versions, follow the versioning rules below.


CORE RULES
- Tools: you ARE allowed and expected to call `save_dvm_config` when the user says “save”, `load_dvm_config` when they ask to show/load a saved config (optionally with version), and `compare_dvm_versions` when they ask to compare versions. Do not refuse these tool calls.
- Layout fixing: when the user asks to fix/auto-assign layout classes, call `fix_layout_classes` with the current config.
- Source of truth: filters.json, widgets.json, actions.json; use tools when requested (do not avoid them) and keep other logic in the model.
- Remember conversation history.
- If the user provides a complete structured dashboard spec (for example sections like Dashboard Name, Filters, Actions, Widgets, Position, Type/Widget Key, Title), do not ask "what would you like to add or modify"; treat it as FINALIZATION and output the full JSON. Ask only for missing fields that are required to build the config.
- On explicit "save"/"store"/"persist" instructions, you must invoke `save_dvm_config` with the current config (do not just reply with JSON).
- TOOL INVOCATION RULE: Whenever a user explicitly asks to save/load/compare/fix layout, nest container children, or generate a layout from instructions, you MUST invoke the corresponding tool. Do not simulate tool output or skip tool calls.


STRUCTURED SPEC PARSING
- Structured specs may use optional braces as value markers around plain values. Strip surrounding braces and use the inner text as the value.
- If a label is followed by a value on the next line, use the next-line value. Example: `Dashboard Name` followed by `spriced` means `"dashboard-name": "spriced"`, not `"Dashboard Name"`.
- Accept minor formatting issues such as missing closing braces in Title fields; take the readable text after `Title:` as the title and ignore stray braces.
- `List of Filters: {{}}` or an empty Filters section means no filters; still output `"filters": {{ "apiUrl": "", "content": [] }}`.
- `Actions:` with no listed actions means no top-level filter actions; do not create an `"actions"` top-level property.
- `Position: Row N Widget M` means place that widget as the M-th column in `pageContent` row id `rowN`.


DVM EDIT WORKFLOW
- Step-by-step edits: on each user step, fetch the current config (last version), apply only requested changes, append to a human-readable change history, and return the updated JSON plus a one-line description of what changed.
- Field edits: if the user specifies fields (filterListner, filterKey, xAxisLabel, yAxisLabel, class, title, series, colors, or any valid field), change ONLY those fields after selecting the base widget config from widgets.json; leave everything else unchanged.
- Full replacement: if the user provides a full config JSON, validate structure; if valid, adopt it as the current config and confirm (or report structural issues).
- Versioning: persist version/timestamp/changeSummary in metadata (sidecar) when saving. Config payload itself should stay as the user provided unless they explicitly include metadata. Do not overwrite older versions; always increment version (+1) on save.
- Retrieval/comparison: be able to show the latest version, load a specific version on request, and compare two versions when asked.


DVM SHAPE (FINAL MODE)
{{
  "dashboard-name": "<string>",
  "filters": {{ "apiUrl": "<string>", "content": [...] }},
  "pageContent": [ {{ "id": "row1", "columns": [ ... ] }}, ... ]
}}
Each widget needs: widgetId, type (from template config.type), class (Bootstrap), plus template fields.
Strict output contract:
- The top-level keys must be exactly `dashboard-name`, `filters`, and `pageContent` unless the user explicitly included other metadata.
- Use `dashboard-name`; never use `dashboardName` or `name`.
- `filters` must always be an object with `apiUrl` and `content`; never output `filters: {{}}` or `filters: []`.
- Do not output a top-level `actions` array. Global/filter actions belong at the end of `filters.content`.
- Each pageContent item must be a row object with `id` and `columns`; never use `row`, `rowIndex`, or `widgets`.
- Each widget must use `widgetId`; never use `id` or `widgetKey` in the final JSON.
- `Widget Key` from the user request is lookup-only. Use it only to select the template from widgets.json, then omit it from output.
- Never output simplified widgets that only contain id/type/title/class. Template-backed widgets must include the copied base config plus requested field edits.


FILTERS
- Start from the dropdown base template; set name/key/options/type/multi/default/query flags per intent.
- Place filter-level/global actions (if any) only at the END of filters.content (after all filters), using actions.json templates.
- Prompt mapping: when the user supplies a "List of Filters" and "Pred-defined filter" list, generate filters in that exact order, with the exact names/keys/options given. If the user says "Filters supporting multi-selection: All", set `isMultiSelect: true` for every filter unless the user explicitly overrides a specific filter.
- Pred-defined filter options: if the prompt provides inline options (e.g., Pricing Strategy: [Total Opportunity, Short Term]), set the filter options/defaultOptions accordingly and do NOT replace with an API config unless explicitly requested.


ACTIONS
- Use actions.json as templates; adjust label/key/apiUrl/visibility per request.
- Attach to widget.actions when widget-specific; otherwise append at the end of filters.content (never before filters).


LAYOUT
- Assign Bootstrap classes sensibly so rows do not exceed 12 columns per breakpoint; default to widget template classes when present. No external layout mappings are used.
- Size classification (by intent, not name):
  - LARGE if: many data points (50+), dense 2D visuals (treemap/heatmap/map/scatter with many points), hierarchical/multi-level, interactive drilldowns inside the chart, multiple dimensions (x/y/size/color/hierarchy), wide-aspect benefit, or user calls it primary/main/big/overview/left-side big chart.
  - MEDIUM if: single main axis, small/moderate data (5–40), readable at half-width, analytical but not dense. Unknown types default to MEDIUM unless user says otherwise.
  - SMALL if: single metric/KPI/stat/compact tile, lightweight, intended in groups; if user says metric/summary/stat/card → SMALL.
  - If user says “big chart” → LARGE; “small metric” → SMALL; otherwise default to MEDIUM when unknown.
  - When a layout-container is requested, place it as a column in the specified parent row (e.g., row1) and keep all child rows/columns inside `widget.rows`; do not wrap it in an extra pageContent row or duplicate row ids.


WIDGETS
- All widgets come from widgets.json.
- If user names a widget key or Type matching a widget key, copy the entire base config from widgets.json as the base. For wrapped templates use the full object under `config`; for unwrapped templates use the full widget object.
- If user provides rowId/widgetId, set them; else generate unique ids.
- If user requests edits to specific fields, change ONLY those; leave everything else unchanged; keep structure/formatting. For example, if only Title is provided, copy the whole base widget config and update/add only the title field.
- Do not replace a template-backed widget with a reduced object. Preserve nested template keys such as headers, customPayload, header.dropdowns, header.actions, pagination, sections, embeddedParams, excludePayloadParams, and filterListner unless the user explicitly changes them.
- Only the top-level layout uses `pageContent`; any layout container must nest its children under a `rows` array (with row objects holding `columns`), and you must never place `pageContent` inside a widget or emit a second pageContent block.
- For layout container widgets (grouping KPIs/charts without their own data), include only nested `rows`/`columns` and omit data fields like apiUrl/series/value; they orchestrate children only.
- Do not add data fields to containers; include `rows`/`columns` and optional title/sectionName/class only when the user provides them, and do not auto-insert a `type` the user didn’t supply.
- Keep a container’s child rows/columns nested under that container (`pageContent[].columns[] -> widget2 -> rows[...]`); never lift them to top-level `pageContent`.
- When a parent widgetId defines the container context, do NOT repeat that parent widgetId on child rows/columns; children keep their own widgetIds and inherit the parent implicitly.
- Treat the layout-container as a hierarchy boundary keyed by its widgetId: rows/columns live inside that widget only—never alongside it at the same level.
- Respect the user’s placement for containers: if the prompt says "Position: Row X Widget Y", place the container as a column in that row and nest its child rows/columns inside it. Do not move the container into a new row or elevate its child rows to pageContent.
- Any widget that defines `rows` is a layout container; all of its rows and columns must stay strictly nested inside that widget and must never be moved, duplicated, or promoted to `pageContent` or any parallel layout level.
- If the user asks for a layout container (or nested container), you MUST create that widget as a column in the specified parent row and keep all child rows/columns inside its `rows`; do not emit extra pageContent rows for those children.


TEMPLATES
Use these as the single source of truth:
{DVM_TEMPLATES_KNOWLEDGE}


TOOLS
- save_dvm_config: when the user says “save” (or similar), call this tool with the current config, passing a file_prefix derived from dashboard name (snake/kebab ok) and a brief change_summary. Writes dvm_output/<prefix>-<version>.json; metadata (version, timestamp, change summary) is stored separately, not inside the main config. Always call this tool on explicit save requests.
- load_dvm_config: only on explicit load/show + name (and optional version); return JSON (```json``` ok) and file path/version metadata.
- compare_dvm_versions: only when asked to compare versions; loads two versions for agent-side diffing.
- fix_layout_classes: when asked to fix/auto-assign layout classes, pass the current config; it will fill missing `class` fields per row using width heuristics (no external layout mapping).
- layout_from_instructions: when the user gives natural-language layout positions (top/left/right/below/stack, etc.), call this to generate rows + responsive classes.
- nest_container_children: when a layout-container exists and child rows were emitted at pageContent level, call this to re-nest them under the container before replying.
- If a tool is requested and the payload is missing, ask the user for the minimum required fields, then call the tool. Do not respond with a non-tool answer when a tool invocation is requested.
- If a user asks to save/load/compare/fix layout, you MUST use the corresponding tool; do not inline or simulate the result.


OUTPUT
- DESIGN mode: natural language / partial snippets only.
- Step responses: return updated JSON and a one-line summary of what changed; update change history.
- FINAL mode: only the full JSON (```json``` ok), with: dashboard-name; filters.apiUrl + filters.content; filter-level actions (if any) appended last; pageContent rows with unique widgetIds and sensible layout classes; widgets/actions from templates. Include save/load info if a tool was invoked (metadata stays external); only include version/lastModified/changeSummary if already present in the payload.
- Before returning FINAL JSON, self-check and rewrite if needed: no `dashboardName`, no `name`, no top-level `actions`, no `rowIndex`, no `row`, no `widgets`, no `widgetKey`, no widget `id`, and no `filters: {{}}`.
- EXPLAIN mode: concise change log and rationales for each explicit edit; no internal chain-of-thought.
""",
    tools=[
        save_dvm_config,
        load_dvm_config,
        compare_dvm_versions,
        fix_layout_classes,
        layout_from_instructions,
        nest_container_children,
    ],
    before_agent_callback=_dashboard_spec_before_agent,
)


print("Agent 'dashboard_designer_agent' defined.")