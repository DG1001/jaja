# jaja — Just Another Java Agent

A small agentic coding harness for **local** LLMs, with an interactive
terminal session. Java 21, no dependencies, one jar — small enough to read
through in an evening.

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

./bin/jaja ~/my-project        # interactive session
./bin/jaja --liste             # which model servers are up
```

```bash
./bin/jaja --online ~/my-project   # hosted API instead of a local server
```

`bin/jaja` probes the usual ports, asks the server what it is serving and
starts there. That exists because the harness has to default to *some* port,
and the moment two models take turns on one machine that default is wrong half
the time. `JAJA_PORTS="8000 8888"` changes where it looks; `--model <name>`
finds the port serving that particular model.

`--online` skips the probing and uses a hosted endpoint, reading the key from
`~/.deepseek-key` (`JAJA_ONLINE_URL` and `JAJA_ONLINE_MODELL` change the target).
The key goes in through the **environment**, never the command line: `--api-key`
lands in `/proc/<pid>/cmdline`, which is mode 444 and readable by every user on
the machine, while `/proc/<pid>/environ` is 400 and belongs to you alone.
`JAJA_API_KEY` therefore takes precedence over the flag. This was worth checking
rather than assuming — the first version of the launcher passed the key as an
argument, and a comment above it claimed the opposite.

The jar underneath takes plain flags, which is what scripts and benchmarks use:

```bash
java -jar target/jaja-0.1.0.jar --model deepseek-v4-flash --cwd ~/my-project

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
| `--cwd <path>` | `.` | workspace root; the file tools cannot leave it, `bash` can — see below |
| `--prompt <text>` / `--prompt-file <path>` | — | one of them required |
| `--context-window <n>` | `65536` | must match what the server was started with |
| `--max-output <n>` | `16384` | |
| `--max-turns <n>` | `60` | |
| `--timeout-minutes <n>` | `25` | per request; local models are slow |
| `--leise` | off | suppress progress output |
| `--frei` | off | session: run `bash` without asking |
| `--systemprompt <path>` | — | replace the built-in prompt entirely |
| `--kein-agent-md` | off | ignore `AGENT.md` in the workspace |
| `--abgleich` | off | before finishing, ask the model to walk the task statement point by point |
| `--karte` | off | add the `karte` tool (measured to buy nothing — see below) |
| `--index` | — | write descriptions into the map, then exit |
| `--muster <glob>` | — | with `--index`: describe only that part |

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
                            │                     (+ karte with --karte)
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
| `wire` | `Json` (a hand-rolled reader/writer), `Messages`, `ChatClient`, `Retry` |
| `tools` | the six tools, `ToolRegistry`, `Spill` (oversized output handling) |
| `ws` | `Workspace` — every path resolves through it, or not at all |
| `agent` | `Agent`, `Transcript`, `Elision`, `ContextBudget`, `TokenSchaetzer` |
| `karte` | the source map: `Scanner` (tree walk), `Karte` (store, ranking, rendering), `Indexer` (descriptions) |
| `tui` | `Terminal` (raw mode), `Eingabe` (line editor), `Anzeige`, `Sitzung`, `Markdown` |

### The six tools

`glob` · `grep` · `read` · `write` · `edit` · `bash`

A seventh, `karte`, exists but is **off by default** — see below for the 735
runs that put it there. `--karte` switches it on.

Registration order is fixed and must stay that way. The tool list sits at the
very front of every request; reorder it and the server's prefix cache misses
for the whole conversation. On local hardware prefill is the expensive part —
one measured run had a 63.5% cache hit rate, which is not something to throw
away for tidiness.

`edit` enforces a **uniqueness rule**: if the search text occurs zero or more
than once, nothing is changed and the model is told why. A silent multi-match
is the most expensive failure in this class of tool — it surfaces only at test
time, and by then the model is looking in the wrong place.

#### `bash` is the one tool the workspace does not contain

