#!/usr/bin/env python3
# Convert mod translation files from the post-42.15 JSON format to the
# pre-42.15 Lua-table .txt format. PZ 42.15 dropped UI_<LANG>.txt parsing,
# pre-42.15 doesn't parse UI.json — to support both, ship both. UI.json is
# the canonical source; UI_<LANG>.txt is generated alongside at build time.
import json
import re
import sys
from pathlib import Path

LUA_IDENT = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")


def lua_escape(s: str) -> str:
    return (
        s.replace("\\", "\\\\")
        .replace('"', '\\"')
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
    )


def render(lang: str, entries: dict) -> str:
    lines = [f"{lang} = {{"]
    for key, value in entries.items():
        rendered_key = key if LUA_IDENT.match(key) else f'["{lua_escape(key)}"]'
        lines.append(f'    {rendered_key} = "{lua_escape(value)}",')
    lines.append("}")
    lines.append("")
    return "\n".join(lines)


def main(translate_root: Path) -> int:
    if not translate_root.is_dir():
        print(f"[json_to_lua] not a directory: {translate_root}", file=sys.stderr)
        return 1
    count = 0
    for lang_dir in sorted(translate_root.iterdir()):
        json_path = lang_dir / "UI.json"
        if not json_path.is_file():
            continue
        with json_path.open(encoding="utf-8") as fh:
            entries = json.load(fh)
        lang = lang_dir.name.upper()
        out_path = lang_dir / f"UI_{lang}.txt"
        out_path.write_text(render(f"UI_{lang}", entries), encoding="utf-8")
        count += 1
    print(f"[json_to_lua] wrote {count} UI_<LANG>.txt files under {translate_root}")
    return 0


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("usage: json_to_lua.py <Translate-root-folder>", file=sys.stderr)
        sys.exit(2)
    sys.exit(main(Path(sys.argv[1])))
