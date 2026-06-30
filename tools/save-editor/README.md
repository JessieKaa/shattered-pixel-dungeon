# SPD Save Slot Editor

A minimal offline web editor for Shattered Pixel Dungeon fork save slots.

**Flow**: export a slot zip in-game → upload here → edit fields in browser →
download new zip → import back in-game.

## Setup

```bash
cd tools/save-editor
python3 -m venv .venv
. .venv/bin/activate
pip install -r requirements.txt
python app.py            # starts on 127.0.0.1:5000 by default
                        # (override with PORT=5001 if 5000 is taken)
```

Then open <http://127.0.0.1:5000> (or whichever port you chose).

## Tests

```bash
. .venv/bin/activate

# Pure-Python suite (17 logical cases + parametrized expansions).
python -m pytest -q

# Force the Java cross-check (Acceptance #8) to run even if gradle is slow:
SPD_SKIP_JAVA_CROSS_CHECK=0 python -m pytest tests/test_save_slot_io_cross_check.py -q

# Skip the Java cross-check (Python-only fast run):
SPD_SKIP_JAVA_CROSS_CHECK=1 python -m pytest -q
```

The Java cross-check `tests/test_save_slot_io_cross_check.py` orchestrates a
real round-trip:

1. `spd_bundle.pack_slot_zip(...)` writes a slot zip to `/tmp/spd-cross-check-*.zip`.
2. `subprocess` runs `{REPO_ROOT}/gradlew :core:test --tests *SaveSlotIOPythonZipTest`
   with `SPD_ZIP_PATH=<zip>` in env.
3. The JUnit fixture reads `System.getenv("SPD_ZIP_PATH")`, streams the zip
   through `SaveSlotIO.readSlotFromStream`, and asserts `result.ok`,
   `result.meta.version == 896`, plus no `.import-*` staging leftovers.

This means **the editor's output is provably importable by the fork's
SaveSlotIO**, not just by inspection.

## Files

- `spd_bundle.py` — pure-logic Bundle + zip read/write (no Flask).
- `app.py` — Flask routes (`GET /`, `POST /api/parse`, `POST /api/pack`).
- `templates/index.html` — single-page UI (vanilla JS, no build step).
- `tests/test_spd_bundle.py` — 17 logical cases for `spd_bundle`.
- `tests/test_save_slot_io_cross_check.py` — Java cross-check (Acceptance #8).
- `tests/conftest.py` — repo-root discovery.

## Constraints honored

- `meta.version` is force-set to `896` on pack (override with
  `force_meta_version: null` in the JSON body).
- Every zip entry name must pass `^[A-Za-z0-9_.\-]+$` and not be `.`, `..`,
  or contain `/` `\` `:`. Duplicates, directory entries, and >64 entries are
  rejected on both read and pack.
- Total **uncompressed** entry bytes capped at 64 MB (streamed during read,
  not trusted from central-directory file_size, mirroring `SaveSlotIO.java`).
- `__className` is preserved through round-trip but never shown in the UI.
- `meta.bundle` is always written as the first entry (matches
  `SaveSlotIO.writeSlotToStream`).
- Field whitelist: `meta.{name,depth,level,hero_class}`,
  `game.hero.{HP,HT,pos,lvl,STR,exp,attackSkill,defenseSkill}`,
  `game.{gold,challenges,seed,daily}`, plus read-only `depth{N}.dat`.

## Out of scope

- Desktop GUI / APK inline editing / cloud sync.
- Full SPD Bundle schema (the ~14 commonly-edited fields above are exposed).
- depth{N}.dat field editing (read-only display only).
- Multi-slot batch.
- Production deployment (use the Flask dev server for now).