`glob`, `grep`, `read`, `write` and `edit` all resolve their paths through
`Workspace`, which normalises them and rejects anything outside the root —
including `a/../../etc/passwd` and symlinks that point out. **`bash` gets no
such boundary.** A shell can go anywhere the user can, and closing that
properly needs a container, not a path check.

This is not hypothetical. An unattended overnight run wrote a complete task
solution into an unrelated repository on the same machine, and the batch log
recorded only `bash -> 39 Zeichen` — the command itself was never written down,
so afterwards there was no way to tell which run had done it.

Two changes came out of that, neither of them a lock:

- **The batch log now carries the argument**, truncated at 200 characters, the
  same summary the session UI has always shown. A log that does not say what a
  tool did is worthless exactly when you need it.
- **A command whose paths leave the workspace produces a notice**, listing
  where it reached. System directories and `/tmp` are excluded or the notice
  drowns in `/usr/bin/env`.

It is a *heuristic for diagnosis*: it reads paths written in the command, so it
cannot see inside a script it invokes, or a path assembled from a variable.
Refusing instead of warning was considered and rejected — `pip`, `git` and
neighbouring checkouts are legitimate reasons to reach out, and a harness that
blocks them is one people disable.

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

The header names the endpoint, not just the model: `lokal :8888` in green,
`api.deepseek.com` in yellow. Both were showing `deepseek-v4-flash` and were
byte-identical, because the local server answers to the same model id as the
hosted one — and one of the two bills.
| `↑` `↓` | previous prompts |
| `/neu` | drop the transcript and start over |
| `/zeige [file]` | show a file, markdown typeset (default `NOTIZEN.md`) |
| `/karte [keyword]` | the project map, same view the model gets |
| `/index [glob]` | have the model describe the project files, optionally a part |
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

## The source map

On anything bigger than a toy project the agent spends turns just finding its
way: `glob`, then `grep`, then read three files before it opens the one that
mattered. Each of those turns costs ten seconds to minutes on local hardware.

`karte` answers that in one call, without reading a single file:

```
46 Dateien in der Karte · 44 Java, 2 Text

src/main/java/de/dg1001/harness/ws/Workspace.java  74 Zeilen
  class Workspace  class AusbruchFehler  wurzel()  aufloesen(String pfad)  … +1
  ← Agent.java  Systemprompt.java  Scanner.java  Karte.java  Sitzung.java  … +11
```

Files are ordered by **in-degree** — how many other files reference them — so
what the project is built around floats to the top. That is the poor relation
of aider's PageRank, and it answers the same question in five lines that anyone
can check. `stichwort` filters, `muster` takes a glob, `datei` gives one file
with all its references both ways. `/karte` shows the same thing to you.

**Does it pay? Less clearly than the first run suggested.** Nine runs against
this repository, three per configuration, same model (DeepSeek-V4-Flash), same
question — *where is the transcript elided when context runs short?* Every
answer was correct.

| | Turns | Tool calls | Wall clock |
|---|---|---|---|
| no map | 6.0 (5–7) | 6.7 (6–8) | 72 s (49–94) |
| map, structure only | 6.0 (6–6) | 7.0 (6–8) | 76 s (63–94) |
| map with descriptions | 4.7 (3–6) | 5.7 (4–7) | 64 s (31–84) |

Read that carefully. **The spread inside one configuration reaches three turns;
the gap between no map and the full map is 1.3.** The first single A/B run gave
6 turns against 3 and this README claimed it halved the work — that was noise
presented as a finding, and this table replaces it.

What survives three runs each: **structure alone buys nothing measurable here**,
and descriptions are modestly ahead of both — consistent with the idea that
*what is this file for* is the question a map should answer, but not
established by n=3.

One honest caveat in the other direction: 48 files is a small repository, which
is precisely where a model can afford to glob and read its way around. So the
map was pointed at a large one.

### At scale: Django

3,050 indexed files (2,928 Python), 7,751 resolved edges between them.

