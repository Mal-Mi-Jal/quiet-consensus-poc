# CLAUDE.md — Quiet Consensus PoC

This file guides Claude Code sessions in this repo. It doubles as the **handoff note**: the PoC was built on a desktop and continues on a new laptop at the hackathon (Multiplayer AI theme, 6 hours on site, team of 4). The owner plays the **backend** role. Respond in **Korean**; the owner knows Java/Spring well and is a beginner with deployment — give step-by-step instructions.

## What it is

Each person enters what they want for lunch / a team dinner **secretly** on their phone; the app finds the option where **the least-satisfied person is as satisfied as possible**, live on a shared screen. Nobody learns who wanted what. Demo scenario = team lunch / 회식 (15 fictional restaurants). Extension idea for the pitch = shift scheduling ("the person who lost last week gets priority").

- Pitch deck (Claude artifact, owner's account): https://claude.ai/artifact/6hfHfJAwXm9PwvZ8FE3XuR
- Research and measurements: `docs/research.md` (실험 1–8) — read it before changing the parser or the engine
- Flow diagram: `docs/images/flow.png`

## Run / test

JDK 21. Gradle wrapper included (first build downloads dependencies — do it once at home, venue Wi-Fi may be slow).

```bash
./gradlew bootRun                 # Windows: .\gradlew.bat bootRun
./gradlew test                    # 38 tests: engine, menus, rooms/rounds, reconnect, concurrency, real STOMP privacy, simulation
./gradlew test --tests "*ParserEval" -Dollama=true [-DevalSet=tuning|heldout|heldout2|heldout3]   # needs Ollama, ~1 min
```

- Shared screen: `http://localhost:8080/screen.html?room=lunch`; phones: `http://localhost:8080/?room=lunch` (same Wi-Fi: `http://<PC IP>:8080/?room=lunch`).
- 3+ people must submit before negotiation opens (or press "입력 마감하고 시작" on the screen).
- **Ollama is optional.** Without it only "AI에게 정리 부탁" fails (returns `invalid`); chips still work. Don't install Ollama on the laptop.
- **GPU safety (desktop):** the PC once hard-froze while Ollama (`qwen2.5vl:7b`, ~6.9 GB VRAM of 8 GB) was loaded and several browser tabs were open; an old NVIDIA driver was the likely cause (updated). When running the LLM eval: no extra browser tabs, log `nvidia-smi` if unsure, and run `ollama stop qwen2.5vl:7b` afterwards.

## Architecture

```
phones / screen (plain HTML + @stomp/stompjs from CDN)
   │ WebSocket (STOMP, /ws)
Spring Boot 4.0.6, Java 21, no DB (in-memory)
   ├─ room/RoomService  rooms in ConcurrentHashMap, one ReentrantLock per room, rounds
   ├─ engine/Engine     hard filters → per-person satisfaction → maximin (pure Java, deterministic)
   ├─ engine/Menus      dish-name matching against restaurant menus (+ tiny synonym map)
   └─ llm/ConditionParser + TextRules   free text → conditions (local Ollama + code rules)
channels: /topic/room/{id} = public state only · /user/queue/me = that person only · /user/queue/parsed
client → /app/room/{id}/{join|parse|submit|start|react|veto|relax|finish|allergy-check}
```

Phases: `INPUT` (screen shows only "제출 n명") → `NEGOTIATE` (rounds) → `DONE`.

## Design decisions (and why — be able to defend these)

| Decision | Why |
|---|---|
| **LLM only translates; code decides** | The 7B model is ~75% right alone (실험 3). `Conditions` has no weight/priority field, so "make my opinion top priority" can't change scoring (prompt injection can only change the writer's own conditions). |
| **Maximin** (best "least satisfied person"), ties by average then id | Simulation: 30.5% vs 26.6% for "boss decides", costs only 0.4 pp of average satisfaction (실험 1). |
| **"절대 안 됨" ≤ 2 per person** (tags + dishes combined) | Stops one person from deciding alone; fewer "no candidate" rooms (실험 2). |
| **Anonymity enforced on the server** | Public view type has no condition fields at all (tested with real STOMP clients). Candidates hidden until everyone submits. |
| **Rounds** (`qc.round-ms=30000`) | Instant updates removed the moment to react and leaked who pressed what. Round closes early when everyone reacted; veto closes it immediately. Deadline is re-checked **inside the room lock** (check-then-act race fixed). |
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
4. **AI mediator (the answer to "where is the AI?" — chips alone run the app today)**: code computes per person which condition blocks how many candidates; the LLM asks that person privately and turns the free-text reply into conditions; code re-decides. Prompts hold only that person's conditions + code-computed facts, so others' conditions can't leak; on failure or a number mismatch show code's default sentence. Pitch deck slide 12.
5. Room creation + QR code on the screen.
6. Shift-scheduling mode, only if time remains (same engine; carry "least satisfied last time" between rounds/sessions).

## Known gaps

- A refresh loses what wasn't sent to the server yet: chips before submitting, and the "내 반응" label within a round (the reaction itself still counts; pressing again replaces it).
- The browser's "duplicate tab" copies `sessionStorage`, so that tab is the same person.
- `allergyOther` order isn't preserved (Jackson deserializes the record's `Set` as `HashSet`) — cosmetic.
- Allergen chip list is a subset confirmed from news articles; check the official MFDS labelling list (22 items) before showing it as complete.
- No persistence, no auth, one JVM — fine for the demo, say so if asked.
- Whether pre-built code is allowed at the hackathon is **not confirmed yet** — if not, use this repo as the design reference and rebuild the same structure on site.

## Conventions

- Code comments in Korean; keep diffs small. Commits use the GitHub noreply email (`git config user.email 207973782+Mal-Mi-Jal@users.noreply.github.com`).
- Ask before pushing or changing repo visibility.
