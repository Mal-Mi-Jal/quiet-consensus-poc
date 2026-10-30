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

## 테스트

```powershell
.\gradlew.bat test                                   # 엔진·방·동시성·실제 WebSocket·시뮬레이션
.\gradlew.bat test --tests "*ParserEval" -Dollama=true  # LLM 변환 평가 (Ollama 필요, 약 30초)
```

## 구조

| 위치 | 하는 일 |
|---|---|
| `engine/` | 순수 Java. 필수 조건 필터 → 사람별 만족도 → 최소 만족도 최대화, 후보 0개면 거리·예산 완화 |
| `room/` | 방 상태(메모리), 방마다 lock, 조율 이벤트는 큐에 모았다가 3초마다 반영, 공개/개인 화면 데이터 |
| `ws/` | STOMP 설정. 방 채널 `/topic/room/{id}`, 개인 채널 `/user/queue/me` |
| `llm/` | 평문 → (태그, 태도) 목록. 로컬 Ollama, enum 고정, 실패해도 서비스는 계속 |
| `static/` | 폰 화면(`index.html`), 공용 화면(`screen.html`) — 프레임워크 없는 HTML |
