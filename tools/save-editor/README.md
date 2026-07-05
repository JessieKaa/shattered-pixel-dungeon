# SPD Save Slot Editor

A Vue 3 SPA for editing Shattered Pixel Dungeon fork save slots.

**Flow**: export a slot zip in-game → upload here → edit fields in browser →
download new zip → import back in-game.

## Stack

- Frontend: Vue 3 + Element Plus + Pinia + TypeScript + Vite + Monaco + vuedraggable
- Backend: Flask (parse / pack only — pure logic in `spd_bundle.py`)

## Development

Two terminals:

```bash
# Terminal A — backend (port 5001)
cd tools/save-editor
python3 -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt
PORT=5001 python app.py

# Terminal B — frontend Vite dev server (:5173, proxies /api → 5001)
cd tools/save-editor/frontend
npm install
npm run dev
```

Open <http://127.0.0.1:5173>.

## Production

```bash
cd tools/save-editor/frontend
npm install
npm run build      # produces frontend/dist/

cd ..
PORT=5001 python app.py    # serves SPA at http://127.0.0.1:5001/
```

If `frontend/dist/index.html` is missing, `GET /` returns 503 with build
instructions.

## Tests

```bash
. .venv/bin/activate

# Pure-Python suite (40 cases).
python -m pytest -q

# Force the Java cross-check (Acceptance #2):
SPD_SKIP_JAVA_CROSS_CHECK=0 python -m pytest tests/test_save_slot_io_cross_check.py -q

# Skip Java cross-check (Python-only fast run):
SPD_SKIP_JAVA_CROSS_CHECK=1 python -m pytest -q
```

The Java cross-check `tests/test_save_slot_io_cross_check.py` orchestrates a
real round-trip:

1. `spd_bundle.pack_slot_zip(...)` writes a slot zip to `/tmp/spd-cross-check-*.zip`.
2. `subprocess` runs `{REPO_ROOT}/gradlew :core:test --tests *SaveSlotIOPythonZipTest`
   with `SPD_ZIP_PATH=<zip>` in env.
3. The JUnit fixture reads `System.getenv("SPD_ZIP_PATH")`, streams the zip
   through `SaveSlotIO.readSlotFromStream`, asserts `result.ok`,
   `result.meta.version == 896`, and no `.import-*` staging leftovers.

This means the editor's output is provably importable by the fork's SaveSlotIO.

## Files

- `spd_bundle.py` — pure-logic Bundle + zip read/write (no Flask).
- `app.py` — Flask routes (`GET /`, `GET /assets/<path>`, `POST /api/parse`, `POST /api/pack`).
- `frontend/` — Vue 3 SPA (Vite project).
- `tests/test_spd_bundle.py` — 40 cases for `spd_bundle`.
- `tests/test_save_slot_io_cross_check.py` — Java cross-check.
- `tests/conftest.py` — repo-root discovery.

## Constraints honored

- `meta.version` is force-set to `896` on pack (override with
  `force_meta_version: null` in the JSON body).
- Every zip entry name must pass `^[A-Za-z0-9_.\-]+$` and not be `.`, `..`,
  or contain `/` `\` `:`. Duplicates, directory entries, and >64 entries are
  rejected on both read and pack.
- Total **uncompressed** entry bytes capped at 64 MB (streamed during read).
- `__className` is preserved through round-trip and shown as read-only in
  the form editor.
- `meta.bundle` is always written as the first entry (matches
  `SaveSlotIO.writeSlotToStream`).

## Out of scope

- Desktop GUI / APK inline editing / cloud sync.
- depth{N}.dat field editing (round-trip preserved, not exposed in UI).
- Multi-slot batch.
- Production deployment beyond Flask dev server.
