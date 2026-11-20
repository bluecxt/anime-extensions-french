#!/usr/bin/env python3
"""Associe des modules upstream (lib/ de yuzono, players de docchi) à nos implémentations.

Nos lecteurs sont dispersés : une partie est vendored dans `lib/`, le reste est écrit à la main
dans `core/.../extractors`. Ce script fait la correspondance par nom normalisé pour que les
workflows d'upstream n'ouvrent une issue que sur ce qu'on utilise réellement.

Sortie (TSV) : <module> <status> <chemin local> [hint]
  status = lib | core | absent
  hint   = correspondance approchante, à vérifier à la main (jamais comptée comme présente)

Usage : match-local-modules.py <module>... [--json]
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
LIB_DIR = ROOT / "lib"
CORE_DIR = ROOT / "core/src/main/kotlin/fr/bluecxt/core"
# Dossiers de core qui portent une implémentation de lecteur ou de métadonnées.
CORE_FOLDERS = [CORE_DIR / "extractors", CORE_DIR / "anilist", CORE_DIR / "tmdb", CORE_DIR / "tvdb"]

# Renommages connus : module upstream -> préfixe normalisé de notre fichier/dossier.
RENAMES = {
    "anilib": "anilist",
    "mega": "megacloud",
}

SUFFIXES = ("extractor", "players", "player", "lib", "scraper", "extractors")


def normalize(raw: str) -> str:
    """Nom comparable : minuscules, sans suffixe technique ni séparateur."""
    name = re.sub(r"[^a-z0-9]", "", raw.lower())
    if name in RENAMES:
        return RENAMES[name]
    for suffix in SUFFIXES:
        if name.endswith(suffix) and len(name) > len(suffix) + 2:
            name = name[: -len(suffix)]
            break
    return RENAMES.get(name, name)


def build_index() -> dict[str, str]:
    """Normalisé -> chemin local (modules vendored d'abord, puis l'implémentation core)."""
    index: dict[str, str] = {}
    if LIB_DIR.is_dir():
        for entry in sorted(LIB_DIR.iterdir()):
            if entry.is_dir():
                index.setdefault(normalize(entry.name), f"lib/{entry.name}")
    # Dossiers de core qui portent une brique identifiable (anilist, tmdb, tvdb...).
    for entry in sorted(CORE_DIR.iterdir()) if CORE_DIR.is_dir() else []:
        if entry.is_dir() and entry.name in {"anilist", "tmdb", "tvdb"}:
            index.setdefault(entry.name, str(entry.relative_to(ROOT)))
    for folder in CORE_FOLDERS:
        if not folder.is_dir():
            continue
        for entry in sorted(folder.rglob("*.kt")):
            index.setdefault(normalize(entry.stem), str(entry.relative_to(ROOT)))
    return index


def match(module: str, index: dict[str, str]) -> tuple[str, str, str]:
    """(status, chemin, hint) pour un module upstream."""
    key = normalize(module)
    if not key:
        return "absent", "", ""
    path = index.get(key)
    if path:
        return ("lib" if path.startswith("lib/") else "core"), path, ""
    # Aide à la décision uniquement : jamais considérée comme « on l'utilise ».
    candidates = [k for k in index if len(k) >= 4 and (k.startswith(key) or key.startswith(k))]
    hint = index[max(candidates, key=len)] if candidates else ""
    return "absent", "", hint


def main() -> int:
    args = [arg for arg in sys.argv[1:] if not arg.startswith("--")]
    modules = args or [line.strip() for line in sys.stdin if line.strip()]
    index = build_index()
    rows = []
    for module in modules:
        status, path, hint = match(module, index)
        rows.append({"module": module, "status": status, "path": path, "hint": hint})

    if "--json" in sys.argv:
        print(json.dumps(rows, indent=2))
        return 0

    for row in rows:
        line = f"{row['module']}\t{row['status']}\t{row['path']}"
        print(f"{line}\t{row['hint']}" if row["hint"] else line)
    used = sum(1 for row in rows if row["status"] != "absent")
    print(f"# {used}/{len(rows)} module(s) utilisé(s) ici", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
