# Session Handoff — Phase 1 Task #7 직전

> 이 문서만 있으면 새 세션이 여기서 바로 이어갈 수 있습니다.
> 저장소 연 첫 프롬프트에 아래 "세션 시작 프롬프트" 그대로 복붙.

---

## 📌 세션 시작 프롬프트 (복붙용)

```
너는 10년차 슬랙봇/스프링부트 개발자야. 팀 이름은 slackbotwithjira.

이 저장소는 맥북에서 Phase 1 Task #7 직전까지 진행된 상태야.
다음 순서로 이어줘:

1. HANDOFF.md 읽고 현재 상태 파악
2. CLAUDE.md / tasks/lessons.md / tasks/phases/phase1.md 로드
3. .env 의 POSTGRES_PASSWORD 확정 여부 확인
4. 외부 준비 2개 체크:
   - Docker Desktop 기동 여부
   - ngrok authtoken 등록 여부
5. Claude CLI 스모크 1회 (echo ping | claude -p ...)
6. Task #7 E2E 착수 (Phase 1 phase1.md Step 3 체크리스트)

남은 blocker 는 POSTGRES_PASSWORD + Docker 기동 + ngrok authtoken 세 개.
사용자는 한국어로 대화하고, CLAUDE.md 의 지침(Plan Mode / Verification Before
Done / 리스크 균형 언급) 을 따라야 해.
```

---

## 🎯 프로젝트 한 줄 요약

Slack 자연어 → Claude CLI 분류 (BUG/FEATURE/OTHER + Story Point) → Jira 이슈 자동 생성. Go 프록시가 Slack 3초 ack 보장 + Spring Boot 가 비즈니스 로직.

## 🏁 진행 상태 (2026-04-21 기준)

### 완료
- [x] **Task #1~6** Spring Boot scaffold, Docker Compose, Go bot, IssueCreateService, HMAC+AbortPolicy
- [x] **맥북 환경 이관** — JDK 17 (17.0.18), Go 1.26.1, Docker 29.4.0, ngrok 3.37.6, Claude CLI 2.1.76
- [x] **`./gradlew test` 39/39 green** (`build/test-results/test/` 에서 재확인 가능)
- [x] **Jira 사이트/프로젝트 확정** — `<JIRA_BASE_URL>` / `SLAC` (Team-managed Scrum)
- [x] **Jira API 연결 검증** (curl) — 프로젝트 조회 + 이슈 생성 둘 다 성공
- [x] **Story Points 필드 ID 확정** — `customfield_10016` (기본값)
- [x] **이슈 타입 한글 매핑 반영** — [JiraApiClientImpl.java:66-68](src/main/java/com/jirabot/slack/client/JiraApiClientImpl.java) `BUG→"버그"`, else→`"작업"`
- [x] **`.env` 값 채움** — JIRA 4개, SLACK 2개 (POSTGRES_PASSWORD 제외)

### 미완 — Task #7 E2E 연결 검증
아직 시작 안 함. 단위 테스트 + Jira API 단독 검증까지만 완료 상태.

---

## 🗺️ Task #7 E2E — 커버리지 현황

**전체 경로와 검증 상태:**

```
Slack 메시지 → Events API → ngrok → Go bot(:3000) → Spring(:8080)
→ SlackSignatureFilter → IssueCreateService(@Async)
→ ClaudeApiClient(claude -p) → JiraApiClient(WebClient)
→ Jira 이슈 생성
```

| 구간 | 검증 상태 |
|---|---|
| Jira API 호환성 (토큰/프로젝트/이슈타입/SP필드) | ✅ curl 로 검증, 이슈 2건 생성 성공 |
| 단위 로직 (mock 기반) | ✅ 39/39 green |
| Claude CLI 실제 호출 | ❌ 미검증 (스모크도 아직) |
| Spring Boot 앱 기동 | ❌ 미검증 |
| Go bot 기동 | ❌ 미검증 |
| Postgres 연결 | ❌ 미검증 (비밀번호 미설정) |
| ngrok 터널 | ❌ 미설정 |
| Slack Event Subscriptions 등록 | ❌ 미설정 |
| 전 경로 관통 (hello world) | ❌ 미검증 |

**즉 현재까지는 "E2E 직전의 전제조건(Jira + 설정) 검증" 만 완료.** 봇이 실제로 도는 건 지금부터.

---

## 🔑 핵심 결정사항 (Session 2026-04-21 확정)

### 결정 1 — 이슈 타입 한글 매핑
**배경:** Jira 사이트가 한국어로 생성돼 이슈 타입 `name` 이 `작업/버그/스토리` 로 저장됨. UI 는 사용자 언어 설정에 따라 영어로 번역 표시되지만 **저장된 name 은 한글 그대로**. Team-managed 프로젝트는 `PUT /rest/api/3/issuetype/{id}` 로 리네임 불가 (Jira 가 400 으로 거절).

