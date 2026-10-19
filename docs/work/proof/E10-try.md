# E10-try — try the Kitchen Assistant locally from a terminal: `try.sh start|stop|status` and `chat.py`

Built in the main checkout on `agentic-mvp`. Nothing committed, pushed or deployed. No backend code changed.
Left STOPPED: API down, 8095 free, no gradle or app process, `kms-postgres` `Exited` as found, `kms_try` kept.

## The three commands

```
tools/assistant-dev/try.sh start
python3 tools/assistant-dev/chat.py
tools/assistant-dev/try.sh stop
```

## Files changed (all inside the contract)

- `tools/assistant-dev/try.sh` (new). `start [--port N]`, `stop [--drop]`, `status`.
  - start: starts `kms-postgres` only if stopped and writes `.local/started-postgres`; `CREATE DATABASE
    kms_try TEMPLATE kms` only if absent (`kms` is never written); looks up the Temple Admin (the T8
    account by email, else the first active Temple Admin) and the tenant from `kms_try`, saved to
    `.local/user`, `.local/tenant`; makes `.local/token-key` once (`openssl rand -base64 32`, mode 600)
    and reuses it; starts `./gradlew bootRun` detached with nohup, log `.local/api.log`, pid files.
  - The safety environment is read out of `tools/local-backend.sh` at run time (the `export` block
    from `JAVA_HOME` to `SENTRY_DSN=`), not copied, and the script refuses to start if
    `TRANSLATION_PROVIDER` is not `stub` or the worker is not off afterwards. Then `PORT` (8095, or the
    next free port), `DB_URL` to `kms_try`, `KMS_ASSISTANT_TOKEN_KEY` from the key file.
  - Waits for `/health` UP (gives up after 240 s, or at once if gradle dies, with the log tail).
  - Then `fill-kn-names.py --from-file results/2026-10-04-claude-kn-names.json`.
  - stop: kills the JVM listening on the port and the gradle client; stops `kms-postgres` only if
    start started it; keeps `kms_try`; `--drop` drops it.
- `tools/assistant-dev/chat.py` (new). Loop on `You: `. Each message runs `run-question.sh` (which
  mints a fresh token with `mint-token.py` and runs the locked-down `claude -p`), with the last 10
  question-and-answer pairs written in front of the new message. Streams text deltas as they arrive,
  a dim `· tool(args)` line per tool call, and a dim `Ns · $cost notional` line per answer. `/new`
  clears history, `/quit` or Ctrl-D exits, Ctrl-C stops the current answer and kills its `claude`.
  stdin/stdout reconfigured to UTF-8.
- `tools/assistant-dev/fill-kn-names.py` (changed). Added `--from-file <json>`: takes the `names` list
  a previous run saved, keeps only items still missing (matched lower-case, like the glossary key),
  applies the same Kannada-only check, and inserts through the same code. Step 4 (the insert) became
  `insert_names(names)` so both paths share it; the Claude path is otherwise unchanged.
- `.gitignore`: `tools/assistant-dev/.local/` with a comment.
- `docs/work/proof/E10-try.md`: this file.

## Commands run and what came back

First start (found a defect: the command did not return when piped):

```
$ tools/assistant-dev/try.sh start 2>&1 | tail -30
Starting kms-postgres (it was stopped; stop will stop it again).
Creating database kms_try as a copy of kms.
Starting the API on port 8095 (log: tools/assistant-dev/.local/api.log).
API up after 8s (pid 64138).
stock items with no Kannada entry: 119
from file: 119 of 119 names, problems 0
inserted 119 rows (machine_provider='claude')

Kitchen Assistant is ready on port 8095, as the Temple Admin of this temple.
```

