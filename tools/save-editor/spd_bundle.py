"""SPD Bundle (libgdx Json + optional gzip) read/write + slot zip pack/unpack.

Pure logic, no Flask dependency. All exceptions are BundleError so callers
can wrap them uniformly (e.g. Flask @errorhandler returning HTTP 400).
"""

from __future__ import annotations

import gzip
import io
import json
import re
import zipfile
from typing import Any

__all__ = [
    "BundleError",
    "safe_entry_name",
    "read_bundle",
    "write_bundle",
    "read_slot_zip",
    "pack_slot_zip",
    "MAX_ENTRY_COUNT",
    "MAX_TOTAL_BYTES",
    "META_FILE",
    "SPD_VERSION_CODE",
    "SAFE_ENTRY_NAME",
]

# Mirrors SaveSlotIO.java:79 — anchored to full match.
SAFE_ENTRY_NAME = re.compile(r"^[A-Za-z0-9_.\-]+$")

# Mirrors SaveSlotIO.java:81-82 hard caps.
MAX_ENTRY_COUNT = 64
MAX_TOTAL_BYTES = 64 * 1024 * 1024  # 64 MB summed uncompressed entry bytes

META_FILE = "meta.bundle"

# Mirrors Game.versionCode (android/build.gradle:17 appVersionCode = 896).
SPD_VERSION_CODE = 896

# Mirrors SPD Bundle.writeValue: small payloads uncompressed, large ones gzip.
COMPRESS_THRESHOLD = 256

# gzip magic header used by libgdx Bundle + java.util.zip.GZIPInputStream.
_GZIP_MAGIC = b"\x1f\x8b"


class BundleError(Exception):
    """All spd_bundle failures. Message should embed the failing entry / reason."""


def safe_entry_name(name: str | None) -> bool:
    """Mirror SaveSlotIO.isSafeEntryName (java:447-456).

    Rejects: None, empty, '.', '..', any name containing '/' '\\' ':',
    anything outside [A-Za-z0-9_.-]+. Duplicate detection is the caller's
    job (use a seen set).
    """
    if name is None or not name:
        return False
    if name == "." or name == "..":
        return False
    if "/" in name or "\\" in name or ":" in name:
        return False
    return bool(SAFE_ENTRY_NAME.match(name))


def read_bundle(raw: bytes) -> dict[str, Any]:
    """Decode bytes as SPD Bundle (libgdx Json, optionally gzip-wrapped).

    Detection uses gzip magic bytes (0x1f 0x8b), not the file extension — SPD
    decides compression per-size at write time, and our editor shouldn't care.

    Raises BundleError on: empty input, pseudo gzip header, UTF-8 decode
    failure, JSON decode failure (BOM included — JSON spec forbids it, we do
    not strip), or non-dict top-level value.
    """
    if not isinstance(raw, (bytes, bytearray)):
        raise BundleError(f"read_bundle expects bytes, got {type(raw).__name__}")
    if len(raw) < 2:
        raise BundleError(f"bundle too short: {len(raw)} bytes (<2)")

    if raw[:2] == _GZIP_MAGIC:
        try:
            decoded = gzip.decompress(raw)
        except OSError as e:
            raise BundleError(f"gzip decode failed: {e}") from e
    else:
        decoded = bytes(raw)

    try:
        text = decoded.decode("utf-8")
    except UnicodeDecodeError as e:
        raise BundleError(f"utf-8 decode failed: {e}") from e

    # Reject BOM. SPD's libgdx JsonReader does not accept a leading BOM and
    # we want round-trip fidelity, so error rather than silently strip.
    if text.startswith("﻿"):
        raise BundleError("unexpected UTF-8 BOM (SPD Bundle does not use BOM)")

    try:
        data = json.loads(text)
    except (json.JSONDecodeError, ValueError) as e:
        raise BundleError(f"json decode failed: {e}") from e

    if not isinstance(data, dict):
        raise BundleError(
            f"bundle top-level must be object, got {type(data).__name__}"
        )
    return data


def write_bundle(
    data: dict[str, Any], *, compress: bool | None = None
) -> bytes:
    """Serialize dict to SPD Bundle bytes (libgdx Json, optional gzip).

    `compress=None` mirrors SPD Bundle.writeValue: payload > 256 bytes → gzip,
    else raw JSON. `compress=True/False` overrides explicitly. Output is UTF-8
    without BOM, with `separators=(',', ':')` for SPD-compatible compactness.
    """
    if not isinstance(data, dict):
        raise BundleError(
            f"write_bundle expects dict, got {type(data).__name__}"
        )
    text = json.dumps(data, separators=(",", ":"), ensure_ascii=False)
    raw_json = text.encode("utf-8")
    if compress is None:
        compress = len(raw_json) > COMPRESS_THRESHOLD
    if compress:
        return gzip.compress(raw_json)
    return raw_json