| | |
|---|---|
| build from nothing | **0.80 s** |
| second call, nothing changed | **0.37 s** |
| after touching one file | one file re-read |
| store on disk | 2.4 MB |

That is the incrementality earning its keep: a project of this size stays
answerable in under half a second per call, because the walk only stats
directory entries and reads what actually moved.

**Three defects surfaced that 48 files could never have shown**, all of them
now fixed and pinned by checks:

- **False edges from the standard library.** `from collections import
  defaultdict` resolved to `django/contrib/gis/geos/collections.py`, and
  `from uuid import UUID` to `django/db/models/functions/uuid.py` — because a
  path *suffix* was enough to match. Suffix matching is right for a
  package-style name like `django.core.exceptions`, where you cannot know which
  source root it sits under. It is wrong for a bare `uuid`, which means the
  standard library or a sibling. Single-segment imports now need an exact hit
  against a sibling or the project root. Worse than the wrong edges themselves:
  they inflated the in-degree of those files, so the ranking put them forward.
- **Data files crowding out source.** `.txt`, `.json`, `.yml` and friends took
  1,149 of the 4,000 slots — test fixtures and translations that carry no
  definitions and no imports — and pushed 177 real Python files out of the map.
  Only `.md` survives among the non-code formats.
- **A cap that truncated in silence.** Hitting the 4,000-file limit produced a
  map that looked complete. It now says so: `[unvollstaendig: beim Deckel von
  4000 Dateien abgebrochen, es gibt mehr]`.

The first of those is the instructive one. It was not a crash and not a wrong
answer — the map simply asserted a relationship that does not exist, in a form
that reads exactly like the true ones next to it.

### Does it pay at scale? Measured properly: no.

375 runs — five questions whose answers sit deep in `django/db/`, three
configurations, 25 complete rounds, 125 runs per configuration. Configuration
order rotated each round so a server that slows over hours could not favour one.

| | Correct | Turns | Tool calls | Wall clock |
|---|---|---|---|---|
| no map | 125/125 | 4.77 ± 2.44 | 4.86 | 31 s |
| map, structure only | 125/125 | 4.74 ± 1.89 | 5.07 | 32 s |
| map with descriptions | 125/125 | 4.74 ± 2.30 | 4.90 | 30 s |

The differences are 0.02, 0.03 and 0.01 turns against a standard error of 0.28.
That is **t ≈ 0.1** — as close to nothing as a measurement gets. At this sample
size any real gain larger than about 12% would have shown up. None did.

**This overturns the two earlier numbers in this README.** The twelve-run A/B
reported 8.8 turns against 7.2 and called it "about a fifth less"; the
48-file experiment reported 6.0 against 4.7. Both were noise read as signal,
and both are now excluded by data with forty times the runs. I have left the
mistake visible here rather than quietly deleting it, because making it twice
in one day is the more useful lesson: **an effect smaller than the spread needs
tens of runs per cell, not two or three.**

### Why there was nothing to win

Look at the first column: **375 correct answers out of 375**, in every
configuration, in under five turns. That is a ceiling, not a result about maps.
The questions were chosen for unambiguous answers, and unambiguous turned out to
mean easy — `grep` finds `class UniqueConstraint` on the first try.

### So the experiment was rebuilt around hard questions

Six questions that a single `grep` cannot answer, because they ask about
relationships between files: the layers a `.filter().count()` passes through
before SQL exists, the base classes a new database backend must inherit, where
`db_index=True` turns into `CREATE INDEX`, how migration order is decided across
apps, whether `exclude()` becomes a JOIN or a subquery. Chain questions are
graded on how many of the expected files appear, not on one.

A pilot confirmed the headroom before committing nine hours: **9.0 turns**
without a map, against 4.7 in the first series, with a criterion fixed in
advance. Then 360 runs, 120 per configuration, 20 complete rounds.

