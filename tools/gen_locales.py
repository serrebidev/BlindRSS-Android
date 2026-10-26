#!/usr/bin/env python3
"""Regenerate res/values-<lang>/strings.xml from the English strings.xml by
matching each English value against the desktop app's gettext catalogs
(msgid -> msgstr). This is the Android gettext bridge the research round
settled on: the desktop .po files are the translation source of truth for
every string the two apps share; Android-only strings stay English until
the desktop catalogs gain them (same documented limit as iOS).

Usage: gen_locales.py <desktop-locale-dir> [res-dir]
  desktop-locale-dir contains <lang>/LC_MESSAGES/blindrss.po per locale.
Locale dir names map to Android qualifiers via LOCALES below.
Placeholders: desktop %s/%d become %1$s/%2$s by position.
"""
import re, sys
from pathlib import Path
import xml.etree.ElementTree as ET

LOCALES = {"de": "de", "es": "es", "fr": "fr", "hi": "hi", "it": "it",
           "ja": "ja", "nl": "nl", "nl_BE": "nl-rBE", "pl": "pl",
           "pt_BR": "pt-rBR", "pt_PT": "pt-rPT", "ru": "ru", "sv": "sv",
           "zh_CN": "zh-rCN", "zh_TW": "zh-rTW"}

def parse_po(path):
    out = {}
    msgid = msgstr = None
    cur = None
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line.startswith("msgid "):
            if msgid is not None and msgstr is not None:
                out[msgid] = msgstr
            msgid = eval(line[6:]); msgstr = None; cur = "id"
        elif line.startswith("msgstr "):
            msgstr = eval(line[7:]); cur = "str"
        elif line.startswith('"'):
            if cur == "id": msgid += eval(line)
            elif cur == "str": msgstr += eval(line)
    if msgid is not None and msgstr is not None:
        out[msgid] = msgstr
    return out

def placeholders(text, translated):
    parts = re.split(r"(%\d+\$[sd])", text)
    plain = [p for p in parts if p and not re.fullmatch(r"%\d+\$[sd]", p)]
    tokens = []
    for piece in plain:
        tokens += re.findall(r"%[sd]", piece)
    result = translated
    for i, _ in enumerate(tokens):
        result = result.replace("%s", f"%{i+1}$s", 1).replace("%d", f"%{i+1}$d", 1)
    return result

def esc(text):
    return (text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("'", "\\'").replace('"', '\\"'))

def main():
    locale_dir = Path(sys.argv[1])
    res = Path(sys.argv[2] if len(sys.argv) > 2 else "app/src/main/res")
    english = [(s.get("name"), s.text or "") for s in ET.parse(res / "values" / "strings.xml").getroot().iter("string")]
    for lang, qual in LOCALES.items():
        po = locale_dir / lang / "LC_MESSAGES" / "blindrss.po"
        if not po.exists():
            print(f"{lang}: no catalog, skipped"); continue
        catalog = parse_po(po)
        rows = []
        for name, text in english:
            tr = catalog.get(text)
            if tr:
                rows.append((name, placeholders(text, tr)))
        out = res / f"values-{qual}" / "strings.xml"
        out.parent.mkdir(parents=True, exist_ok=True)
        body = "".join(f'    <string name="{n}">{esc(t)}</string>\n' for n, t in rows)
        out.write_text('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n' + body + "</resources>\n", encoding="utf-8")
        print(f"{lang} ({qual}): {len(rows)}/{len(english)} translated")

if __name__ == "__main__":
    main()
