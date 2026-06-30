"""Acceptance #8 cross-check: a zip produced by spd_bundle.pack_slot_zip
must be importable by the Java fork's SaveSlotIO.readSlotFromStream.

This test orchestrates a real round-trip:
  1. Python pack_slot_zip(...) writes real zip bytes to a tmp file.
  2. subprocess invokes `{REPO_ROOT}/gradlew :core:test --tests
     *SaveSlotIOPythonZipTest` with `SPD_ZIP_PATH=<tmp>` in env.
  3. The JUnit fixture reads SPD_ZIP_PATH, calls SaveSlotIO.readSlotFromStream,
     asserts ok=true and version=896, and cleanups staging.
  4. Python asserts gradle exit code 0.

Skipping policy: the test is skipped if gradlew is missing or if the
environment variable SPD_SKIP_JAVA_CROSS_CHECK is set (useful when running
pytest without a JDK installed). When skipped, the test prints guidance so
the operator knows Acceptance #8 is not exercised.
"""

from __future__ import annotations

import os
import subprocess
import sys
import tempfile
from pathlib import Path

import pytest

import spd_bundle as sb


# repo root = this file's parents[3]:
# tests/test_save_slot_io_cross_check.py → tests/ → save-editor/ → tools/ → root
REPO_ROOT = Path(__file__).resolve().parents[3]
GRADLEW = REPO_ROOT / "gradlew"


pytestmark = pytest.mark.slow


def test_pack_output_imports_via_java_save_slot_io():
    if not GRADLEW.exists():
        pytest.skip(
            f"gradlew not found at {GRADLEW}; cannot run Java cross-check"
        )
    if os.environ.get("SPD_SKIP_JAVA_CROSS_CHECK") == "1":
        pytest.skip("SPD_SKIP_JAVA_CROSS_CHECK=1 in env")

    # Synthesize a slot zip via the same API the Flask pack endpoint calls.
    meta = {
        "name": "pytest-cross-check",
        "version": sb.SPD_VERSION_CODE,
        "depth": 1,
        "level": 1,
        "hero_class": "WARRIOR",
        "saved_at": 0,
    }
    game = {
        "version": sb.SPD_VERSION_CODE,
        "seed": 1,
        "daily": False,
        "challenges": 0,
        "gold": 0,
        "hero": {"HP": 1, "HT": 1, "pos": 1, "lvl": 1},
    }
    files = {
        "meta.bundle": meta,
        "game.dat": game,
    }
    zip_bytes = sb.pack_slot_zip(files, meta_version=sb.SPD_VERSION_CODE)

    with tempfile.NamedTemporaryFile(
        suffix=".zip", prefix="spd-cross-check-", delete=False
    ) as fh:
        fh.write(zip_bytes)
        zip_path = fh.name

    try:
        env = {**os.environ, "SPD_ZIP_PATH": zip_path}
        # cwd=REPO_ROOT so `./gradlew` resolves; pass absolute path to be safe.
        result = subprocess.run(
            [str(GRADLEW), ":core:test", "--tests",
             "*SaveSlotIOPythonZipTest"],
            cwd=str(REPO_ROOT),
            env=env,
            capture_output=True,
            text=True,
            timeout=300,
        )
        if result.returncode != 0:
            sys.stderr.write("--- gradle stdout ---\n")
            sys.stderr.write(result.stdout[-4000:])
            sys.stderr.write("--- gradle stderr ---\n")
            sys.stderr.write(result.stderr[-4000:])
        assert result.returncode == 0, (
            f"gradle exit {result.returncode}; see stderr above"
        )
    finally:
        try:
            os.unlink(zip_path)
        except OSError:
            pass
