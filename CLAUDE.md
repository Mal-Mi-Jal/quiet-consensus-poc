# CLAUDE.md — Quiet Consensus PoC

Guide for Claude sessions in this repo (Claude Code in the terminal or the Claude desktop app). It is also the **handoff note** between the owner's desktop and laptop for the hackathon (theme "Multiplayer AI", 6 hours on site, team of 4; the owner plays **backend**).

**How to work with the owner:** answer in **Korean**. Comfortable with Java/Spring, beginner at deployment → give step-by-step instructions (which window, which folder, exact command, expected output). Ask before pushing, changing repo visibility, or making any paid API call. Never write an API key into a file, a commit, or the chat — and if the owner pastes one, say so once and recommend revoking it.

## Start here (status 2026-11-05)

- **Done:** realtime negotiation with per-person cards, block board on the shared screen, deadline extension, decision rule (floor 50% + most 👍; if nothing reaches the floor, most 👍 among places within 10%p of the fair top — 실험 10), **AI mediator with Claude Haiku** (measured once on real Haiku: questions 4/4, replies 10/10 — small self-written set, 실험 9). 57 tests (55 pass + 2 paid/local evals skipped by default). 25 mock shops (16–25 added 2026-11-05). Last commits: `fda6e54` (realtime + mediator), `be6020b` (OK vs SOFTEN replies + eval).
- **Next (in order):** ① deploy (Dockerfile + Render/Railway, `wss://`) ② Kakao restaurant data ③ room creation + QR ④ shift mode only if time is left. Details in the task list below.
- **Open questions for the owner:** is pre-built code allowed on site? (not confirmed — if not, this repo is the design reference and the team rebuilds it). Lunch demo vs the "회의 우선순위" alternative (deck appendix) — the team decides.
- **Pitch deck** (Claude artifact, private, owner's account): https://claude.ai/artifact/6hfHfJAwXm9PwvZ8FE3XuR — 14 slides (the owner removed 마무리): cover · 목차 · 문제 · **한 장으로 보는 흐름** · Multiplayer AI · 결정 규칙 · AI 중재자 · 데모 · LLM 평가 · 시뮬레이션 · 구조 · 6시간 계획 · 리스크 · 부록(회의 버전). The owner edits it too: re-read the live version before changing it. Slide 8 shows the 2026-11-04 demo screenshots (taken with 15 shops: 2 → 6 candidates; with 25 shops the same chips give 3 → 9).
- **Research log:** `docs/research.md` (실험 1–9 + the rounds → realtime change note). Read it before changing the parser, the engine or the mediator prompts.

### First run on a new machine (laptop)

1. Install JDK 21, Git, `gh` (`gh auth login`), IntelliJ. No Ollama or Docker needed on the laptop.
2. `gh repo clone Mal-Mi-Jal/quiet-consensus-poc` then `cd quiet-consensus-poc`
3. `git config user.email 207973782+Mal-Mi-Jal@users.noreply.github.com` (noreply author for this repo)
4. `.\gradlew.bat test` → expect `BUILD SUCCESSFUL` (first run downloads dependencies — do it at home, venue Wi-Fi may be slow).
5. `.\gradlew.bat bootRun` → open `http://localhost:8080/screen.html?room=lunch` and 3 tabs of `http://localhost:8080/?room=lunch`.

### Claude API key (AI mediator) — Windows

- The owner sets it **in their own PowerShell window** (not in the chat): `setx ANTHROPIC_API_KEY "sk-ant-..."`. It applies to windows opened afterwards.
- From a Claude session, start the server without printing the key:
  `$env:ANTHROPIC_API_KEY = [Environment]::GetEnvironmentVariable("ANTHROPIC_API_KEY","User"); .\gradlew.bat bootRun`
- Remove it: `[Environment]::SetEnvironmentVariable("ANTHROPIC_API_KEY", $null, "User")`
- Without a key the app still works: the mediator sends code-written sentences and buttons; only free-text replies are disabled.
- On a deploy host, put it in the host's secret env vars.

## Run / test

```bash
./gradlew bootRun                                     # Windows: .\gradlew.bat bootRun
./gradlew test                                        # 57 tests, free: engine, menus, realtime room, mediator (fake AI), reconnect, concurrency, real STOMP privacy, simulation
./gradlew test --tests "*MediatorEval" -Dclaude=true  # real Claude Haiku, 14 paid calls (cents), needs ANTHROPIC_API_KEY — ask first
./gradlew test --tests "*ParserEval" -Dollama=true [-DevalSet=tuning|heldout|heldout2|heldout3]   # needs local Ollama (desktop only)
```

- 3+ people must submit before negotiation opens (or press "입력 마감하고 시작" on the screen). Phones on the same Wi-Fi: `http://<PC IP>:8080/?room=lunch`.
- Settings live in `application.properties` (`qc.*`): negotiate 120 s, extend 15 s, board batch 2 s, floor 0.5, mediator-below 4, model `claude-haiku-5-5`. Override with `--args="--qc.negotiate-ms=180000"`.
- **Ollama is optional** — without it only "AI에게 정리 부탁" fails (`invalid`), chips still work.
- **Desktop GPU safety:** the desktop once hard-froze with Ollama (`qwen2.5vl:7b`, ~6.9 GB of 8 GB VRAM) loaded plus browser tabs; driver since updated. After any Ollama run: `ollama stop qwen2.5vl:7b`.

## How a session goes

1. **INPUT** — each phone enters conditions secretly (chips; free text → local LLM → chips, confirmed by the owner of the phone). Screen shows only "제출 n명".
2. **NEGOTIATE (realtime)** — each phone shows its own card in fair order; 👍/👎 moves *only that phone*; past cards stay as tappable blocks; 🚫 veto (1 per person) removes a candidate for everyone. The screen shows all candidates as fixed-position blocks (👍 count, least-satisfied %, 👑 likely winner, "n명 아직 고민 중"), sent in 2 s batches.
3. **AI mediator** (only when stuck) — one person gets a private question with code-counted numbers ("밥을 풀면 2곳 → 6곳"); answers [상관없어요] / [가능하면 피할게요] / [그대로 둘게요] or in words → "이렇게 바꿀까요?" → 적용.
4. **DONE** — everyone 👍 the same place / everyone reacted to everything / timer (one 15 s extension if someone hasn't judged the likely winner) / host presses 확정. Rule: among places where the least-satisfied person is ≥ 50%, most 👍; none → most 👍 among places within 10%p of the fair top (no 👍 there → maximin). Private allergy check card after.

## Architecture

```
phones / screen (plain HTML + @stomp/stompjs from CDN)
   │ WebSocket (STOMP, /ws?pid=<128-bit id>)
Spring Boot 4.0.6, Java 21, no DB (in-memory)
   ├─ room/RoomService     rooms in ConcurrentHashMap, one ReentrantLock per room, per-person cards, board batching, deadline
   ├─ engine/Engine        hard filters → per-person satisfaction → fair ranking → decide() (pure Java, deterministic)
   ├─ engine/Menus         dish-name matching against restaurant menus (+ tiny synonym map)
   ├─ mediator/Blockers    which "절대 안 됨" to ask about and how many candidates it adds (code)
   ├─ mediator/ClaudeMediatorAi   Claude Haiku via the official Java SDK, structured output; only words the question / classifies replies
   └─ llm/ConditionParser + TextRules   free text → conditions (local Ollama + code rules)
channels: /topic/room/{id} = public board only · /user/queue/me = that person only · /user/queue/parsed
client → /app/room/{id}/{join|parse|submit|start|react|veto|relax|mediate|finish|allergy-check}
```

## Design decisions (be able to defend these)

| Decision | Why |
|---|---|
| **LLM only translates; code decides** | The 7B parser is ~75% right alone (실험 3). `Conditions` has no weight field, so "make my opinion top priority" can't change scoring. |
| **Fair ranking = maximin**, ties by average then id | Simulation: least-satisfied 30.5% vs 26.6% for "boss decides", average costs only 0.4 pp (실험 1). |
| **Decision = floor 50% + most 👍** (`qc.fair-floor`) | Lets the group's 👍 matter without letting a majority override one person: a 👎 lowers that person's satisfaction, so the place drops below the floor. 50% is a judgment call, not measured yet. |
| **No place reaches the floor → most 👍 within 10%p of the fair top** (`Engine.FALLBACK_BAND`) | Pure maximin picked a place nobody 👍-ed (owner's 4-tab test, 실험 10). The band keeps "no big loser" — a place far below the fair top never wins on 👍. No 👍 in the band → fair top. 10%p is unmeasured. |
| **"절대 안 됨" ≤ 2 per person** | Stops one person deciding alone; fewer "no candidate" rooms (실험 2). |
| **Realtime with per-person cards** (replaced 30 s rounds, 2026-11-04) | Rounds didn't feel real-time for the theme, but instant updates let a fast group skip someone still thinking. Cards move only when that person presses (`Person.current`). The board is batched every 2 s so a tap and a screen change don't line up (anonymity — the old reason for rounds); block positions are fixed, only 👑 moves. |
| **Don't decide over someone still thinking** | At the deadline, anyone who hasn't reacted to the would-be winner gets it pushed to their phone, with one 15 s extension. |
| **Mediator: code computes, AI words** | Triggers: 0 candidates, < 4 candidates, or none above the floor. `Blockers` relaxes each "절대 안 됨" one at a time and asks only the owner of the one that adds the most (one open question per room, each item once, allergies never — not relaxable). Prompt = that item + two numbers, so others' conditions can't leak; any other number in the AI sentence → code sentence (`Facts.accepts`). AI calls run outside the room lock with a timeout. |
| **OK ≠ SOFTEN** | "밥은 괜찮아요" must delete the condition; softening it to 피함 kept lowering that person's score at rice places (owner's catch, 실험 9). Changes can only relax — the mediator never adds a new "절대 안 됨". |
| **Anonymity on the server** | Public view types have no condition fields (tested with real STOMP clients); candidates hidden until everyone submits. |
| **Reconnect = same person** (per-tab `sessionStorage` id → `/ws?pid=`) | A refresh or a sleeping phone used to create ghosts that blocked the room. The id is the key to the private channel, so it must be 128-bit random; short ids are rejected. |
| **Disconnect grace 30 s** | A disconnect can arrive after the reconnect, so sessions are tracked. After the grace: not submitted → removed; submitted → conditions stay (a dead phone must not drop an allergy) but they're no longer waited for. |
| **Small tag enum (14) + dish names by code** | "카레는 싫어" became 분식 (closed-set problem). Don't add tags; match `menus`. |
| **Allergies never from the LLM** | The model invented allergies (실험 7). 갑각류/해산물 filter by category; others can't be filtered → private "직접 확인하세요" card after DONE. Never claim allergy safety. |

## Evaluation discipline (keep it)

- Write evaluation cases **before** changing prompts/parsers and before looking at outputs (`Heldout2`, `Heldout3`, `MediatorCases`). Once you've studied a set's results it is tuning data — write a new one.
- Compare numbers only within one environment (a GPU driver update alone flipped 10 of 36 parser outputs, 실험 7).
- Record failures and reverts in `docs/research.md`, not just wins.

## Hackathon backend task list

1. **Deploy first** — Dockerfile (multi-stage: Gradle build → JRE 21), Render or Railway free tier; phones need a public `wss://` URL. Set `ANTHROPIC_API_KEY` as a host secret. Wake the free instance right before the demo.
2. **Real restaurant data** — Kakao Local keyword search around a point (key via env var). Map Kakao category paths to the 14 tags; place names as `menus` fallback. With 25 mock shops 4 people average 7.2 candidates, but the 0-candidate rate barely moved (6.6% for 4, 25.5% for 8 — 실험 10): variety of tags matters more than count.
3. **Room creation + QR code** on the shared screen.
4. Swappable parser provider (Ollama won't run on the deploy host — move `ConditionParser` to Haiku behind an interface, re-run `ParserEval` sentences on it). Until then, free-text parsing works only where Ollama runs.
5. Shift-scheduling mode, only if time is left.

## Known gaps

- Board 👍 counts are aggregate and batched, but with 3 people someone watching closely could still guess.
- One mediator question open per room; if that person ignores it, auto-finish waits until the timer.
- Floor 50% and mediator-below 4 are unmeasured judgment calls. The mediator eval is small and self-written (14 cases, one run).
- A refresh loses unsent input (chips before submitting, half-typed reply); cards, reactions and an open question are restored.
- Browser "duplicate tab" copies `sessionStorage` → same person. `allergyOther` order isn't preserved (cosmetic). Allergen chips are a subset — check the official MFDS list (22 items) before calling it complete.
- No persistence, no auth, one JVM — fine for the demo; say so if asked.

## Conventions

- Code comments in Korean; keep diffs small; commits with the noreply email above, ending with the Co-Authored-By line the session gives.
- Tests must never call a paid API (fake `MediatorAi`; `StompPrivacyTest` blanks the key). Paid evals are opt-in flags.
- When a command touches a secret, mask its output (e.g. `-replace 'sk-ant-[A-Za-z0-9_-]+','(가림)'`).
