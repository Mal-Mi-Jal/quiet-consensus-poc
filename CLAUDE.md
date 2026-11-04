# CLAUDE.md — Quiet Consensus PoC

This file guides Claude Code sessions in this repo. It doubles as the **handoff note**: the PoC was built on a desktop and continues on a new laptop at the hackathon (Multiplayer AI theme, 6 hours on site, team of 4). The owner plays the **backend** role. Respond in **Korean**; the owner knows Java/Spring well and is a beginner with deployment — give step-by-step instructions.

## What it is

Each person enters what they want for lunch / a team dinner **secretly** on their phone; the app finds the option where **the least-satisfied person is as satisfied as possible**, live on a shared screen. Nobody learns who wanted what. Demo scenario = team lunch / 회식 (15 fictional restaurants). Extension idea for the pitch = shift scheduling ("the person who lost last week gets priority").

- Pitch deck (Claude artifact, owner's account): https://claude.ai/artifact/6hfHfJAwXm9PwvZ8FE3XuR
- Research and measurements: `docs/research.md` (실험 1–8) — read it before changing the parser or the engine
- Flow diagram: `docs/images/flow.png`

## How a session goes (2026-11-04 version)

1. **INPUT** — each phone enters conditions secretly (chips; free text → local LLM → chips). Screen shows only "제출 n명".
2. **NEGOTIATE (realtime)** — each phone shows its own card in fair order; 👍/👎 moves *only that phone* to the next card, past cards stay as tappable blocks; 🚫 veto (1 per person) removes a candidate for everyone. The screen shows all candidates as fixed blocks (👍 count, least-satisfied %, 👑 likely winner, "n명 아직 고민 중"), updated every 2 s.
3. **AI mediator** (only when stuck) — one person gets a private question with code-counted numbers ("'밥'을 풀면 2곳 → 6곳"), answers by button or in words, confirms the change.
4. **DONE** — everyone 👍 the same place / everyone reacted to everything / timer (with one 15 s extension for people who haven't judged the likely winner) / host presses 확정. Rule: floor 50% + most 👍, else maximin. Private allergy check card after.

## Run / test

JDK 21. Gradle wrapper included (first build downloads dependencies — do it once at home, venue Wi-Fi may be slow).

```bash
./gradlew bootRun                 # Windows: .\gradlew.bat bootRun
./gradlew test                    # 54 tests (52 + Ollama/Haiku evals skipped): engine, menus, realtime room, AI mediator (fake AI), reconnect, concurrency, real STOMP privacy, simulation
./gradlew test --tests "*ParserEval" -Dollama=true [-DevalSet=tuning|heldout|heldout2|heldout3]   # needs Ollama, ~1 min
./gradlew test --tests "*MediatorEval" -Dclaude=true   # real Claude Haiku, 14 paid calls, needs ANTHROPIC_API_KEY
```

- Shared screen: `http://localhost:8080/screen.html?room=lunch`; phones: `http://localhost:8080/?room=lunch` (same Wi-Fi: `http://<PC IP>:8080/?room=lunch`).
- 3+ people must submit before negotiation opens (or press "입력 마감하고 시작" on the screen).
- **AI mediator key:** set the env var `ANTHROPIC_API_KEY` before `bootRun` (never in a file). Without it the mediator still works with code-written sentences and the buttons; only the free-text reply is disabled. Tests never call the real API (fake `MediatorAi`, and `StompPrivacyTest` blanks the key). Ask before any paid run.
- **Ollama is optional.** Without it only "AI에게 정리 부탁" fails (returns `invalid`); chips still work. Don't install Ollama on the laptop.
- **GPU safety (desktop):** the PC once hard-froze while Ollama (`qwen2.5vl:7b`, ~6.9 GB VRAM of 8 GB) was loaded and several browser tabs were open; an old NVIDIA driver was the likely cause (updated). When running the LLM eval: no extra browser tabs, log `nvidia-smi` if unsure, and run `ollama stop qwen2.5vl:7b` afterwards.

## Architecture

```
phones / screen (plain HTML + @stomp/stompjs from CDN)
   │ WebSocket (STOMP, /ws)
Spring Boot 4.0.6, Java 21, no DB (in-memory)
   ├─ room/RoomService  rooms in ConcurrentHashMap, one ReentrantLock per room, realtime per-person cards
   ├─ engine/Engine     hard filters → per-person satisfaction → maximin (pure Java, deterministic)
   ├─ engine/Menus      dish-name matching against restaurant menus (+ tiny synonym map)
   ├─ mediator/Blockers + MediatorAi (ClaudeMediatorAi = Haiku)   code finds the blocking condition, AI only words the question / reads the reply
   └─ llm/ConditionParser + TextRules   free text → conditions (local Ollama + code rules)
channels: /topic/room/{id} = public state only · /user/queue/me = that person only · /user/queue/parsed
client → /app/room/{id}/{join|parse|submit|start|react|veto|relax|mediate|finish|allergy-check}
```

Phases: `INPUT` (screen shows only "제출 n명") → `NEGOTIATE` (realtime, `qc.negotiate-ms=120000`) → `DONE`.

## Design decisions (and why — be able to defend these)

| Decision | Why |
|---|---|
| **LLM only translates; code decides** | The 7B model is ~75% right alone (실험 3). `Conditions` has no weight/priority field, so "make my opinion top priority" can't change scoring (prompt injection can only change the writer's own conditions). |
| **Maximin** (best "least satisfied person"), ties by average then id | Simulation: 30.5% vs 26.6% for "boss decides", costs only 0.4 pp of average satisfaction (실험 1). |
| **"절대 안 됨" ≤ 2 per person** (tags + dishes combined) | Stops one person from deciding alone; fewer "no candidate" rooms (실험 2). |
| **Anonymity enforced on the server** | Public view type has no condition fields at all (tested with real STOMP clients). Candidates hidden until everyone submits. |
| **Realtime with per-person cards** (replaced 30 s rounds, 2026-11-04) | Feedback: rounds didn't feel real-time for a "Multiplayer AI" theme. Reactions apply at once, but each phone's card moves only when **that person** presses (`Person.current`), so a fast group can't skip someone still thinking; past cards stay as blocks and can be flipped. The shared screen is a block **board** (positions fixed by first appearance, 👑 moves) sent in 2 s batches (`qc.board-ms`) so a tap and a screen change don't line up (anonymity — the old reason for rounds). Deadline is checked inside the room lock. |
| **Don't decide over someone still thinking** | At the deadline, if anyone hasn't reacted to the would-be winner, extend once (`qc.extend-ms=15000`) and push that candidate to their phone. Also ends when everyone 👍 the same place, or everyone has reacted to every candidate. |
| **Decision rule = fairness floor + most 👍** (`qc.fair-floor=0.5`) | Among candidates where the least-satisfied person is ≥ 50%, most 👍 wins (ties → maximin order); none qualify → maximin. A 👎 lowers that person's satisfaction, so a majority can't push past one person. |
| **AI mediator: code computes, AI words** | Trigger: 0 candidates, fewer than `qc.mediator-below=4`, or no candidate above the floor. `Blockers` relaxes each person's "절대 안 됨" one at a time and asks the owner of the one that adds the most candidates (one open question per room, each item asked once, allergies never — they aren't relaxable). The prompt holds only that item + two numbers; an AI sentence with any other number is replaced by the code sentence. Replies: buttons [상관없어요]=`ok` (drop the condition) / [가능하면 피할게요]=`soften` (→ 피함) / [그대로], or free text → `OK/SOFTEN/KEEP/UNCLEAR` + tags (enum-filtered), shown as "이렇게 바꿀까요?" and applied only on 적용; changes can only relax (no new "절대 안 됨"). OK ≠ SOFTEN matters: "밥은 괜찮아요" left as 피함 kept lowering that person's score at rice places (실험 9). AI calls run outside the room lock with a timeout. |
| **Reconnect = same person** (per-tab `sessionStorage` id → `/ws?pid=` → Principal) | A refresh or a sleeping phone used to create a ghost that blocked "everyone submitted/reacted". `sessionStorage` keeps tabs as separate people. The id doubles as the key to the private channel, so it must be 128-bit random; the server rejects short ones. |
| **Disconnect grace** (`qc.away-grace-ms=30000`) | Never remove on the first disconnect event: it can arrive after the reconnect, so people track live session ids. After the grace period: not submitted → removed; submitted → conditions stay (a dead phone must not drop an allergy), but they stop being waited for (`active`). STOMP heartbeat 10 s catches phones that vanish silently. |
| **Small tag enum (14) + dish names matched by code** | "카레는 싫어" was mapped to 분식 (closed-set problem, same as the Vision Bot non-ramen gate). Don't add tags; match `menus`. |
| **Allergies never come from the LLM** | The model invented allergies repeatedly (실험 7). 갑각류/해산물 chips filter shops by category; 땅콩/우유/… are NOT filterable (no ingredient data) → private "직접 확인하세요" card after DONE, opt-in anonymous request to the screen. Never claim the app guarantees allergy safety. |
| **Parser v5 = LLM + `TextRules`** | v4 (LLM-only dish field) 43% on unseen sentences; v5 12/12 on a new set — but that set is small and mostly dishes in our data (실험 8). |

