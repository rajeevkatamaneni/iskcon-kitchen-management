#!/usr/bin/env python3
"""Talk to the Kitchen Assistant from a terminal (E10-try). LOCAL DEVELOPMENT ONLY.

    tools/assistant-dev/try.sh start
    python3 tools/assistant-dev/chat.py

Each message goes through run-question.sh, the same locked-down path the T8 evals measured: a fresh
agent token from mint-token.py, an empty working directory, `env -i`, the plan's `claude -p` flags,
and the local API's /mcp as the only tools. Nothing about the call is re-implemented here, so what he
tries is what was measured.

`claude -p` keeps no session (--no-session-persistence), so the conversation is carried the way the
plan's request path does it (§2 step 2): the last turns are written in front of the new message. A
turn here is one question and its answer; the last 10 are kept. /new forgets them.

What is printed: the answer as it streams, a dim line for each tool the assistant called with what it
asked for, and a dim line after each answer with the wall-clock seconds and the notional cost the CLI
reports (informational on a subscription).
"""
import json
import os
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
LOCAL = os.path.join(HERE, ".local")
KEEP_TURNS = 10
DIM, RESET = ("\033[2m", "\033[0m") if sys.stdout.isatty() else ("", "")


def local(name):
    try:
        with open(os.path.join(LOCAL, name), encoding="utf-8") as f:
            return f.read().strip()
    except FileNotFoundError:
        sys.exit("The assistant is not set up. Run: tools/assistant-dev/try.sh start")


def prompt_with_history(history, message):
    if not history:
        return message
    lines = ["Conversation so far (oldest first):", ""]
    for q, a in history[-KEEP_TURNS:]:
        lines += [f"User: {q}", f"Assistant: {a}", ""]
    lines += ["New message from the user, to answer now:", message]
    return "\n".join(lines)


def tool_line(name, args):
    short = name.removeprefix("mcp__ikms__")
    shown = ", ".join(f"{k}={v}" for k, v in (args or {}).items())
    return f"{DIM}· {short}({shown}){RESET}"


def list_events(proc):
    """Yields each stream-json event as it arrives (a generator, so printing keeps pace)."""
    for raw in proc.stdout:
        try:
            yield json.loads(raw)
        except json.JSONDecodeError:
            continue


def ask(message, history, env):
    """Runs one question, streams the answer, returns (answer_text, result_event or None)."""
    started = time.monotonic()
    proc = subprocess.Popen([os.path.join(HERE, "run-question.sh"), prompt_with_history(history, message)],
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env,
                            text=True, encoding="utf-8", errors="replace")
    # "Assistant:" is printed with the first text, so a tool line that comes first sits on its own.
    answer, result, at_line_start, labelled = [], None, True, False
    try:
        for ev in list_events(proc):
            kind = ev.get("type")
            if kind == "stream_event":
                delta = ev.get("event", {}).get("delta", {})
                if delta.get("type") == "text_delta":
                    text = delta.get("text", "")
                    if not labelled:
                        print("Assistant: ", end="")
                        labelled = True
                    answer.append(text)
                    print(text, end="", flush=True)
                    at_line_start = text.endswith("\n")
            elif kind == "assistant":
                # The complete message arrives after its stream; tool calls are read from it, because
                # their input is only whole here.
                for block in ev.get("message", {}).get("content", []):
                    if block.get("type") == "tool_use":
                        if not at_line_start:
                            print()
                            answer.append("\n")  # keep "Let me check." apart from the answer in history
                        print(tool_line(block.get("name", "?"), block.get("input")), flush=True)
                        at_line_start = True
            elif kind == "result":
                result = ev
    except KeyboardInterrupt:
        proc.kill()  # Ctrl-C stops this answer, not the chat; the half-run claude must not linger
        raise
    proc.wait()
    seconds = time.monotonic() - started
    if not at_line_start:
        print()
    if proc.returncode or result is None or result.get("is_error"):
        err = proc.stderr.read().strip()
        detail = (result or {}).get("result") or err[-500:] or f"exit {proc.returncode}"
        print(f"{DIM}(the assistant could not answer: {detail}){RESET}")
        return None, result
    cost = result.get("total_cost_usd")
    cost_text = f"${cost:.4f}" if isinstance(cost, (int, float)) else "cost unknown"
    print(f"{DIM}{seconds:.1f}s · {cost_text} notional{RESET}")
    text = "".join(answer).strip() or (result.get("result") or "").strip()
    return text, result


def main():
    port = local("port")
    env = dict(os.environ,
               KMS_ASSISTANT_TOKEN_KEY=local("token-key"),
               KMS_DEV_USER=local("user"),
               KMS_DEV_TENANT=local("tenant"),
               MCP_URL=f"http://localhost:{port}/mcp")
    # Kannada in and out: the terminal is read and written as UTF-8 whatever the locale says.
    for stream in (sys.stdin, sys.stdout):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8", errors="replace")
    try:
        import readline  # noqa: F401  line editing and up-arrow history; optional
    except ImportError:
        pass

    print(f"Kitchen Assistant (local, port {port}). Ask in English or Kannada. /new starts over, /quit leaves.")
    history = []
    while True:
        try:
            message = input("You: ").strip()
        except EOFError:
            print()
            break
        except KeyboardInterrupt:
            print()
            continue
        if not message:
            continue
        if message in ("/quit", "/exit"):
            break
        if message == "/new":
            history.clear()
            print(f"{DIM}(new conversation){RESET}")
            continue
        try:
            answer, _ = ask(message, history, env)
        except KeyboardInterrupt:
            print(f"\n{DIM}(stopped){RESET}")
            continue
        if answer:
            history.append((message, answer))
            del history[:-KEEP_TURNS]


if __name__ == "__main__":
    main()
