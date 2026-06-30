"""Pytest suite for spd_bundle (17 cases, covers reviewer-required edge cases).

Covers: round-trip, version force, path traversal, zip bomb (real decompressed
bytes), duplicate entry, directory entry, missing meta, BOM rejection, empty
bytes, pseudo gzip header, mixed gzip+raw round-trip, pack unsafe name rejection.
"""

from __future__ import annotations

import gzip
import io
import json
import zipfile

import pytest

import spd_bundle as sb


# ---- helpers ---------------------------------------------------------------


def make_meta(**overrides) -> dict:
    base = {
        "name": "test",
        "version": sb.SPD_VERSION_CODE,
        "depth": 1,
        "level": 1,
        "hero_class": "WARRIOR",
        "saved_at": 0,
    }
    base.update(overrides)
    return base


def make_game(**overrides) -> dict:
    base = {
        "__className": "com.shatteredpixel.shatteredpixeldungeon.Dungeon$GameLevel",
        "version": sb.SPD_VERSION_CODE,
        "init_ver": sb.SPD_VERSION_CODE,
        "seed": 12345,
        "daily": False,
        "challenges": 0,
        "gold": 100,
        "hero": {
            "__className": "com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero",
            "HP": 20,
            "HT": 20,
            "pos": 100,
            "lvl": 1,
            "STR": 10,
            "exp": 0,
            "attackSkill": 10,
            "defenseSkill": 5,
        },
    }
    base.update(overrides)
    return base


def make_depth(n: int) -> dict:
    return {
        "__className": "com.shatteredpixel.shatteredpixeldungeon.levels.CaveLevel",
        "depth": n,
        "width": 32,
        "height": 32,
        "cells": [0] * (32 * 32),
    }