## Evaluation discipline (keep it)

- Write new evaluation sentences **before** changing the parser and commit them first (`Heldout2`, `Heldout3`). After you've looked at a set's details it becomes tuning data — write a new one.
- Compare numbers only within one environment: a GPU driver update alone flipped 10 of 36 outputs at temperature 0 (실험 7).
- Record failures and reverts in `docs/research.md`, not just wins.

## Hackathon backend task list (suggested order)

1. **Deploy first** (Render or Railway free tier; Dockerfile not written yet — multi-stage Gradle build → JRE 21). Phones need a public URL; WebSocket over `wss://` works on both. Wake the free instance right before the demo.
2. **Real restaurant data**: Kakao Local API keyword search around a point (key via env var, never committed). Map Kakao category paths to our 14 tags; use place names as `menus` fallback. Fixes "8 people → 26% no candidate" with 15 mock shops.
3. **Swappable LLM provider**: extract an interface from `ConditionParser` (Ollama / a hosted API if the organizers give credits); re-run `ParserEval` with the new model and the same sentences.
4. ~~AI mediator~~ — **built 2026-11-04** (see the design table; `mediator/`, `RoomService.maybeMediate/mediate`). Real Haiku measured once (실험 9): questions 4/4, replies 10/10 on the small `MediatorCases` set; run `./gradlew test --tests "*MediatorEval" -Dclaude=true` (14 paid calls, needs `ANTHROPIC_API_KEY`). After changing prompts, write NEW cases first.
5. Room creation + QR code on the screen.
6. Shift-scheduling mode, only if time remains (same engine; carry "least satisfied last time" between sessions).