| | Correct | Turns | Tool calls | Wall clock |
|---|---|---|---|---|
| no map | 114/120 | 10.69 ± 8.00 | 14.5 | 113 s |
| map, structure only | 114/120 | 11.85 ± 8.49 | 15.0 | 117 s |
| map with descriptions | 109/120 | 11.28 ± 8.14 | 14.7 | 116 s |

Turn differences: −1.16 and −0.59 against a standard error of ~1.05, so
**t ≈ −1.1 and −0.6**. Correctness: 4.2 percentage points against a standard
error of 3.3, **t ≈ 1.3**. Nothing here is significant, and every sign that is
non-zero points *against* the map.

**Two experiments, 735 runs, no measurable benefit at either difficulty.** On
easy questions there was nothing to win; on hard ones the map did not win it.
What can be excluded at this sample size is a navigation gain larger than about
20%. What cannot be excluded is a small effect in either direction.

One concrete number is worth more than the averages: the map costs roughly the
one tool call it takes (15.0 calls against 14.5) and returns nothing measurable
for it.

### The same series against the hosted API

Everything above ran on local hardware, where tokens are free — so the
measurement was blind to the dimension anyone paying for an API cares about.
360 runs against DeepSeek's own endpoint, six workers in parallel, same
questions, same three configurations.

| | Correct | Turns | Input tokens | Wall clock |
|---|---|---|---|---|
| no map | 115/120 | 8.18 ± 3.38 | 60,244 | 25 s |
| map, structure only | 118/120 | 7.92 ± 3.45 | **54,395** | 24 s |
| map with descriptions | 118/120 | 7.99 ± 3.85 | 57,228 | 25 s |

**Every sign now favours the map** — 3.2% fewer turns, 9.7% fewer input tokens,
2.5 points more correct answers. Locally every one of those signs was reversed.
And none of it is significant either: t = 0.88, 0.59 and −1.15.

The input-token spread is the reason (±51,500 on a mean of 60,000): a run that
takes 18 turns costs twelve times one that takes 4. Detecting a 10% difference
against that spread needs roughly **1,200 runs per cell**, not 120.

So after 1,095 runs across two days the question is still open, with local
evidence pointing gently against and hosted evidence pointing gently for. Note
these are not the same model despite the name: locally a heavily quantised GGUF,
hosted the full weights — and the hosted answers were visibly more precise,
citing methods with line numbers.

### Three things that series settled anyway

**Tokens are the sensitive measure, turns are not.** The same runs differ by
3.2% in turns and 9.7% in input tokens, because a turn saved does not save one
turn's worth of tokens — it saves every token that turn would have re-sent.
Anyone measuring this kind of technique should measure tokens.

**The prefix cache works, and now there is a number for it.** The bill came to
**$0.67** for 20.8M input and 0.89M output tokens. Working backwards from what
was actually charged gives a **87.3% cache hit rate** — external confirmation
for the two design rules that exist to protect it: never reorder the tool list,
and append tool results in call order rather than completion order. Without
those, the same series would have cost $3.16.

**Cost is not the constraint; latency is.** 20.8M tokens for 67 cents is less
than the electricity the nine-hour local series drew. And six parallel workers
turned nine hours into 38 minutes — locally the bottleneck is memory bandwidth,
which cannot be bought by the hour; hosted it is latency, which can.

### What this means for the feature

The navigation map is **not justified by evidence**. It does not hurt either,
but "harmless" is a poor reason to spend base-load tokens in every request and a
turn whenever it is used. The description pass is worse off still: twenty
minutes of local GPU for `django/db/**` and, if anything, slightly more wrong
answers.

**So it is off by default.** `--karte` switches it on, and `/karte` still shows
the same view to you at any time without the model paying for it. Turning a
feature off after building it is the cheaper half of having measured it.

What survives is the part that was never about navigation: **the shadowing
check**. It uses the same index to answer a different question — *did the agent
just define a name it also imports* — and the defect it finds cost three points
in the companion benchmark. That claim is mechanical rather than measured, and
measuring it needs a different experiment: agent-written code, not Django.

### Two things the Django runs changed