def write_zip(entries: dict[str, bytes]) -> bytes:
    """Build a raw zip from pre-serialized entry payloads."""
    bio = io.BytesIO()
    with zipfile.ZipFile(bio, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        for name, data in entries.items():
            zf.writestr(name, data)
    return bio.getvalue()


# ---- 1. round_trip_meta_bundle_uncompressed --------------------------------


def test_round_trip_meta_bundle_uncompressed():
    meta = make_meta(name="hello")
    raw = sb.write_bundle(meta)
    # Small payload → uncompressed, no gzip magic.
    assert raw[:2] != b"\x1f\x8b"
    back = sb.read_bundle(raw)
    assert back == meta


# ---- 2. round_trip_game_dat_gzipped ----------------------------------------


def test_round_trip_game_dat_gzipped():
    game = make_game()
    # Pad to ensure payload exceeds COMPRESS_THRESHOLD.
    game["big_field"] = "x" * 1024
    raw = sb.write_bundle(game)
    assert raw[:2] == b"\x1f\x8b"
    back = sb.read_bundle(raw)
    assert back == game


# ---- 3. pack_slot_zip_meta_first -------------------------------------------


def test_pack_slot_zip_meta_first():
    files = {
        "meta.bundle": make_meta(),
        "game.dat": make_game(),
        "depth1.dat": make_depth(1),
    }
    zip_bytes = sb.pack_slot_zip(files)
    with zipfile.ZipFile(io.BytesIO(zip_bytes)) as zf:
        names = [i.filename for i in zf.infolist()]
    assert names[0] == "meta.bundle"


# ---- 4. pack_slot_zip_forces_version ---------------------------------------


def test_pack_slot_zip_forces_version():
    meta = make_meta(version=1)  # wrong, should be overwritten
    files = {"meta.bundle": meta, "game.dat": make_game()}
    zip_bytes = sb.pack_slot_zip(files, meta_version=896)
    with zipfile.ZipFile(io.BytesIO(zip_bytes)) as zf:
        meta_bytes = zf.read("meta.bundle")
    parsed = sb.read_bundle(meta_bytes)
    assert parsed["version"] == 896


def test_pack_slot_zip_meta_version_none_preserves_input():
    meta = make_meta(version=999)
    files = {"meta.bundle": meta, "game.dat": make_game()}
    zip_bytes = sb.pack_slot_zip(files, meta_version=None)
    with zipfile.ZipFile(io.BytesIO(zip_bytes)) as zf:
        parsed = sb.read_bundle(zf.read("meta.bundle"))
    assert parsed["version"] == 999


# ---- 5. safe_entry_name_rejects_traversal ----------------------------------


@pytest.mark.parametrize(
    "bad",
    ["../evil", "a/b", "a\\b", "a:b", ".", "..", "", None, "a b", "café"],
)
def test_safe_entry_name_rejects_traversal(bad):
    assert sb.safe_entry_name(bad) is False


@pytest.mark.parametrize("ok", ["meta.bundle", "game.dat", "depth1.dat", "a-b_c.d"])
def test_safe_entry_name_accepts_valid(ok):
    assert sb.safe_entry_name(ok) is True


# ---- 6. read_slot_zip_rejects_too_many_entries -----------------------------


def test_read_slot_zip_rejects_too_many_entries():
    entries: dict[str, bytes] = {"meta.bundle": b"{}"}
    # 64 is the cap; add 64 non-meta entries (plus meta = 65).
    for i in range(64):
        entries[f"f{i}.dat"] = b"{}"
    zip_bytes = write_zip(entries)
    with pytest.raises(sb.BundleError, match="too_many_entries"):
        sb.read_slot_zip(zip_bytes)


# ---- 7. read_bundle_invalid_json_raises_bundle_error -----------------------


def test_read_bundle_invalid_json_raises_bundle_error():
    with pytest.raises(sb.BundleError, match="json decode failed"):
        sb.read_bundle(b"{not json")


# ---- 8. pack_imported_back_into_save_slot_io_white_list --------------------


def test_pack_imported_back_into_save_slot_io_white_list():
    files = {
        "meta.bundle": make_meta(),
        "game.dat": make_game(),
        "depth1.dat": make_depth(1),
        "depth5.dat": make_depth(5),
    }
    zip_bytes = sb.pack_slot_zip(files)
    with zipfile.ZipFile(io.BytesIO(zip_bytes)) as zf:
        for info in zf.infolist():
            assert sb.safe_entry_name(info.filename)


# ---- 9. read_bundle_empty_bytes_raises -------------------------------------


def test_read_bundle_empty_bytes_raises():
    with pytest.raises(sb.BundleError, match="too short"):
        sb.read_bundle(b"")
    with pytest.raises(sb.BundleError, match="too short"):
        sb.read_bundle(b"\x1f")  # 1 byte, can't be magic


# ---- 10. read_bundle_pseudo_gzip_header_raises -----------------------------


def test_read_bundle_pseudo_gzip_header_raises():
    # Starts with gzip magic but the rest is junk.
    raw = b"\x1f\x8b" + b"\x00" * 20
    with pytest.raises(sb.BundleError, match="gzip decode failed"):
        sb.read_bundle(raw)


# ---- 11. read_bundle_bom_rejected ------------------------------------------


def test_read_bundle_bom_rejected():
    bom_json = "﻿" + json.dumps({"a": 1})
    with pytest.raises(sb.BundleError, match="BOM"):
        sb.read_bundle(bom_json.encode("utf-8"))


# ---- 12. read_slot_zip_rejects_directory_entry -----------------------------


def test_read_slot_zip_rejects_directory_entry():
    bio = io.BytesIO()
    with zipfile.ZipFile(bio, "w") as zf:
        zf.writestr("meta.bundle", b"{}")
        zf.writestr("subdir/", b"")
    with pytest.raises(sb.BundleError, match="directory"):
        sb.read_slot_zip(bio.getvalue())


# ---- 13. read_slot_zip_rejects_duplicate_entry ------------------------------


def test_read_slot_zip_rejects_duplicate_entry():
    # Need raw zip construction; zipfile normalizes dups on write via separate
    # writestr calls — both entries are kept.
    bio = io.BytesIO()
    with zipfile.ZipFile(bio, "w") as zf:
        zf.writestr("meta.bundle", b"{}")
        zf.writestr("meta.bundle", b"{}")  # duplicate
    with pytest.raises(sb.BundleError, match="duplicate"):
        sb.read_slot_zip(bio.getvalue())


# ---- 14. read_slot_zip_rejects_zip_bomb (real decompressed bytes) ----------


def test_read_slot_zip_rejects_zip_bomb():
    # Real zip-bomb: zip-decompressed (not gzip) bytes exceed 64 MB.
    # SaveSlotIO's MAX_TOTAL_BYTES counts zip-decompressed bytes, so we
    # construct an entry whose zip-decompressed size is > cap. The stored
    # zip is small (ZIP_DEFLATED compresses repetitive bytes well).
    huge = b'{"big":"' + b"x" * (sb.MAX_TOTAL_BYTES + 1024) + b'"}'
    entries = {"meta.bundle": b"{}", "bomb.dat": huge}
    zip_bytes = write_zip(entries)
    with pytest.raises(sb.BundleError, match="zip_too_large"):
        sb.read_slot_zip(zip_bytes)


# ---- 15. read_slot_zip_missing_meta ----------------------------------------


def test_read_slot_zip_missing_meta():
    zip_bytes = write_zip({"game.dat": b"{}"})
    with pytest.raises(sb.BundleError, match="missing_meta"):
        sb.read_slot_zip(zip_bytes)


# ---- 16. pack_then_read_round_trip_mixed -----------------------------------


def test_pack_then_read_round_trip_mixed():
    files = {
        "meta.bundle": make_meta(depth=5),
        "game.dat": make_game(hero={"HP": 99, "HT": 99, "lvl": 5} | {"big": "x" * 500}),
        "depth1.dat": make_depth(1),
        "depth5.dat": make_depth(5),
    }
    zip_bytes = sb.pack_slot_zip(files)
    back = sb.read_slot_zip(zip_bytes)
    assert set(back.keys()) == set(files.keys())
    assert back["meta.bundle"]["depth"] == 5
    assert back["game.dat"]["hero"]["HP"] == 99
    assert back["depth1.dat"]["depth"] == 1
    assert back["depth5.dat"]["depth"] == 5


# ---- 17. pack_rejects_unsafe_entry_name ------------------------------------


def test_pack_rejects_unsafe_entry_name():
    files = {
        "meta.bundle": make_meta(),
        "../evil": make_depth(1),
    }
    with pytest.raises(sb.BundleError, match="invalid_zip_entry"):
        sb.pack_slot_zip(files)


def test_pack_rejects_too_many_entries():
    files = {"meta.bundle": make_meta()}
    for i in range(sb.MAX_ENTRY_COUNT):
        files[f"f{i}.dat"] = {"i": i}
    with pytest.raises(sb.BundleError, match="too_many_entries"):
        sb.pack_slot_zip(files)


def test_pack_missing_meta_raises():
    with pytest.raises(sb.BundleError, match="missing_meta"):
        sb.pack_slot_zip({"game.dat": make_game()})


# ---- 18. branch depth files survive round-trip (rev4 must-fix) ------------


def test_pack_preserves_branch_depth_files():
    """SPD writes depthN-branchM.dat for branch floors (GamesInProgress.java:50).
    Pack must preserve them untouched, even though the UI only exposes
    depthN.dat in the depths view."""
    files = {
        "meta.bundle": make_meta(),
        "game.dat": make_game(),
        "depth17.dat": make_depth(17),
        "depth17-branch1.dat": {
            "__className": "...levels.CavesBossLevel",
            "depth": 17,
            "branch": 1,
            "width": 16,
            "height": 16,
            "cells": [0] * 256,
        },
    }
    zip_bytes = sb.pack_slot_zip(files)
    with zipfile.ZipFile(io.BytesIO(zip_bytes)) as zf:
        names = [i.filename for i in zf.infolist()]
        assert "depth17-branch1.dat" in names, names
        back = sb.read_bundle(zf.read("depth17-branch1.dat"))
    assert back["branch"] == 1
    assert back["depth"] == 17


def test_read_slot_zip_accepts_branch_depth_files():
    files = {
        "meta.bundle": make_meta(),
        "game.dat": make_game(),
        "depth5.dat": make_depth(5),
        "depth5-branch2.dat": make_depth(5) | {"branch": 2},
    }
    zip_bytes = sb.pack_slot_zip(files)
    parsed = sb.read_slot_zip(zip_bytes)
    assert "depth5-branch2.dat" in parsed, list(parsed.keys())
    assert parsed["depth5-branch2.dat"]["branch"] == 2


# ---- 19. hero.weapon round-trip with nested wand (MagesStaff) ---------------


def test_round_trip_hero_weapon_with_nested_wand():
    """MagesStaff nests a wand field that is itself a dict with __className.
    Verify the existing pack/read round-trip preserves the nested structure
    that the new raw-JSON textarea will expose to users."""
    nested_wand = {
        "__className": "com.shatteredpixel.shatteredpixeldungeon.items.wands.WandOfMagicMissile",
        "quantity": 1,
        "level": 0,
        "curriedCharges": 2,
        "zapped": False,
    }
    weapon = {
        "__className": "com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.MagesStaff",
        "quantity": 1,
        "level": 0,
        "levelKnown": True,
        "cursed": False,
        "augment": "NONE",
        "wand": nested_wand,
    }
    hero = make_game()["hero"]
    hero["weapon"] = weapon
    game = make_game(hero=hero)
    files = {"meta.bundle": make_meta(), "game.dat": game}
    zip_bytes = sb.pack_slot_zip(files)
    back = sb.read_slot_zip(zip_bytes)
    w = back["game.dat"]["hero"]["weapon"]
    assert w["__className"].endswith("MagesStaff")
    assert w["wand"]["__className"].endswith("WandOfMagicMissile")
    assert w["wand"]["curriedCharges"] == 2


# ---- 20. hero.inventory round-trip with nested container (VelvetPouch) -----


def test_round_trip_hero_inventory_with_nested_container():
    """VelvetPouch nests an `inventory` list of seeds. Round-trip must
    preserve the nested array — the textarea editor allows editing this
    whole structure as raw JSON."""
    seed = {
        "__className": "com.shatteredpixel.shatteredpixeldungeon.items.Seed",
        "quantity": 3,
        "level": 0,
        "plantDepth": 5,
    }
    pouch = {
        "__className": "com.shatteredpixel.shatteredpixeldungeon.items.bags.VelvetPouch",
        "quantity": 1,
        "level": 0,
        "inventory": [seed],
    }
    hero = make_game()["hero"]
    hero["inventory"] = [
        {"__className": "com.shatteredpixel.shatteredpixeldungeon.items.food.Food",
         "quantity": 1},
        pouch,
    ]
    game = make_game(hero=hero)
    files = {"meta.bundle": make_meta(), "game.dat": game}
    zip_bytes = sb.pack_slot_zip(files)
    back = sb.read_slot_zip(zip_bytes)
    inv = back["game.dat"]["hero"]["inventory"]
    assert len(inv) == 2
    assert inv[0]["__className"].endswith("Food")
    pouch_back = inv[1]
    assert pouch_back["__className"].endswith("VelvetPouch")
    assert isinstance(pouch_back["inventory"], list)
    assert pouch_back["inventory"][0]["quantity"] == 3
    assert pouch_back["inventory"][0]["plantDepth"] == 5


# ---- 21. nested dict field set + per-field types preserved (form schema) ----


def test_round_trip_nested_dict_preserves_field_set():
    """The form schema editor renders every dict field as an editable input
    keyed by inferred type. Round-trip must preserve both the field-name set
    and the per-field runtime type (so the form re-renders with the same
    input controls the user saw before pack)."""
    nested_wand = {
        "__className": "com.shatteredpixel.shatteredpixeldungeon.items.wands.WandOfMagicMissile",
        "quantity": 1,
        "level": 0,
        "curriedCharges": 2,         # int
        "partialCharge": 0.5,         # float (force non-integer)
        "zapped": False,              # bool
        "curseName": "Disarming",     # str (non-__className)
    }
    weapon = {
        "__className": "com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.MagesStaff",
        "quantity": 1,
        "level": 3,
        "cursed": False,
        "wand": nested_wand,
    }
    hero = make_game()["hero"]
    hero["weapon"] = weapon
    game = make_game(hero=hero)
    files = {"meta.bundle": make_meta(), "game.dat": game}
    zip_bytes = sb.pack_slot_zip(files)
    back = sb.read_slot_zip(zip_bytes)

    w_back = back["game.dat"]["hero"]["weapon"]
    expected_outer = set(weapon.keys())
    assert set(w_back.keys()) == expected_outer, (
        f"outer key set drift: {set(w_back.keys())} vs {expected_outer}"
    )
    # Per-field type preservation (excluding the nested wand, checked separately).
    for k in expected_outer:
        if k == "wand":
            continue
        assert type(w_back[k]) is type(weapon[k]), (
            f"outer field {k}: type {type(w_back[k]).__name__} "
            f"!= {type(weapon[k]).__name__}"
        )

    wand_back = w_back["wand"]
    expected_wand = set(nested_wand.keys())
    assert set(wand_back.keys()) == expected_wand, (
        f"wand key set drift: {set(wand_back.keys())} vs {expected_wand}"
    )
    for k in expected_wand:
        assert type(wand_back[k]) is type(nested_wand[k]), (
            f"wand field {k}: type {type(wand_back[k]).__name__} "
            f"!= {type(nested_wand[k]).__name__}"
        )

    # Spot-check the float field survives at full precision (form parses
    # floats via parseFloat; JSON round-trip must not truncate).
    assert wand_back["partialCharge"] == 0.5
    assert isinstance(wand_back["partialCharge"], float)


# ---- 22. list with mixed-typed items preserves each item's field set --------


def test_round_trip_list_with_mixed_typed_items_preserves_each_item():
    """hero.inventory can hold items with heterogeneous field sets (Food has
    only int quantity; Waterskin adds float volume; VelvetPouch nests a list
    of seeds). Round-trip must preserve every item's keys and per-field types
    so the form re-render shows the same controls the user edited."""
    food = {
        "__className": "com.shatteredpixel.shatteredpixeldungeon.items.food.Food",
        "quantity": 1,                # int
        "level": 0,                   # int
    }
    waterskin = {
        "__className": "com.shatteredpixel.shatteredpixeldungeon.items.Waterskin",
        "quantity": 1,
        "level": 0,
        "volume": 1.5,                # float (force non-integer)
        "quickslotpos": 2,            # int
    }
    seed = {
        "__className": "com.shatteredpixel.shatteredpixeldungeon.items.Seed",
        "quantity": 3,
        "plantDepth": 5,
    }
    pouch = {
        "__className": "com.shatteredpixel.shatteredpixeldungeon.items.bags.VelvetPouch",
        "quantity": 1,
        "level": 0,
        "inventory": [seed],          # nested list of dict
    }
    hero = make_game()["hero"]
    hero["inventory"] = [food, waterskin, pouch]
    game = make_game(hero=hero)
    files = {"meta.bundle": make_meta(), "game.dat": game}
    zip_bytes = sb.pack_slot_zip(files)
    back = sb.read_slot_zip(zip_bytes)

    inv_back = back["game.dat"]["hero"]["inventory"]
    assert isinstance(inv_back, list)
    assert len(inv_back) == 3, f"inventory len drift: {len(inv_back)}"

    # Item 0: Food (only int fields).
    food_back = inv_back[0]
    assert set(food_back.keys()) == set(food.keys())
    for k in food:
        assert type(food_back[k]) is type(food[k]), (
            f"food.{k}: {type(food_back[k]).__name__} != {type(food[k]).__name__}"
        )

    # Item 1: Waterskin (mixed int + float).
    ws_back = inv_back[1]
    assert set(ws_back.keys()) == set(waterskin.keys())
    for k in waterskin:
        assert type(ws_back[k]) is type(waterskin[k]), (
            f"waterskin.{k}: {type(ws_back[k]).__name__} "
            f"!= {type(waterskin[k]).__name__}"
        )
    assert ws_back["volume"] == 1.5
    assert isinstance(ws_back["volume"], float)
    assert ws_back["quickslotpos"] == 2

    # Item 2: VelvetPouch with nested seed list.
    pouch_back = inv_back[2]
    assert set(pouch_back.keys()) == set(pouch.keys())
    assert isinstance(pouch_back["inventory"], list)
    assert len(pouch_back["inventory"]) == 1
    seed_back = pouch_back["inventory"][0]
    assert set(seed_back.keys()) == set(seed.keys())
    for k in seed:
        assert type(seed_back[k]) is type(seed[k]), (
            f"seed.{k}: {type(seed_back[k]).__name__} "
            f"!= {type(seed[k]).__name__}"
        )
    assert seed_back["plantDepth"] == 5