## Known gaps

- A refresh loses what wasn't sent to the server yet (chips before submitting, a half-typed mediator reply). Cards, reactions and an open mediator question are restored from the server.
- Board likes are shown per candidate (aggregate only, 2 s batches). With 3 people and a quiet room, someone watching the screen closely could still guess — batching lowers it, doesn't remove it.
- Only one mediator question is open per room at a time, and each (person, item) is asked once. If the asked person ignores it, the room waits on that question only until the timer ends (auto-finish on "everyone reacted to everything" is held while a question is open).
- The fairness floor (50%) and `mediator-below` (4) are judgment calls, not measured yet — re-run `SimulationReport`-style numbers before claiming they're best.
- The browser's "duplicate tab" copies `sessionStorage`, so that tab is the same person.
- `allergyOther` order isn't preserved (Jackson deserializes the record's `Set` as `HashSet`) — cosmetic.
- Allergen chip list is a subset confirmed from news articles; check the official MFDS labelling list (22 items) before showing it as complete.
- No persistence, no auth, one JVM — fine for the demo, say so if asked.
- Whether pre-built code is allowed at the hackathon is **not confirmed yet** — if not, use this repo as the design reference and rebuild the same structure on site.

## Conventions

- Code comments in Korean; keep diffs small. Commits use the GitHub noreply email (`git config user.email 207973782+Mal-Mi-Jal@users.noreply.github.com`).
- Ask before pushing or changing repo visibility.