**A broad match now answers with directories, not a truncated file list.**
`migration` matches 374 of 3,050 files. Showing twenty of them ranked by
*global* in-degree meant showing Django's most central files that happen to
contain the word — not the most central migration files. That is where the map
lost its runs. Directories rank by how often their files are referenced, not by
how many there are: counting files put eight test-fixture directories above
`django/db/migrations/`, because a hundred files nobody references beat fourteen
that carry the subsystem.

```
374 Treffer fuer 'migration' — zu viele fuer eine Liste, deshalb nach Verzeichnis:

django/db/migrations/                      14  state.py  loader.py  writer.py
django/core/management/                     1  base.py
django/db/migrations/operations/            5  base.py  __init__.py  fields.py

Weiter mit muster, z. B. muster="django/db/migrations/**"
```

One more step to narrow, instead of a guess.

**Shadowed names are reported without being asked for.** This one comes straight
out of the benchmark in the companion repository: a model created
`STANDARD = Register()` as specified and then a *second* one in `__init__.py`
that shadowed the import. Everything visible worked — the CLI, its own tests —
while the path the task named explicitly stayed empty forever. Three points lost
to something a list of duplicate names shows in one line.

Getting that useful took two corrections. Module-level assignments were not
definitions at all (`STANDARD = Register()` is neither `def` nor `class`), so the
case that motivated the feature was invisible. And bare name collision is far too
weak a signal: Django has 703 names defined in more than one file and almost all
are coincidence. What counts is a file that *imports a name and then redefines
it*, which meant tracking which names each file imports rather than only which
files. Django then reports **nothing** — the right answer for a mature codebase.
The reconstructed defect reports:

```
Achtung: STANDARD wird in __init__.py und einheiten.py definiert
         — eine davon importiert die andere.
```

That second one is the **greenfield** half. On a new project the map has nothing
to orient you with; here it stops being a reading aid and becomes a check on
what the agent just wrote.

A hypothesis the runs suggest but do not establish: how well it works tracks
how *specific* the search term is. `csrf` matches 23 of 3,050 files and gave
the largest, most consistent gain (7→4 and 9→7 turns). `sql` matches 200 and
`migration` 374; both were mixed. With a broad term the map degenerates into
twenty files out of several hundred, ranked by in-degree — and the answer may
simply not be among them. That would be worth testing properly.

Not tested here: **descriptions at this scale.** Describing all of Django is
373 requests, three to six hours on this hardware — an overnight job rather than
an impossibility. The practical shape is `--index --muster 'django/db/**'`:
109 files, 21 requests, about twenty minutes for the part you actually work in,
with the rest of the map staying structural. The indexer says up front what it
is about to cost.

One caveat in the map's disfavour: Django is unusually well named.
`django/middleware/csrf.py` already tells you everything, so descriptions have
little left to add. A codebase full of `utils.py` and `helpers.py` is where they
would matter, and that is untested.

### How it is built, and what it costs

Structure comes from **regular expressions per language**, not tree-sitter.
tree-sitter would be more accurate and cover 130 languages, and it would be
this project's first real dependency. Anything dynamic slips through — runtime
imports, reflection, generated code — which is why the tool description tells
the model the map is a hint, not a guarantee.

Import strings are resolved to real project paths, which is the part that earns
its keep: `from modelle.artikel import Artikel` becomes `modelle/artikel.py`,
`import de.beispiel.hilfe.Rechner` becomes `de/beispiel/hilfe/Rechner.java`,
`'./werkzeug.js'` resolves against the importing file's directory. Imports that
resolve to nothing are third-party and drop out of the graph while staying
visible in the file's raw import list.

**Incremental, or it would be pointless.** Every call walks the tree — cheap,
directory entries only — but a file is *read* only when its size or mtime
differs from the stored entry. On this repository the first call reads 46 files,
the second reads none and answers in 38 ms. There is a check for exactly this,
because if it regressed you would only notice it as "somehow slower".