**해결:** 봇 코드에서 한글로 매핑 — [JiraApiClientImpl.java:66-68](src/main/java/com/jirabot/slack/client/JiraApiClientImpl.java)
```java
// BUG → "버그", FEATURE/OTHER → "작업"
```

**리스크:** FEATURE 와 OTHER 가 둘 다 "작업" 으로 수렴됨. Story 로 분리하려면 별도 매핑 추가 필요 (Phase 1 범위 밖).

### 결정 2 — Secret Manager 설치 지연
**배경:** 원본 서버의 `setup/install.sh` 는 `~/.code-assistant.json` + GCP Secret Manager + symlinked agents/skills 조합. 맥북 repo 에는 `setup/claude/`, `setup/codex/` 가 누락되어 있고 gcloud CLI 도 미설치.

**해결:** deferred task 로 분리 — [tasks/deferred/secret-manager-setup.md](tasks/deferred/secret-manager-setup.md). Phase 1 은 `.env` 방식으로 진행.

### 결정 3 — 테스트 프로젝트는 `SLAC` 그대로 사용
사용자가 직접 만든 Scrum 프로젝트. 본인 admin. 별도 테스트 프로젝트 새로 파지 않고 이대로 Phase 1 진행.

---

## 🔐 .env 현재 상태

```bash
# Slack (원본 .env.example 값 그대로 이전 — 재발급 안 됨)
SLACK_SIGNING_SECRET=<set>
SLACK_BOT_TOKEN=<set>

# Jira (실사용 값)
JIRA_BASE_URL=<set>
JIRA_EMAIL=<set>
JIRA_API_TOKEN=<set, 192자>
JIRA_PROJECT_KEY=SLAC

# Postgres
POSTGRES_DB=jirabot
POSTGRES_USER=jirabot
POSTGRES_PASSWORD=           ← ⚠️ 비어있음. 채워야 Docker/Spring 기동 가능
```

**보안 주의:**
- `.env.example` 은 사용자가 삭제함. `.gitignore` 의 `.env` 규칙은 살아있음
- Slack 토큰은 원래 `.env.example` 에 평문으로 박혀있던 값 그대로 사용 중 — git 이력에 노출됐을 가능성 있으면 재발급 필요. 현재 개인 전용 가정이라 보류

---

## 🚧 Task #7 시작 전 남은 Blocker 3개

1. **`POSTGRES_PASSWORD` 값 결정** — `.env` 직접 편집. `jirabot-local` 같은 임의값이면 충분 (로컬 전용)
2. **Docker Desktop 기동** — `open -a "Docker Desktop"` 후 데몬 ready 대기
3. **ngrok authtoken 등록** — `ngrok config add-authtoken <토큰>` (한 번만)

---

## 🚀 Task #7 실행 순서 (예정)

`prepare/07-run-order.md` 기준 4터미널 구성:

```
Terminal 1:  docker compose up -d postgres
             docker compose logs -f postgres  # ready 확인

Terminal 2:  set -a && source .env && set +a
             ./gradlew bootRun                 # :8080

Terminal 3:  cd bot && set -a && source ../.env && set +a
             go run .                          # :3000

Terminal 4:  ngrok http 3000                   # 공개 URL 확보
             → 출력된 https://xxx.ngrok.app 를 Slack App
                Event Subscriptions Request URL 에 등록 + Verify
```

검증 체크리스트 ([phase1.md:232-240](tasks/phases/phase1.md)):
- [ ] Go Bot → Spring Boot 수신 로그
- [ ] 잘못된 Slack 서명 → 403
- [ ] 유효 자연어 입력 → Claude 분류 로그
- [ ] Jira 이슈 실제 등록
- [ ] "hello world" 전 경로 관통

---

## 🧪 언제든 돌릴 수 있는 검증 스니펫

### Jira API 건강 체크 (curl)
```bash
set -a && source .env && set +a
curl -s -u "$JIRA_EMAIL:$JIRA_API_TOKEN" \
  "$JIRA_BASE_URL/rest/api/3/project/$JIRA_PROJECT_KEY" \
  | jq '{key, name, issueTypes: (.issueTypes | map(.name))}'
# 기대: key=SLAC, issueTypes=["작업","버그","스토리","에픽","하위 작업"]
```

### Story Points 필드 ID 확인
```bash
curl -s -u "$JIRA_EMAIL:$JIRA_API_TOKEN" \
  "$JIRA_BASE_URL/rest/api/3/issue/createmeta?projectKeys=$JIRA_PROJECT_KEY&expand=projects.issuetypes.fields" \
  | jq '.projects[0].issuetypes[] | {type: .name, sp: (.fields | to_entries[]? | select(.value.name | test("[Ss]tory [Pp]oint")) | .key)}'
# 기대: 모든 타입에 sp=customfield_10016
```

### Claude CLI 스모크
```bash
echo 'ping' | claude -p --output-format json \
  --permission-mode plan --max-turns 1 --model claude-sonnet-4-5
# 기대: JSON 응답 (세션 만료 시 `claude login` 재실행)
```

