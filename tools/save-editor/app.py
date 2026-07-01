"""Minimal Flask app: parse a slot zip / single bundle, expose editable
fields, pack edited values back into a zip.

Run:
    # Development (with Vite dev server on :5173):
    cd frontend && npm run dev
    PORT=5001 python app.py

    # Production (serve pre-built Vue SPA):
    cd frontend && npm run build
    PORT=5001 python app.py
    # open http://127.0.0.1:5001

Endpoints:
    GET  /                -> SPA index.html (from frontend/dist)
    GET  /assets/<path>   -> Vite-built JS/CSS chunks
    POST /api/parse       multipart file upload -> JSON {meta, game, depths, warnings, __raw_files}
    POST /api/pack        JSON {meta, game, depths, __raw_files, force_meta_version?} -> zip bytes
"""

from __future__ import annotations

import io
import os
import re
from typing import Any

from flask import Flask, jsonify, request, send_file, send_from_directory

import spd_bundle

app = Flask(__name__, template_folder="templates")
app.config["MAX_CONTENT_LENGTH"] = 10 * 1024 * 1024  # 10 MB upload cap

FRONTEND_DIST = os.path.join(os.path.dirname(__file__), "frontend", "dist")


# Editable field whitelist. Anything outside this list is preserved untouched
# in the round-tripped bundle but never shown in the UI input names.
# (Mirrors the field table in docs/PLAN-save-editor.md rev3.)
HERO_FIELDS = ("HP", "HT", "pos", "lvl", "STR", "exp")
META_FIELDS = ("name", "depth", "level", "hero_class")
GAME_TOP_FIELDS = ("gold", "challenges", "seed", "daily")


def _extract_views(files: dict[str, dict]) -> dict[str, Any]:
    """Project raw files into UI views: meta / game / depths.

    Non-whitelist fields stay in `files` for round-trip; only the editable
    subset is exposed. Depths are keyed by integer depth number. Branch
    depth files (`depthN-branchM.dat`) are intentionally NOT split out —
    they survive via `__raw_files` and are re-emitted untouched on pack.
    """
    meta = files.get(spd_bundle.META_FILE, {})
    game = files.get("game.dat", {})
    depths: dict[str, dict] = {}
    depth_re = re.compile(r"^depth(\d+)\.dat$")
    for name, data in files.items():
        m = depth_re.match(name)
        if m:
            depths[m.group(1)] = data
    return {"meta": meta, "game": game, "depths": depths}


def _collect_warnings(meta: dict, game: dict, depths: dict[str, dict]) -> list[str]:
    warns: list[str] = []
    if game.get("daily"):
        warns.append(
            "game.daily=true — SaveSlotService.isSaveAllowed will reject save/load "
            "for daily runs; editing daily fields may break import."
        )
    seed = game.get("seed")
    if seed is not None and depths:
        warns.append(
            "Editing game.seed without deleting depth files may cause "
            "inconsistent world state."
        )
    hero = game.get("hero") or {}
    if isinstance(hero, dict):
        if isinstance(hero.get("HP"), (int, float)) and isinstance(
            hero.get("HT"), (int, float)
        ):
            if hero["HP"] > hero["HT"]:
                warns.append(
                    f"hero.HP ({hero['HP']}) > hero.HT ({hero['HT']}) — "
                    "allowed but unusual."
                )
    return warns


@app.errorhandler(spd_bundle.BundleError)
def _handle_bundle_error(e: spd_bundle.BundleError):
    return jsonify({"error": str(e)}), 400


@app.errorhandler(400)
def _handle_400(e):
    return jsonify({"error": str(e.description)}), 400


@app.errorhandler(413)
def _handle_too_large(e):
    return jsonify({"error": "payload too large (max 10 MB)"}), 413


@app.get("/")
def index():
    if not os.path.exists(os.path.join(FRONTEND_DIST, "index.html")):
        return (
            "Frontend not built. Run: cd frontend && npm install && npm run build",
            503,
        )
    return send_from_directory(FRONTEND_DIST, "index.html")


@app.get("/assets/<path:path>")
def assets(path):
    return send_from_directory(os.path.join(FRONTEND_DIST, "assets"), path)


@app.post("/api/parse")
def parse():
    if "file" not in request.files:
        return jsonify({"error": "missing 'file' field"}), 400
    upload = request.files["file"]
    raw = upload.read()
    if not raw:
        return jsonify({"error": "empty file"}), 400
    name = upload.filename or ""

    try:
        if name.lower().endswith(".zip"):
            files = spd_bundle.read_slot_zip(raw)
        else:
            # Single bundle file: parse and wrap.
            data = spd_bundle.read_bundle(raw)
            key = name or "single.bundle"
            files = {key: data}
    except spd_bundle.BundleError as e:
        raise

    views = _extract_views(files)
    views["warnings"] = _collect_warnings(
        views["meta"], views["game"], views["depths"]
    )
    # Ship the full files dict so /api/pack can round-trip entries the UI
    # never exposes — including depthN-branchM.dat and any future SPD naming
    # variants. The frontend treats this as opaque and passes it back.
    views["__raw_files"] = files
    return jsonify(views)


@app.post("/api/pack")
def pack():
    body = request.get_json(silent=True)
    if not isinstance(body, dict):
        return jsonify({"error": "expected JSON body"}), 400

    meta = body.get("meta") or {}
    game = body.get("game") or {}
    depths = body.get("depths") or {}
    raw_files = body.get("__raw_files") or {}
    force_version = body.get("force_meta_version", spd_bundle.SPD_VERSION_CODE)

    if not isinstance(meta, dict) or not isinstance(game, dict):
        return jsonify({"error": "meta / game must be objects"}), 400
    if not isinstance(depths, dict):
        return jsonify({"error": "depths must be object"}), 400
    if not isinstance(raw_files, dict):
        return jsonify({"error": "__raw_files must be object"}), 400

    # Start from raw_files (preserves branch depth files and any field the
    # UI doesn't expose), then overlay edited meta/game/depths on top.
    files: dict[str, dict] = {}
    for k, v in raw_files.items():
        if isinstance(v, dict) and spd_bundle.safe_entry_name(k):
            files[k] = v

    if meta:
        files[spd_bundle.META_FILE] = meta
    if game:
        files["game.dat"] = game
    if isinstance(depths, dict):
        for depth_key, data in depths.items():
            if not isinstance(data, dict):
                continue
            if not str(depth_key).isdigit():
                continue
            files[f"depth{depth_key}.dat"] = data

    force_ver_arg: int | None
    if force_version is None or force_version is False:
        force_ver_arg = None
    else:
        try:
            force_ver_arg = int(force_version)
        except (TypeError, ValueError):
            return jsonify({"error": "force_meta_version must be int"}), 400

    zip_bytes = spd_bundle.pack_slot_zip(files, meta_version=force_ver_arg)
    return send_file(
        io.BytesIO(zip_bytes),
        mimetype="application/zip",
        as_attachment=True,
        download_name="slot.zip",
    )


if __name__ == "__main__":
    port = int(os.environ.get("PORT", "5000"))
    app.run(host="127.0.0.1", port=port, debug=True)