It lives in `.harness/karte.json`. `.harness` is in the skip list, so the map
does not index itself.

**It is delivered as a tool and not appended to the prompt**, deliberately. The
system prompt and the tool descriptions are the one part of the context that
elision can never touch; a map living there would cost on every single turn and
would push the run toward the context wall we spent a whole session fixing. As
a tool result it costs only when used and can be elided afterwards like anything
else.

### Descriptions: `jaja --index`

Structure says what is in a file. It does not say what the file is *for*, which
is the question you would otherwise answer by reading it. `jaja --index` has the
model write one sentence and a few keywords per file:

```
src/main/java/de/dg1001/harness/agent/ContextBudget.java  77 Zeilen
  Berechnet den nutzbaren Eingabebereich aus Kontextfenster minus
  Ausgabebudget und Reserve und löst bei Überschreitung Kürzungen aus.
```

The prompt aims at purpose rather than content — *not "contains the class
Rabatt" but "calculates volume discounts"* — because otherwise you get a
paraphrase of the definition list that is already printed next to it.

Over this repository: 47 files, 11 requests, about eleven minutes on
DeepSeek-V4-Flash. Three properties make that survivable:

- **It saves after every batch.** Anyone who starts a twenty-minute pass will
  interrupt it; a second `--index` picks up where the first stopped.
- **It starts with the files with the highest in-degree.** Stop halfway and the
  ones that matter are described.
- **A failed batch costs its files, not the run.** Server errors, unparseable
  answers, invented paths — all reported and skipped. Most of the checks in
  `ProbeIndexer` are about these, including the fenced code block that is the
  most common way a model breaks "reply with JSON only".

`/index` does the same inside a session, with `Ctrl-C` to stop it.

**Staleness is handled by design, not by discipline.** Every file stores a
content hash, and a description records which hash it was written for. When they
diverge the description is **not shown** — only `[Beschreibung veraltet]`, with
the structure still there and current. Verified on a live map: append one line
to `Elision.java` and its description disappears while its neighbour's stays.
A description that no longer matches misleads the model more actively than no
description at all; that is the one warning every write-up on this subject
repeats, so it is designed in rather than bolted on.

## Project rules: `AGENT.md`

An `AGENT.md` (or `AGENTS.md`) in the workspace root is picked up
automatically and **added to** the built-in prompt, announced on startup:

```
[harness] Systemprompt aus AGENT.md (235 Zeichen)
```

```markdown
# Projektregeln

- Alle Funktionsnamen in diesem Projekt beginnen mit dem Praefix `px_`.
- Jede Funktion braucht einen Docstring mit einer Zeile `Beispiel:`.
- Tests liegen im Unterverzeichnis `pruefungen/`, nicht im Wurzelverzeichnis.
```

All three were followed on the next run, unprompted — `px_addiere`, the
`Beispiel:` line, and the test in `pruefungen/`.

**Added, not substituted, and that is the whole design decision.** The built-in
prompt carries instructions that came out of failures — above all *act first,
do not plan the whole thing up front*, which exists because a measured run
spent an entire turn inside one thinking block and never called a tool. An
`AGENT.md` almost always carries something else: which test command applies,
which style, which directories are off limits. If the file replaced the base,
you would lose that defence silently, at the exact moment you first write a
project file — and you would find out via a run that does nothing.

`--systemprompt <file>` replaces it outright for anyone who means to.
`--kein-agent-md` ignores the project file.

The prompt sits in **every** request, in the part of the budget elision can
never touch, so a long project file costs on every turn. Past ~6,000 characters
jaja says so rather than letting it quietly eat the context.

`AGENT.md` and `NOTIZEN.md` do different jobs: rules that outlive the session
versus state that does not. The handover writes the second and never touches
the first.

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

Seven offline suites, roughly two hundred checks, plus one round trip against
a real server:

```bash
mvn test              # all offline suites, no server required
mvn test -Plive       # additionally: one real round trip to a model server
```