### 테스트 이슈 정리 (방치된 경우)
```bash
# 이전 세션에서 생성된 검증용 이슈
curl -s -u "$JIRA_EMAIL:$JIRA_API_TOKEN" -X DELETE \
  "$JIRA_BASE_URL/rest/api/3/issue/SLAC-2"
curl -s -u "$JIRA_EMAIL:$JIRA_API_TOKEN" -X DELETE \
  "$JIRA_BASE_URL/rest/api/3/issue/SLAC-3"
```

---

## 📁 주요 파일 위치

```
slackbot/
├── HANDOFF.md                    ← 지금 이 파일
├── CLAUDE.md                     ← 프로젝트 지침 (Plan Mode, 리스크 언급, STUDY 주석)
├── .env                          ← 실제 credentials (gitignored)
├── .env.example                  ← 삭제됨 (.env 로 이관됨)
├── build.gradle                  ← 의존성
├── gradle.properties             ← 주석만 남음 (리눅스용 /tmp/jdk 제거됨)
├── docker-compose.yml            ← Postgres 16
├── setup/
│   ├── install.sh                ← GCP Secret Manager 방식 (deferred 로 분리)
│   └── config.json.template
├── tasks/
│   ├── jira-slack-bot.md         ← 전체 스펙
│   ├── lessons.md                ← L1~L4 기록됨
│   ├── phases/phase1~5.md
│   └── deferred/
│       └── secret-manager-setup.md  ← 추후 작업
├── prepare/
│   ├── README.md                 ← 체크리스트
│   ├── 01-slack-app.md           ← Slack App 세팅
│   ├── 03-jira.md                ← Jira 세팅
│   ├── 04-public-url.md          ← ngrok
│   └── 07-run-order.md           ← 4터미널 실행 순서
├── src/main/java/com/jirabot/slack/
│   ├── SlackbotServerApplication.java
│   ├── config/          ← WebClient, Security, Async, Claude/JiraProperties
│   ├── controller/      ← SlackEventController, HealthController
│   ├── service/         ← IssueCreateServiceImpl
│   ├── client/          ← Claude/JiraApiClient + DTO  (⚠️ JiraApiClientImpl:66-68 한글 매핑)
│   ├── filter/          ← SlackSignatureFilter, CachedBodyFilter
│   └── dto/
├── src/test/            ← 10 클래스 39 테스트
└── bot/                 ← Go 프록시
```

---

## 📚 lessons.md 요약 (세부는 파일 참조)

- **L1** — `@Async` fire-and-forget 에 `CallerRunsPolicy` 금지 (Slack 3초 ack 붕괴 → 중복 이슈)
- **L2** — 환경 제약은 blocker 로 보고, completed 로 닫지 말 것
- **L3** — 외부 프로세스 호출은 얇은 interface 뒤에 둔다 (ProcessRunner)
- **L4** — Jira 이슈 타입 **UI 표시명과 저장된 name 이 다를 수 있음** (사용자 언어 번역 레이어). API 관점에서는 저장된 name 이 진실. Team-managed 프로젝트는 REST API 리네임 불가 → 매핑 우회

---

## ⚠️ 알려진 함정

1. **이슈 타입 name 은 한글**: UI 에 영어로 보여도 API 한테는 한글로 보내야 함. 매핑은 [JiraApiClientImpl.java:66-68](src/main/java/com/jirabot/slack/client/JiraApiClientImpl.java) 한 곳
2. **Team-managed 프로젝트 제약**: `style=next-gen` → Company-managed 전환 불가. 이슈 타입 글로벌 리네임 API 불가
3. **Story Points 필드 ID 는 customfield_10016** 로 기본값 유지. Team-managed 에선 프로젝트마다 달라질 수 있어 **새 프로젝트 만들면 재확인 필수**
4. **Slack "봇으로 구성 안 됨" 에러**: App Home 에서 Bot User 생성 + Bot Token Scopes 1개 이상 추가해야 설치 버튼 활성화
5. **ngrok 무료 플랜**: 매번 URL 이 바뀜. Slack Event Subscriptions Request URL 도 매번 재Verify. 유료면 고정 서브도메인 가능
6. **맥북 이관 시 JDK 경로**: `gradle.properties` 의 `/tmp/jdk` 는 이미 제거됨. 재발 시 즉시 주석 처리
7. **테스트 이슈 `SLAC-2`, `SLAC-3`**: 지난 세션에서 검증용으로 생성. 정리 명령은 위 "검증 스니펫" 참조

---

## 🎯 세션 재개 첫 3 액션

1. `HANDOFF.md` 읽기 (지금 이 문서)
2. `.env` 의 `POSTGRES_PASSWORD` 채움 확인 → 안 채워져 있으면 사용자에게 값 요청
3. Docker Desktop 기동 + ngrok authtoken 확인 → Task #7 실행 순서 착수

Phase 1 Task #7 완료 시 Phase 2 로 전환.
