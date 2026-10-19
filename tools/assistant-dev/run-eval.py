#!/usr/bin/env python3
"""Run every question in questions.jsonl through run-question.sh and record what happened (E10-T8).
LOCAL DEVELOPMENT ONLY. Needs the same environment as run-question.sh.

For each question it keeps the raw stream-json (<out>/<id>.jsonl) and writes one summary row to
<out>/results.jsonl: tools called (in order, with their inputs), whether the expected tool was the
first one called, the tool results' text, the final answer, wall-clock and CLI durations, tokens and
cost. Judging whether the answer's figures match the tool result is left to a person reading the
row side by side, because that is the check this task exists to make honestly.

Usage: run-eval.py <out-dir>   (QUESTIONS_FILE overrides the question set; PROMPT_FILE the prompt)
"""
import json, os, subprocess, sys, time

HERE = os.path.dirname(os.path.abspath(__file__))
out = sys.argv[1]
os.makedirs(out, exist_ok=True)
rows = []
for line in open(os.environ.get("QUESTIONS_FILE") or os.path.join(HERE, "questions.jsonl"), encoding="utf-8"):
    q = json.loads(line)
    t0 = time.monotonic()
    p = subprocess.run([os.path.join(HERE, "run-question.sh"), q["q"]], capture_output=True, text=True)
    wall = time.monotonic() - t0
    open(os.path.join(out, q["id"] + ".jsonl"), "w", encoding="utf-8").write(p.stdout)
    calls, results, final, res = [], [], None, {}
    for ev in p.stdout.splitlines():
        try:
            e = json.loads(ev)
        except ValueError:
            continue
        if e.get("type") == "assistant":
            for c in e["message"]["content"]:
                if c.get("type") == "tool_use":
                    calls.append({"name": c["name"].removeprefix("mcp__ikms__"), "input": c["input"]})
        elif e.get("type") == "user":
            for c in e["message"]["content"]:
                if isinstance(c, dict) and c.get("type") == "tool_result":
                    txt = c["content"] if isinstance(c["content"], str) else "".join(x.get("text", "") for x in c["content"])
                    results.append(txt)
        elif e.get("type") == "result":
            res = e
            final = e.get("result")
    u = res.get("usage", {})
    row = {
        **q,
        "tools": calls,
        "first_tool": calls[0]["name"] if calls else None,
        "tool_ok": bool(calls) and calls[0]["name"] == q["expect"],
        "tool_results": results,
        "answer": final,
        "wall_s": round(wall, 2),
        "duration_ms": res.get("duration_ms"),
        "duration_api_ms": res.get("duration_api_ms"),
        "ttft_ms": res.get("ttft_ms"),
        "num_turns": res.get("num_turns"),
        "is_error": res.get("is_error"),
        "cost_usd": res.get("total_cost_usd"),
        "input_tokens": u.get("input_tokens"),
        "cache_creation_input_tokens": u.get("cache_creation_input_tokens"),
        "cache_read_input_tokens": u.get("cache_read_input_tokens"),
        "output_tokens": u.get("output_tokens"),
        "exit": p.returncode,
        "stderr": p.stderr[-500:],
    }
    rows.append(row)
    print(f'{q["id"]} expect={q["expect"]} got={[c["name"] for c in calls]} ok={row["tool_ok"]} {wall:.1f}s', flush=True)
with open(os.path.join(out, "results.jsonl"), "w", encoding="utf-8") as f:
    for r in rows:
        f.write(json.dumps(r, ensure_ascii=False) + "\n")
print("score", sum(r["tool_ok"] for r in rows), "/", len(rows))