They are plain `main()` methods rather than JUnit — the project has no
dependencies, and rewriting a working set of checks to gain a test runner was
not a good trade. `exec-maven-plugin` runs them; each exits non-zero on
failure, which is all Maven needs.

| Suite | |
|---|---|
| `ProbeJson` | parser, writer, escapes, malformed input |
| `ProbeMessages` | the protocol details that fail silently |
| `ProbeRetry` | which errors are worth retrying |
| `ProbeTools` | all six tools, path confinement, spilling |
| `ProbeAgent` | budget, transcript, elision |
| `ProbeSchleife` | the loop and approvals, against a scripted endpoint |
| `ProbeKarte` | tree walk, import resolution, incrementality, staleness |
| `ProbeIndexer` | batching, malformed answers, resumability, abort |
| `ProbeTui` | line editor, display, approval keys, handover prompt |
| `ProbeMarkdown` | headings, lists, tables, code fences, wrapping |
| `Probe` (live) | round trip to a real server |

Three bugs that only a test caught, all invisible in normal operation:

- **A timeout that never fired.** `BashTool` read the subprocess output with
  `readAllBytes()` *before* `waitFor(timeout)` — so it blocked in the read and
  the timeout was dead code. Everything works until the first command that
  hangs, and then the run stops forever. (The class comment warned about this.
  It was written by the same person who then did it.)
- **A killed shell leaves its children running.** `Process.destroy()` signals
  the shell, not what the shell started. A `python3 app.py` that outlives its
  timeout gets reparented to init and keeps its port; the harness moves on
  without noticing. Found by looking at a session that seemed hung: a Flask
  server had held port 5000 for two and a half hours, and a `pytest` from a
  benchmark three days earlier had been burning a full core the entire time —
  through every measurement series in this README. The fix collects
  `descendants()` *before* killing the parent, because afterwards they are no
  longer descendants.
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

## Reading what it wrote

The agent produces a lot of markdown — handovers, READMEs, notes — and `cat`
shows you the source. `bin/md` typesets it instead:

```bash
./bin/md NOTIZEN.md          # pages if it does not fit on one screen
./bin/md --kein-pager FILE   # all at once
./bin/md FILE > plain.txt    # no colour, like any other tool
```

Inside a session, `/zeige` does the same without leaving it.

Paging is handled by the launcher, because getting colour through a pager
needs **both** halves and each is easy to miss on its own: the renderer only
colours when its output is a terminal (the same rule `ls` and `grep` follow),
so the pipe into the pager has to ask for colour explicitly — and `less` only
passes escape sequences through with `-R`. Add `-F` so short files do not open
a pager at all and `-X` so the text stays on screen afterwards, and it behaves
the way you would expect without any of it being your problem. `MD_PAGER`
overrides the default `less -RFX`.

Line width still comes out right behind the pipe: it is read from `/dev/tty`
rather than from standard output.

It covers headings, lists, block quotes, links, emphasis, fenced code and — the
part that earns its keep — **tables with aligned columns**, because a markdown
table in source form is almost never aligned and that is exactly when it stops
being readable. Wrapping counts visible characters only, so emphasis does not
make a line break early.

Not a spec-complete renderer: no nested emphasis, footnotes, HTML or images.
For those use `glow` or `bat`. This one costs no install and no dependency, and
it knows the terminal width it is already running in.

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
bin/jaja                 start a session, finding the model server itself
bin/md                   read a markdown file in the terminal
src/main/java/de/dg1001/harness/
  Main.java              CLI, system prompt, wiring
  wire/                  transport: Json, Messages, ChatClient, Retry
  tools/                 the six tools, registry, spilling
  ws/Workspace.java      path confinement
  agent/                 loop, transcript, elision, budget, estimator
  karte/                 source map: walk, store, ranking, descriptions
  tui/                   terminal, line editor, display, session
src/test/java/…          seven probe suites
pom.xml                  Maven; no dependencies
```

## License

MIT — see [LICENSE](LICENSE).
