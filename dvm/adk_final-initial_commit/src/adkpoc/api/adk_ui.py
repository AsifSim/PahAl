from __future__ import annotations

from fastapi import FastAPI
from fastapi.responses import RedirectResponse
from fastapi.staticfiles import StaticFiles


def configure_dev_ui(app: FastAPI, *, web_assets_dir: str | None) -> None:
    if not web_assets_dir:
        return

    import mimetypes

    mimetypes.add_type("application/javascript", ".js", True)
    mimetypes.add_type("text/javascript", ".js", True)

    @app.get("/")
    async def redirect_root_to_dev_ui():
        return RedirectResponse("/dev-ui/")

    @app.get("/dev-ui")
    async def redirect_dev_ui_add_slash():
        return RedirectResponse("/dev-ui/")

    app.mount(
        "/dev-ui/",
        StaticFiles(directory=web_assets_dir, html=True, follow_symlink=True),
        name="static",
    )
