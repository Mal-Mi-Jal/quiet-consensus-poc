# Quiet Consensus — PoC

각자 비밀로 조건을 넣으면, 모두가 보는 화면에서 **"가장 덜 손해 보는 안"**을 실시간으로 찾는 눈치 없는 합의 엔진.
데모 상황: 팀 점심 / 회식 장소 정하기 (식당은 가상 15곳).

- 조사·테스트 결과: [docs/research.md](docs/research.md)
- 흐름도: [docs/images/flow.png](docs/images/flow.png)

## 실행

필요한 것: JDK 21. (선택) Ollama + `qwen2.5vl:7b` — 없으면 "AI에게 정리 부탁"만 실패하고 칩 입력은 그대로 된다.

```powershell
cd D:\Claude\quiet-consensus-poc
.\gradlew.bat bootRun
```

- 공용 화면: http://localhost:8080/screen.html?room=lunch
- 폰(또는 새 탭 여러 개): http://localhost:8080/?room=lunch
  - 같은 와이파이의 실제 폰: `http://<이 PC의 IP>:8080/?room=lunch`
- 3명 이상이 모두 제출하면 조율 단계가 열린다. (공용 화면의 "입력 마감하고 시작"으로 먼저 열 수도 있음)
- (선택) AI 중재자 문장을 Claude Haiku로: 실행 전에 환경변수 `ANTHROPIC_API_KEY`를 넣는다 (파일에 적지 않는다). 없으면 코드가 쓴 문장과 버튼으로 동작한다.

## 진행 방식

1. **비밀 입력** — 각자 폰에서 조건을 고른다. 공용 화면엔 "제출 n명"만.
2. **실시간 조율** — 폰마다 자기 카드가 있다. 👍/👎를 누르면 **내 카드만** 다음으로 넘어가고, 지난 카드는 블록으로 남아 다시 바꿀 수 있다. 공용 화면은 후보 블록 보드(👍 수, 가장 덜 만족한 사람 %, 👑 유력)를 2초씩 묶어 갱신한다 — 누른 순간과 화면 변화가 바로 이어지지 않게(익명).
3. **AI 중재자** — 막히면(후보 0~3곳, 또는 50%를 넘는 후보 없음) 코드가 "풀면 후보가 가장 많이 늘어나는 조건" 하나를 찾아 그 사람에게만 묻는다. AI는 문장만 쓰고 숫자는 코드가 센다. 바꾸기 전에 꼭 확인을 받는다.
4. **확정** — 전원이 같은 곳 👍 / 모두가 모든 후보에 반응 / 시간 종료(유력 후보에 의견을 안 낸 사람이 있으면 15초 한 번 연장). 규칙: 가장 덜 만족한 사람도 50% 이상인 후보 중 👍 최다, 없으면 최소 만족도 최대.

## 테스트

```powershell
.\gradlew.bat test                                   # 엔진·방·AI 중재(가짜 AI)·동시성·실제 WebSocket·시뮬레이션 (유료 호출 없음)
.\gradlew.bat test --tests "*ParserEval" -Dollama=true  # LLM 변환 평가 (Ollama 필요, 약 30초)
```

## 구조

| 위치 | 하는 일 |
|---|---|
| `engine/` | 순수 Java. 필수 조건 필터 → 사람별 만족도 → 최소 만족도 최대화, 후보 0개면 거리·예산 완화. 목록에 없는 메뉴는 식당 메뉴 이름으로 매칭(`Menus`) |
| `room/` | 방 상태(메모리), 방마다 lock, 실시간 조율(사람마다 카드, 공용 보드 2초 묶음, 마감 연장, 결정 규칙), 공개/개인 화면 데이터 |
| `mediator/` | AI 중재자. `Blockers` = 막는 조건과 늘어나는 후보 수 계산(코드), `ClaudeMediatorAi` = Haiku로 질문 문장·답 분류 (Structured Output) |
| `ws/` | STOMP 설정. 방 채널 `/topic/room/{id}`, 개인 채널 `/user/queue/me` |
| `llm/` | 평문 → 조건. LLM(로컬 Ollama)은 태그와 좋고 싫음만, 아는 메뉴 찾기·알레르기 문장 감지는 코드 규칙(`TextRules`). 알레르기는 LLM이 채우지 않고 칩으로만 받는다 |
| `static/` | 폰 화면(`index.html`), 공용 화면(`screen.html`) — 프레임워크 없는 HTML |