def read_slot_zip(zip_bytes: bytes) -> dict[str, dict]:
    """Parse a slot zip with full SaveSlotIO.readSlotFromStream equivalence.

    Validates: directory entries, entry name safety, duplicates, count cap,
    total uncompressed byte cap (streamed, not central-directory file_size,
    to mirror SaveSlotIO.java:185-191), and presence of meta.bundle.

    Returns {filename: parsed_dict} on success.
    """
    seen: set[str] = set()
    total_bytes = 0
    count = 0
    had_meta = False
    files: dict[str, dict] = {}

    try:
        bio = io.BytesIO(zip_bytes)
        with zipfile.ZipFile(bio, "r") as zf:
            for info in zf.infolist():
                name = info.filename
                if info.is_dir():
                    raise BundleError(f"invalid_zip_entry: directory '{name}'")
                if not safe_entry_name(name):
                    raise BundleError(f"invalid_zip_entry: '{name}'")
                if name in seen:
                    raise BundleError(f"invalid_zip_entry: duplicate '{name}'")
                seen.add(name)
                count += 1
                if count > MAX_ENTRY_COUNT:
                    raise BundleError(
                        f"too_many_entries: {count} > {MAX_ENTRY_COUNT}"
                    )

                # Stream-read so a forged file_size can't bypass the cap.
                # SaveSlotIO reads in 16K chunks; semantics here match.
                chunks: list[bytes] = []
                entry_bytes = 0
                with zf.open(info, "r") as fh:
                    while True:
                        chunk = fh.read(16 * 1024)
                        if not chunk:
                            break
                        chunks.append(chunk)
                        entry_bytes += len(chunk)
                        total_bytes += len(chunk)
                        if total_bytes > MAX_TOTAL_BYTES:
                            raise BundleError(
                                f"zip_too_large: {total_bytes} > "
                                f"{MAX_TOTAL_BYTES}"
                            )
                raw = b"".join(chunks)

                try:
                    files[name] = read_bundle(raw)
                except BundleError as e:
                    raise BundleError(
                        f"bundle parse failed for '{name}': {e}"
                    ) from e

                if name == META_FILE:
                    had_meta = True
    except zipfile.BadZipFile as e:
        raise BundleError(f"bad zip: {e}") from e

    if not had_meta:
        raise BundleError("missing_meta: no meta.bundle in zip")

    return files


def pack_slot_zip(
    files: dict[str, dict], *, meta_version: int | None = SPD_VERSION_CODE
) -> bytes:
    """Pack a slot zip with full SaveSlotIO.writeSlotToStream equivalence.

    Validates every constraint SaveSlotIO enforces on import so that any
    round-trip failure surfaces here instead of in the game:
      - every entry name passes safe_entry_name
      - entry count <= MAX_ENTRY_COUNT
      - total uncompressed bytes <= MAX_TOTAL_BYTES
      - meta.bundle must be present (else missing_meta)
      - meta.bundle written first, rest alphabetical (SaveSlotIO:108-112)
      - if meta_version is not None, force meta.bundle['version'] = meta_version

    Single-entry compression uses write_bundle(compress=None) — SPD reads
    by magic bytes so the compression choice is invisible to the importer.
    """
    if META_FILE not in files:
        raise BundleError(f"missing_meta: files dict lacks '{META_FILE}'")

    # Pre-validate names and total size BEFORE writing, so pack is atomic.
    names = list(files.keys())
    for n in names:
        if not safe_entry_name(n):
            raise BundleError(f"invalid_zip_entry: '{n}'")
    if len(names) > MAX_ENTRY_COUNT:
        raise BundleError(
            f"too_many_entries: {len(names)} > {MAX_ENTRY_COUNT}"
        )

    # Build serialized payloads (also enforces type-check via write_bundle).
    payloads: dict[str, bytes] = {}
    total = 0
    for n in names:
        # Force meta.version BEFORE serialization when caller requests it.
        if n == META_FILE and meta_version is not None:
            data = dict(files[n])
            data["version"] = int(meta_version)
        else:
            data = files[n]
        payload = write_bundle(data)
        payloads[n] = payload
        total += len(payload)
        if total > MAX_TOTAL_BYTES:
            raise BundleError(
                f"zip_too_large: {total} > {MAX_TOTAL_BYTES}"
            )

    # Order: meta.bundle first, rest alphabetical (matches SaveSlotIO:108-112).
    ordered = sorted(n for n in names if n != META_FILE)
    ordered.insert(0, META_FILE)

    bio = io.BytesIO()
    with zipfile.ZipFile(bio, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        for n in ordered:
            zf.writestr(n, payloads[n])
    return bio.getvalue()
