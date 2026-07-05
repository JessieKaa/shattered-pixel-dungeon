"""Shared pytest fixtures + repo-root discovery for the save-editor test suite.

`REPO_ROOT` resolves to the SPD fork root (3 levels up from this file:
tests/ → save-editor/ → tools/ → repo root). Cross-check tests that need to
invoke gradle use this instead of a fragile relative path.
"""

from __future__ import annotations

from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[3]
GRADLEW = REPO_ROOT / "gradlew"