The script itself had finished, but `| tail` hung until stop: the backgrounded subshell still held the
script's stdout. Fixed by redirecting the whole detached subshell (`( … exec nohup ./gradlew bootRun )
</dev/null >api.log 2>&1 &`). Re-checked below: `time (try.sh start | cat)` returned in 6.7 s.

Stored names are exactly T8d's:

```
db rows 119 file names 119 identical True providers {'claude'}
```

Stop, then start again (restart, and the pipe fix):

```
$ tools/assistant-dev/try.sh stop
API stopped.
Database kms_try kept (stop --drop removes it).
kms-postgres stopped (start had started it).
$ time (tools/assistant-dev/try.sh start 2>&1 | cat)
Starting kms-postgres (it was stopped; stop will stop it again).
Using existing database kms_try (kept from last time).
Starting the API on port 8095 (log: tools/assistant-dev/.local/api.log).
API up after 6s (pid 64938).
stock items with no Kannada entry: 0
nothing to fill
…
( tools/assistant-dev/try.sh start 2>&1 | cat; )  0.17s user 0.15s system 4% cpu 6.670 total
```

The chat: two English questions, then a Kannada follow-up ("how many days will it last?") that only
makes sense with history, then `/new` and the same Kannada question with no history:

```
$ printf 'what is running low?\nhow much toor dal do we have?\nಅದು ಎಷ್ಟು ದಿನ ಸಾಕು?\n/new\nಅದು ಎಷ್ಟು ದಿನ ಸಾಕು?\n/quit\n' | python3 tools/assistant-dev/chat.py
Kitchen Assistant (local, port 8095). Ask in English or Kannada. /new starts over, /quit leaves.
You: · list_low_stock()
Assistant: Five items are low:

- **Ajwain** (Spices): 0 gm on hand. Reorder level is 1000 gm.
- **Almond, chopped** (Nuts & Dry Fruits): 0 gm on hand. Reorder level is 1 gm.
- **Amla (gooseberry)** (Vegetables): 0 Kg on hand. Reorder level is 0.5 Kg.
- **Curd** (Dairy): 6 L on hand. Reorder level is 15 L. **18 L is already on order**, so it may not need buying again.
- **Mace** (Spices): 0 Kg on hand. Reorder level is 0.5 Kg.

The tool gave no days of cover for any of these. Ajwain, chopped almond, amla and mace are all at zero and have nothing on order.
4.7s · $0.0083 notional
You: · get_inventory(item_name=toor dal)
Assistant: We have 806.088 Kg of Toor Dal on hand. Saved meal plans have claimed none of it, so 806.088 Kg is available. That is well above the reorder level of 60 Kg, so it isn't low.

The tool gave no days of cover for this item.
4.9s · $0.0068 notional
You: · get_inventory(item_name=toor dal)
Assistant: ತೊಗರಿ ಬೇಳೆ (Toor Dal) ಎಷ್ಟು ದಿನ ಸಾಕು ಎಂದು ಹೇಳಲು ಆಗುವುದಿಲ್ಲ. ಈ ವಸ್ತುವಿಗೆ ಎಷ್ಟು ದಿನಗಳ ಸಾಕಾಗುವಿಕೆ (cover) ಇದೆ ಎಂಬ ಮಾಹಿತಿ ಸ್ಟಾಕ್ ವ್ಯವಸ್ಥೆಯಲ್ಲಿ ಇಲ್ಲ.

ಈಗ ಕೈಯಲ್ಲಿ 806.088 Kg ಇದೆ. ಅಷ್ಟೂ ಲಭ್ಯವಿದೆ. ಮರುಆರ್ಡರ್ ಮಟ್ಟ 60 Kg.

