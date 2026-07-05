"""Validate the auto-generated item-labels-zh.json fixture.

These tests guard against regressions in extract-item-labels.mjs — they
verify that the JSON exists, covers the expected shape (single-segment
items.*, nested items.armor.*, and `$` nested classes), and that no
`%s` placeholder leaks into the rendered values.
"""

from __future__ import annotations

import json
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[3]
JSON_PATH = (
    REPO_ROOT
    / "tools/save-editor/frontend/src/data/item-labels-zh.json"
)


def _load() -> dict[str, str]:
    assert JSON_PATH.exists(), f"missing: {JSON_PATH}. Run extract-item-labels.mjs"
    return json.loads(JSON_PATH.read_text(encoding="utf-8"))


def test_entry_count_close_to_upstream():
    data = _load()
    assert 350 <= len(data) <= 450, f"unexpected entry count: {len(data)}"


def test_basic_keys_present():
    data = _load()
    assert data.get("items.armor.clotharmor") == "布甲"
    assert data.get("items.ankh") == "重生十字架"
    assert data.get("items.wands.wandofmagicmissile") == "魔弹法杖"


def test_dollar_nested_key_preserved():
    data = _load()
    nested = [k for k in data if "$" in k]
    assert nested, "no $ nested keys found"
    assert "items.bombs.bomb$doublebomb" in data
    assert data["items.bombs.bomb$doublebomb"] == "一对炸弹"


def test_no_percent_s_residual():
    data = _load()
    offenders = [k for k, v in data.items() if "%s" in v]
    assert not offenders, f"%s leaked into values: {offenders[:5]}"


def test_curse_and_enchantment_values_are_clean():
    data = _load()
    assert data.get("items.armor.curses.antientropy") == "反熵"
    assert data.get("items.weapon.enchantments.blazing") == "烈焰"
