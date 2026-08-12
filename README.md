# jaja — Just Another Java Agent

A small agentic coding harness for **local** LLMs, with an interactive
terminal session. No dependencies, ~3,600 lines of Java 21 plus ~1,200
lines of tests, one jar.

It scores **86 / 86** on the four-task benchmark from
[local-agentic-coding-128gb](https://github.com/DG1001/local-agentic-coding-128gb)
with DeepSeek-V4-Flash on a 65,536-token context — the same score as opencode,
and 47% slower. That is the honest headline: **this is not a better harness
than the mature ones.** It exists because a specific question needed answering,
and it turned out that answering it takes surprisingly little code.

## Why it exists

While benchmarking local models, one run failed in a way that looked like a
model limitation:

```
turn_end   stopReason: "length"
           usage.output: 16384        ← exactly the output cap
           content blocks: ['thinking']
           thinking length: 60,371 characters
```

DeepSeek-V4-Flash spent an entire turn planning a greenfield task in its head,
hit the output cap mid-thought, and never emitted a tool call. The harness saw
a turn with no action, treated it as finished, and exited `0` — after 17
minutes, with an empty directory and **0 of 33** hidden tests passing.

The obvious fix is one `if`: a turn that ends at the output limit *without*
having called a tool has not finished, it has stalled. Push back and ask for
one concrete step.

That guess was worth checking, and checking it meant writing a harness. The
same model, same task, same 65,536 / 16,384 limits, now scores **33 / 33**.
It was a harness behaviour, not a model limit.

```java
if (a.finishReason() == FinishReason.LENGTH && !a.message().hatWerkzeugaufrufe()) {
    if (++entartet > MAX_ENTARTET) return steckengeblieben();
    t.add(new UserMessage(ANSTOSS));   // "stop planning, call one tool now"
    continue;
}
```

## Quick start

Requires a JDK 21+ and an **OpenAI-compatible** endpoint (vLLM, llama.cpp,
ollama, LM Studio — anything that serves `/v1/chat/completions` with tool
calling).

```bash
mvn package

# Interactive session (no --prompt):
java -jar target/jaja-0.1.0.jar --model deepseek-v4-flash --cwd ~/my-project

# One-shot, for scripts and benchmarks:
java -jar target/jaja-0.1.0.jar \
    --model deepseek-v4-flash \
    --base-url http://127.0.0.1:8888/v1 \
    --cwd ~/my-project \
    --prompt "Fix the failing test in tests/test_cart.py"
```

Maven is convenience, not necessity — there are no dependencies to resolve:

```bash
javac -d out $(find src/main/java -name '*.java')
java -cp out de.dg1001.harness.Main --model … --prompt …
```

### Options

| Flag | Default | |
|---|---|---|
| `--model <name>` | — | required |
| `--base-url <url>` | `http://127.0.0.1:8888/v1` | |
| `--api-key <key>` | `unused` | most local servers ignore it |
| `--cwd <path>` | `.` | workspace root; nothing outside it is reachable |
| `--prompt <text>` / `--prompt-file <path>` | — | one of them required |
| `--context-window <n>` | `65536` | must match what the server was started with |
| `--max-output <n>` | `16384` | |
| `--max-turns <n>` | `60` | |
| `--timeout-minutes <n>` | `25` | per request; local models are slow |
| `--leise` | off | suppress progress output |
| `--frei` | off | session: run `bash` without asking |

Exit code is `0` only on an orderly finish.

## What it does

The loop is the boring part and should stay boring: send the transcript, get a
turn back, run the tools it asks for, append the results, repeat until the
model answers without calling a tool.

```
Main ──┬──> Sitzung ──> Anzeige + Eingabe + Terminal        (interactive)
       │       │
       └───────┴──> Agent ──┬──> ChatEndpunkt ──> Retry ──> ChatClient ──> /v1
                            │                          └── Json + Messages
                            ├──> ToolRegistry ──> glob grep read write edit bash
                            │           │              └──> Workspace (confinement)
                            │           └──> Freigabe   (ask before bash)
                            ├──> Beobachter             (progress: stderr or Anzeige)
                            └──> Transcript + Elision + ContextBudget + TokenSchaetzer
```

`Beobachter` and `Freigabe` are the two seams the session needed: the loop does
not know whether its progress goes to a log or a screen, and it does not know
who — if anyone — approves a command.

| Package | |
|---|---|
| `wire` | `Json` (a 257-line reader/writer), `Messages`, `ChatClient`, `Retry` |
| `tools` | the six tools, `ToolRegistry`, `Spill` (oversized output handling) |
| `ws` | `Workspace` — every path resolves through it, or not at all |
| `agent` | `Agent`, `Transcript`, `Elision`, `ContextBudget`, `TokenSchaetzer` |
| `tui` | `Terminal` (raw mode), `Eingabe` (line editor), `Anzeige`, `Sitzung` |

### The six tools

`glob` · `grep` · `read` · `write` · `edit` · `bash`

Registration order is fixed and must stay that way. The tool list sits at the
very front of every request; reorder it and the server's prefix cache misses
for the whole conversation. On local hardware prefill is the expensive part —
one measured run had a 63.5% cache hit rate, which is not something to throw
away for tidiness.

`edit` enforces a **uniqueness rule**: if the search text occurs zero or more
than once, nothing is changed and the model is told why. A silent multi-match
is the most expensive failure in this class of tool — it surfaces only at test
time, and by then the model is looking in the wrong place.

## The session

Started without `--prompt`, jaja opens an interactive session. Deliberately
**not** a full-screen application: everything except the status line is
ordinary output, so it stays in the terminal's scrollback, and selecting and
copying still works.

```
  jaja · deepseek-v4-flash · /home/you/my-project
  bash fragt nach · /hilfe zeigt die Befehle

  › fix the failing test in tests/test_cart.py

  ⏺ glob    **/test_*.py                    3 Zeilen
  ⏺ read    tests/test_cart.py              41 Zeilen
  ⏺ edit    src/cart.py                     geaendert: src/cart.py (1 Stelle)

  bash?  .venv/bin/python -m pytest -q
    [j] ausfuehren   [n] ablehnen
    ausgefuehrt
  ⏺ bash    .venv/bin/python -m pytest -q   12 passed

  Der Fehler lag in rabatt(): bei Menge 0 wurde durch null geteilt.

  6 Zuege · 4 Werkzeugaufrufe · 8214 Token im Verlauf
  › _
```

| | |
|---|---|
| `Ctrl-C` | abort the running turn — the session and the transcript survive |
| `Ctrl-D` | quit |
| `Ctrl-F` | switch asking on or off **while a turn is running** |
| `↑` `↓` | previous prompts |
| `/neu` | drop the transcript and start over |
| `/zusammenfassen [file]` | hand the work over to a file and start fresh |
| `/speichern [name]` · `/laden [name]` | transcript to and from `.harness/sitzung-<name>.json` |
| `/frei` · `/fragen` | run `bash` unasked · ask again |
| `/verlauf` | transcript size and current token estimate |

Follow-up questions reuse the same `Transcript`, elided results included. That
is the whole difference between an agent and a script with colours.

### When the context fills up, hand the work over

Eliding only postpones the problem: eventually there is nothing left to trim
and the session ends mid-task. So when the transcript crosses the trim
threshold, jaja offers a way out instead of waiting for the wall:

```
  5 Zuege · 4 Werkzeugaufrufe · 4550 Token im Verlauf

  Der Kontext wird knapp.  4550/5168 Token (88% des nutzbaren Platzes)
  Stand als NOTIZEN.md sichern und mit frischem Verlauf weitermachen?
    [j] ja   [n] nein
    ja

  ⏺ write   NOTIZEN.md   angelegt: NOTIZEN.md (34 Zeilen, 2563 Zeichen)
  Uebergabe in NOTIZEN.md (34 Zeilen) — frischer Verlauf, 177 Token
```

**4550 tokens down to 177**, and the work survives in a file a human can read.
The next prompt starts by reading it back.

The model writes the handover, not the harness. What mattered across twenty
turns is known only to whoever took them; a mechanical dump of the transcript
would be a list of filenames without the reasons. It is asked for the goal, what
is done, what is open, the decisions someone would otherwise have to make again,
and what is still unverified. One real run produced, unprompted, *"`python` was
not found on this shell, used `python3` instead"* — exactly the kind of thing
that costs a turn to rediscover.

Two rules it follows:

- **Ask, never assume.** Discarding a transcript cannot be undone, and only the
  person at the keyboard knows whether this is a good moment.
- **Discard only after the file exists.** If the handover turn fails, the
  transcript stays. Losing both would be the one unforgivable outcome here.

`/zusammenfassen` triggers it at any time, and takes a filename — `AGENT.md`,
`PROJEKT.md`, whatever the project already uses.

**`bash` asks before it runs.** It executes whatever the model writes, with your
permissions; in a benchmark that is fine because the directory is disposable, at
a terminal it is not. A refusal is not an error — the model gets a tool result
explaining it and can pick another route. `--frei` or `/frei` turns it off.

`j` runs it, `n` refuses, and **`f` runs it and stops asking from then on**.
Every other key is ignored and the prompt stays. That is not fussiness — the first version treated anything but `j` as a
refusal, so typing your next prompt while a turn ran silently refused whatever
came up. It showed up in a live session as a `/` — the first character of
`/ende` — cancelling a command nobody meant to cancel. `Enter` means nothing
either: agreeing to start a shell should be explicit. `f` sits away from `j`
for the same reason — it disables the question permanently, and a typo should
not be able to do that.

Switching back matters as much as switching off, and a turn can run for
minutes with no prompt in sight. So **`Ctrl-F` toggles asking mid-run**: a
control character, because any printable key would land in the buffered text
for the next prompt instead. It takes effect from the next tool call — one
already waiting for an answer keeps waiting. The status line carries `· frei`
whenever asking is off, so the mode is never something you have to remember.

### Three things the terminal handling has to get right

Raw mode via `stty` is the price of not depending on JLine, and it comes with
sharp edges that are easy to get wrong and unpleasant to debug:

- **Restore the terminal, always.** A process that exits in raw mode leaves a
  terminal that no longer echoes what you type, which every user reasonably
  reads as a crash. Hence both `try`-with-resources *and* a shutdown hook.
- **Only one thread may read the keyboard.** While the agent works it runs on
  its own thread, and the main thread polls the keyboard for `Ctrl-C` and for
  approvals. If the tool thread read as well, one of them would swallow the
  other's keystroke — intermittently, and only under load.
- **`\n` does not return to column 0 in raw mode.** Every line needs `\r\n`.
  Miss it and the output walks diagonally across the screen. Do the splitting inside the
  display, not at the call sites: the help text was written as one multi-line
  string months after this rule was noted down, and marched off the right edge
  of the screen exactly as described.
- **Nothing else may write to the terminal.** The retry notice went to `stderr`
  in batch mode and, in a session, printed itself into the middle of the status
  line. It now goes through the display like everything else — which is what
  the `Beobachter` seam was for, and it was not being used yet.
- **Keystrokes during a turn are kept, not dropped.** A turn takes minutes on
  local hardware, so of course you keep typing. The text reappears at the next
  prompt, ready to edit; the newline is discarded, because sending should be a
  decision and not a timing accident.

## Design decisions that came from measurements

Not from taste. Each of these was something a real run did wrong first.

**Stalled turns get a nudge, not a shrug.** The reason this repo exists; see
above. Capped at two nudges — a model that ignores three of them is not going
to start now, and the run ends with a diagnosis instead of a timeout.

**Tool results are appended in call order, never completion order.** Tools run
concurrently on virtual threads, so completion order varies between runs. If
that leaked into the transcript, two otherwise identical runs would diverge and
the prefix cache would miss from that point on.

**Elision beats compaction.** Instead of summarising the conversation, old tool
results are replaced by one line naming the tool, its argument and its exit
code. It escalates — keep the last 6, then 3, then 1, then 0 — rather than
compacting in a loop. Claude Code failed all four benchmark tasks on this
hardware precisely by thrashing its own compaction.

**Tool *calls* need eliding too, not just their results.** A `write` carries
the whole file in its `arguments`. The result ("created, 76 lines") is elided
within a few turns; the file content sits in the transcript forever. Thirty
written files is ~10,000 tokens the first stage cannot reach — measured on a
session that stalled at 33k with every result already a one-liner. So there is
a second stage: drop large argument values from old calls, keep the short ones
(`pfad`, `kommando`) that say what happened. The file is on disk and can be
read again.

**Give up at the limit, not at the threshold.** The 70% mark is when trimming
*starts*; treating it as the abort criterion ended a live session with
"context exhausted" while 14,000 tokens were free. If nothing more can be
trimmed but it still fits, keep going and say so once.

**Reasoning tokens are read but never sent back.** Some servers reject the
unknown field with a 400, and the thinking block is the single largest item in
a transcript. Reading it is useful for progress output; returning it is not.

**Large tool output is spilled to a file.** Output over 8,000 characters is
cut to head + tail with the full text written to `.harness/spill/`, and the
model is told the path. It can then grep the file instead of losing the turn.

**The token estimator calibrates against the server.** It starts at 3.5
chars/token and moves toward the `prompt_tokens` the server reports. Note that
`prompt_tokens` includes the tool schemas, which are not part of the message
text — miss that and every calibration looks like an outlier and gets
discarded, leaving the estimator stuck on its initial guess forever. (Ask how
that particular sentence got written.)

## Tests

Seven offline suites, 211 checks, plus one round trip against a real server:

```bash
mvn test              # 211 checks, no server required
mvn test -Plive       # additionally: one real round trip to a model server
```

They are plain `main()` methods rather than JUnit — the project has no
dependencies and rewriting ~1,100 lines of working checks to gain a test runner
was not a good trade. `exec-maven-plugin` runs them; each exits non-zero on
failure, which is all Maven needs.

| Suite | Checks | |
|---|---|---|
| `ProbeJson` | 39 | parser, writer, escapes, malformed input |
| `ProbeMessages` | 23 | the protocol details that fail silently |
| `ProbeRetry` | 9 | which errors are worth retrying |
| `ProbeTools` | 32 | all six tools, path confinement, spilling |
| `ProbeAgent` | 32 | budget, transcript, elision |
| `ProbeSchleife` | 17 | the loop and approvals, against a scripted endpoint |
| `ProbeTui` | 59 | line editor, display, approval keys, handover prompt |
| `Probe` (live) | 1 | round trip to a real server |

Three bugs that only a test caught, all invisible in normal operation:

- **A timeout that never fired.** `BashTool` read the subprocess output with
  `readAllBytes()` *before* `waitFor(timeout)` — so it blocked in the read and
  the timeout was dead code. Everything works until the first command that
  hangs, and then the run stops forever. (The class comment warned about this.
  It was written by the same person who then did it.)
- **A glob that skips the project root.** Java's `PathMatcher` requires at
  least one directory level after a `**/` prefix, so `**/*.py` does not match
  `main.py`. Models write that pattern constantly
  and mean "all of them", so without a fallback the model silently fails to see
  the main file of a flat project.
- **An estimator that never calibrated.** See above.

`ProbeSchleife` is the one worth reading: `ChatEndpunkt` exists as an interface
so `Retry` can wrap `ChatClient`, and that same seam lets a test script any
sequence of responses — including the stalled turn that takes 17 minutes and a
90 GB model to reproduce for real.

## What this is not

- **Not production tooling.** Use [opencode](https://opencode.ai) or
  [Claude Code](https://claude.com/claude-code). They are better, faster, and
  maintained by people who work on them full time.
- **Not measured beyond one model on one benchmark.** 86/86 with
  DeepSeek-V4-Flash on four Python tasks in small repositories, one run. It
  says nothing about other models, large codebases, or ambiguous requirements.
- **No subagents, no MCP, no streaming, no diff view, no file watching.**
  `bash` asks before it runs and stays inside the workspace, but a `j` is still
  a shell command executed with your permissions. Do not point it at anything
  you would not hand to a stranger with a shell.

## A note on the German

Identifiers, comments and model-facing strings are in German
(`werkzeug` = tool, `zug` = turn, `kuerzen` = elide). The benchmark tasks were
written in German, and translating the harness now would mean publishing
something other than what produced the numbers above.

The class and package structure reads fine either way; the tables in this
README are the map.

## Layout

```
src/main/java/de/dg1001/harness/
  Main.java              CLI, system prompt, wiring
  wire/                  transport: Json, Messages, ChatClient, Retry
  tools/                 the six tools, registry, spilling
  ws/Workspace.java      path confinement
  agent/                 loop, transcript, elision, budget, estimator
  tui/                   terminal, line editor, display, session
src/test/java/…          seven probe suites
pom.xml                  Maven; no dependencies
```

## License

MIT — see [LICENSE](LICENSE).