ನನಗೆ ಮೆನು ಅಥವಾ ಎಷ್ಟು ಜನರಿಗೆ ಅಡುಗೆ ಎಂಬುದು ಕಾಣಿಸುವುದಿಲ್ಲ. ಆದ್ದರಿಂದ ದಿನದ ಲೆಕ್ಕ ಹಾಕಲು ಆಗುವುದಿಲ್ಲ. ದಿನಕ್ಕೆ ಎಷ್ಟು Kg ಬೇಕು ಎಂದು ಹೇಳಿದರೆ, ಅದರ ಆಧಾರದ ಮೇಲೆ ಲೆಕ್ಕ ಹಾಕಲು ಸಹಾಯ ಮಾಡುತ್ತೇನೆ.
7.2s · $0.0138 notional
You: (new conversation)
You: Assistant: ಯಾವ ವಸ್ತುವಿನ ಬಗ್ಗೆ ಕೇಳುತ್ತಿದ್ದೀರಿ? "ಅದು" ಯಾವುದು ಎಂದು ನನಗೆ ಗೊತ್ತಾಗಲಿಲ್ಲ. ವಸ್ತುವಿನ ಹೆಸರು ತಿಳಿಸಿದರೆ (ಉದಾಹರಣೆಗೆ ಅಕ್ಕಿ, ತುಪ್ಪ, ತೊಗರಿ ಬೇಳೆ) ದಾಸ್ತಾನು ನೋಡಿ, ಎಷ್ಟು ದಿನಕ್ಕೆ ಸಾಕು ಎಂದು ಹೇಳುತ್ತೇನೆ.
3.7s · $0.0057 notional
You: exit 0
```

The follow-up resolved "ಅದು" (it) to toor dal from history and used the stored name ತೊಗರಿ ಬೇಳೆ, which is
the glossary row (`Toor Dal|ತೊಗರಿ ಬೇಳೆ`). After `/new` the same question gets "which item do you mean?",
so `/new` really clears history. (`You:` runs into the next line here only because stdin was a pipe; at a
terminal the typed line ends it.)

Kannada typed at a real terminal (a pty, so readline/tty input is exercised, not a pipe):

```
You: ತುಪ್ಪ ಎಷ್ಟಿದೆ?
[2m· get_inventory(item_name=ghee)[0m
Assistant: ತುಪ್ಪ (Ghee) ಅಂಗಡಿಯಲ್ಲಿ ಎರಡು ದಾಖಲೆಗಳಲ್ಲಿ ಇದೆ:

- **ತುಪ್ಪ (Ghee)**: 80424.907 ml ಇದೆ. …
- **ತುಪ್ಪ (Ghee, for finishing)**: 101504 ml ಇದೆ. …
[2m5.4s · $0.0096 notional[0m
```

Stop, start again, status, a question, stop, status:

```
$ try.sh stop
API stopped.
Database kms_try kept (stop --drop removes it).
kms-postgres stopped (start had started it).
$ try.sh start
Starting kms-postgres (it was stopped; stop will stop it again).
Using existing database kms_try (kept from last time).
API up after 6s (pid 65579).
stock items with no Kannada entry: 0
nothing to fill
$ try.sh status
API: up on port 8095
kms-postgres: running (started by try.sh)
Database kms_try: present, 119 Kannada glossary rows
Acting as user fb91ece2-9bd1-4ee7-940d-0fd5deb7867a at temple f935450b-1b7c-4b2c-a7e3-73e40c7e31e3
$ printf 'how much ghee do we have?\n' | python3 tools/assistant-dev/chat.py
You: · get_inventory(item_name=ghee)
Assistant: We have two ghee entries in the store: …
3.5s · $0.0060 notional
$ try.sh stop
API stopped.
Database kms_try kept (stop --drop removes it).
kms-postgres stopped (start had started it).
$ try.sh status
API: stopped
kms-postgres: stopped
$ lsof -nP -iTCP:8095 -sTCP:LISTEN          # nothing
$ ps -ef | grep -c "[G]radleWrapperMain\|[K]msApplication"
0
kms-postgres Exited (0) Less than a second ago
```

Also: `bash -n try.sh` clean, `py_compile` clean on both Python files, and
`git check-ignore -v tools/assistant-dev/.local/key` → `.gitignore:149:tools/assistant-dev/.local/`.

## Acceptance criteria

- start/stop/status, container state remembered: shown above; the container was stopped before, so start
  started it and stop stopped it each time.
- `kms_try` from `TEMPLATE kms`, `kms` untouched: created once, reused on the next two starts. The id
  lookup reads `kms_try`.
- Safety environment, port, DB_URL, stub translation, fixed key in gitignored `.local/`: as above.
- Names exactly T8d's, machine_provider 'claude', never overwritten: 119 identical; second start inserted 0.
- chat.py: streaming, tool lines, seconds and cost, history, Kannada, `/new`, `/quit`: transcripts above.
- Restart works: three starts in total, each after a stop.

## Not done / needs Rajeev

- Not tested: `stop --drop`; start when `kms-postgres` is already running (stop should then leave it
  running); start while something is connected to `kms` (CREATE … TEMPLATE fails; the script says so
  and stops). Backspace over Kannada characters at the prompt (macOS Python's readline is libedit) was not tested.
- Answers use Markdown (`**bold**`, tables), which a terminal shows as raw asterisks and pipes. Readable,
  but not pretty. I left it, because the prompt is written for the chat page.
- Days of cover are unknown for every item in this data, so "how many days will it last?" is always
  answered "can't say". The assistant is doing the right thing; the data just has no cover figure.
- The conversation id in the token is fresh per message (run-question.sh mints it that way), not one per
  chat. Nothing on the server uses it yet.
- No browser smoke test: this is a terminal tool, there is no screen.
