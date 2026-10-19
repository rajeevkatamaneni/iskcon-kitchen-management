#!/usr/bin/env python3
"""Kannada item-name drift (E10-T8b). LOCAL DEVELOPMENT ONLY.

Reads a run-eval.py results.jsonl and, for every store item named in each answer, takes the Kannada
the model used for it: "ಕನ್ನಡ (English)" or "English (ಕನ್ನಡ)", where English is the tool's own name,
its read-aloud form (IngredientNames.readable) or the name without its bracket. Only the first
rendering per item per answer is counted, so a later case ending (ಮೊಸರಿಗೆ, "to the curd") is not
mistaken for a new spelling. Normalises whitespace, punctuation and the invisible ZWJ/ZWNJ only.

Writes one row per run (no tool results, no tokens) and prints the per-item table.
Usage: kn-drift.py <results.jsonl> <out.jsonl>
"""
import json, re, sys, unicodedata
from collections import defaultdict, OrderedDict

src, out = sys.argv[1], sys.argv[2]
KN = r"[\u0C80-\u0CFF](?:[\u0C80-\u0CFF\u200C\u200D ]|, )*[\u0C80-\u0CFF]|[\u0C80-\u0CFF]"


# Prose words the greedy capture can pick up in front of a name in a sentence
# ("ಸ್ಟೋರ್‌ನಲ್ಲಿ ಎರಡು ರೀತಿಯ ತುಪ್ಪ (Ghee)" = "in the store, two kinds of ghee (Ghee)").
# Built by reading every multi-word capture by hand; none of them is part of an item name.
PROSE = {"ಮತ್ತು", "ಸ್ಟೋರ್ನಲ್ಲಿ", "ಉಗ್ರಾಣದಲ್ಲಿ", "ಎರಡು", "ಮೂರು", "ರೀತಿಯ", "ತರದ", "ಬಗೆಯ", "ಈಗ"}


def strip_prose(s):
    w = s.split(" ")
    while len(w) > 1 and w[0] in PROSE:
        w = w[1:]
    return " ".join(w)


def norm(s):
    # whitespace and punctuation only; ZWNJ/ZWJ are invisible joiners, treated as whitespace
    s = s.replace("‌", "").replace("‍", "")
    s = re.sub(r"[\s\*\.,:;!?\-–—\"'`|]+", " ", s).strip()
    return unicodedata.normalize("NFC", s)


def names_in(results):
    names = set()
    for t in results:
        try:
            d = json.loads(t)
        except ValueError:
            continue
        for row in d.get("items", d.get("rows", [])) if isinstance(d, dict) else []:
            if isinstance(row, dict) and row.get("name"):
                names.add(row["name"])
    return names


def readable(name):
    if "," not in name:
        return name
    parts = [p.strip() for p in name.split(",")]
    if not parts[0] or any(not p or re.match(r"(for|to|as|if|with|in|on|from|per|at|by|about|without)\b", p, re.I) for p in parts[1:]):
        return name
    s = " ".join(parts[1:] + [parts[0].lower()])
    return s[0].upper() + s[1:]


rows = [json.loads(l) for l in open(src, encoding="utf-8")]
out_rows = []
for r in rows:
    items = names_in(r["tool_results"])
    ans = r["answer"] or ""
    found = OrderedDict()  # item -> list of {kn, english_alongside}
    aliases = []
    exact = {n.lower() for n in items}
    for name in sorted(items):
        aliases.append((name, name))
        for a in {readable(name), re.sub(r"\s*\([^)]*\)", "", name).strip(),
                  re.sub(r"\s*\(([^)]*)\)", r", \1", name).strip()}:
            # an alias that is another item's own name belongs to that item ("Broken rice" is
            # both an item and "Rice, broken" read aloud; "Rice" both an item and "Rice (sona masoori)")
            if a and a != name and a.lower() not in exact:
                aliases.append((a, name))
    aliases.sort(key=lambda x: (-len(x[0]), x[0] != x[1], x[0]))
    taken = []
    for kind_i in range(3):
        for alias, name in aliases:
            esc = re.escape(alias)
            kind, p = [
                ("kn_en", re.compile(r"(" + KN + r")\s*\(\s*" + esc + r"\s*\)", re.I)),
                ("en_kn", re.compile(r"(?<![\w,])" + esc + r"\s*\(\s*(" + KN + r")\s*\)", re.I)),
                ("en_only", re.compile(r"(?<![\w(,])" + esc + r"(?![\w,]|\s*\()", re.I)),
            ][kind_i]
            for m in p.finditer(ans):
                span = m.span()
                if any(a < span[1] and span[0] < b for a, b in taken):
                    continue
                taken.append(span)
                found.setdefault(name, []).append({
                    "kn": strip_prose(norm(m.group(1))) if kind != "en_only" else None,
                    "form": kind, "english_alongside": True, "_span": span})
    mentions = []
    for name, ms in found.items():
        ms.sort(key=lambda x: x["_span"][0])
        kn = [m["kn"] for m in ms if m["kn"]]
        mentions.append({"item": name, "kannada_first": kn[0] if kn else None,
                         "kannada_all": sorted(set(kn)), "english_alongside": True,
                         "forms": sorted({m["form"] for m in ms})})
    out_rows.append({"run": r["id"], "qid": r["qid"], "rep": r["rep"], "q": r["q"], "gloss": r["gloss"],
                     "tools": r["tools"], "tool_ok": r["tool_ok"], "items_in_tool_result": len(items),
                     "mentions": mentions, "answer": ans, "cost_usd": r["cost_usd"],
                     "duration_ms": r["duration_ms"], "output_tokens": r["output_tokens"],
                     "is_error": r["is_error"], "exit": r["exit"]})

with open(out, "w", encoding="utf-8") as f:
    for o in out_rows:
        f.write(json.dumps(o, ensure_ascii=False) + "\n")

agg = defaultdict(lambda: {"runs": 0, "spell": defaultdict(int), "no_kn": 0})
for o in out_rows:
    for m in o["mentions"]:
        a = agg[m["item"]]
        a["runs"] += 1
        if m["kannada_first"]:
            a["spell"][m["kannada_first"]] += 1
        else:
            a["no_kn"] += 1
multi = [k for k, a in agg.items() if len(a["spell"]) > 1]
print(f"runs {len(out_rows)} items {len(agg)} multi {len(multi)} "
      f"with_kn {sum(1 for a in agg.values() if a['spell'])} "
      f"cost {sum(o['cost_usd'] or 0 for o in out_rows):.4f} tool_ok {sum(o['tool_ok'] for o in out_rows)} "
      f"errors {sum(1 for o in out_rows if o['is_error'] or o['exit'])}")
for k, a in sorted(agg.items(), key=lambda kv: (-len(kv[1]["spell"]), -kv[1]["runs"], kv[0])):
    sp = "; ".join(f"{s} x{n}" for s, n in sorted(a["spell"].items(), key=lambda x: -x[1]))
    print(f"| {k} | {a['runs']} | {len(a['spell'])} | {sp}{' ; (English only) x'+str(a['no_kn']) if a['no_kn'] else ''} |")
