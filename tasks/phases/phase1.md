# Phase 1 — Slack Bot + Spring Boot + IssueCreateService

> **목표:** Slack에서 자연어로 입력 → Jira 이슈 등록까지 E2E 흐름 완성  
> **기간:** 4월 2주차 ~ 4월 3주차 (4/20~)  
> **이전 Phase:** 없음 (시작)  
> **다음 Phase:** [phase2.md](../phases/phase2.md)

---

## 개발 흐름 — 순차 + 병렬 구간 분리

```
[Step 1 — 혼자, 순차] 환경 세팅
    ↓ 완료 후
[Step 2 — Agent Team, 병렬] Go Bot / Spring 서비스 / Security 동시 구현
    ↓ 완료 후
[Step 3 — 혼자] E2E 연결 검증 ("hello world" 흐름)
```

Step 1은 Agent Team 없이 혼자 진행한다.  
API 키, Docker Compose, Spring Initializr는 각자 완료 여부 확인이 필요한 단순 작업 — 팀 조율 오버헤드가 이득보다 크다.  
Step 2부터 3명 Teammate가 독립적으로 병렬 진행한다.
구현을 진행하며 직접적으로 JIRA에 연결하지 않고 구현 후 최종 확인을 받고 연결 테스트를 진행한다.

---

## Step 1 — 환경 세팅 (혼자)

- [ ] Java 17 + Spring Boot 3.x 프로젝트 생성 (Gradle, Spring Initializr)
- [ ] Docker Compose 세팅 (PostgreSQL — Phase 2 대비 미리 띄워두기)
- [ ] Jira API 연동 확인 (토큰 발급, 이슈 조회 테스트)
- [ ] Claude API 연동 확인 (Anthropic API 키)
- [ ] Slack App 생성 및 Events API 설정
- [ ] `.env` 파일 구성 (API Key, DB 비밀번호 — 절대 커밋 금지)

> **완료 기준:** `curl localhost:8080/health` 200 응답 + Docker PostgreSQL 기동 확인

---

## Step 2 — Agent Team 병렬 구현

### Agent Team 활성화

`~/.claude/settings.json`에 추가:

```json
{
  "env": {
    "CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS": "1"
  }
}
```

### Teammate 구성 (3명)

팀원 수는 3명으로 제한한다. 각 팀원이 독립된 파일 영역을 소유하므로 충돌 없이 병렬 진행 가능하다.

---

#### Teammate 1 — `go-bot-engineer`

| 항목 | 내용 |
|------|------|
| **역할** | Go Slack Bot 구현 |
| **파일 소유** | `bot/` 디렉토리 전체 |
| **계획 승인** | 불필요 (기존 Go 강점 — 빠르게 완료 예상) |
| **의존성** | Step 1 완료 후 독립 진행 |

**작업 목록 (5개)**
1. Go 모듈 초기화 + Slack SDK 의존성 추가
2. Slack Events API 이벤트 수신 핸들러 구현
3. URL Verification challenge 응답 처리
4. `message` 이벤트 → Spring Boot HTTP 포워딩 (`POST /api/slack/event`)
5. Go Bot 단독 실행 + Slack 이벤트 수신 로컬 테스트

**Claude Code 생성 프롬프트:**
```
Spawn a teammate named go-bot-engineer with this prompt:

"Implement a Go Slack Bot in the bot/ directory.
The bot receives Slack Events API events and forwards them to
the Spring Boot server at POST http://localhost:8080/api/slack/event.

Tasks:
1. Init Go module, add slack-go/slack SDK
2. Implement Slack Events API handler (HTTP server)
3. Handle URL Verification challenge
4. Forward message events to Spring Boot via HTTP POST
5. Local smoke test: receive a Slack message and confirm forwarding

Do NOT touch anything outside bot/ directory.
API credentials are in .env — read from environment variables."
```

---

#### Teammate 2 — `spring-service-engineer`

| 항목 | 내용 |
|------|------|
| **역할** | IssueCreateService 구현 (Claude API + Jira API) |
| **파일 소유** | `src/main/java/.../service/IssueCreateService.java`, `src/main/java/.../client/` |
| **계획 승인** | **필요** — IssueCreateService 인터페이스 확정 후 구현 시작 |
| **의존성** | Step 1 완료 후 독립 진행. security-config-engineer와 인터페이스만 맞추면 됨 |

**작업 목록 (6개)**
1. `ClaudeApiClient` 구현 (WebClient, 자연어 → 버그/Feature/기타 분류 + Story Point)
2. Few-shot 프롬프트 설계 (실제 이슈 케이스 3~5개 예시 포함)
3. `JiraApiClient` 구현 (WebClient, `POST /rest/api/3/issue`)
4. `IssueCreateService` 구현 (자연어 → Claude 분류 → Jira 등록 → 결과 반환)
5. `@Async` + `CompletableFuture` 적용 (Slack 3초 제한 해결)
6. Slack Controller 엔드포인트 (`POST /api/slack/event`) 골격 작성

**Claude Code 생성 프롬프트:**
```
Spawn a teammate named spring-service-engineer with this prompt:

"Implement IssueCreateService in the Spring Boot project.
Require plan approval before writing any code — present the class structure
and interface design first.

Tasks:
1. ClaudeApiClient (WebClient): natural language → issue type classification
   (Bug/Feature/Other) + Story Point recommendation via Claude API
   Use few-shot prompting with 3-5 real examples
2. JiraApiClient (WebClient): create Jira issue via POST /rest/api/3/issue
3. IssueCreateService: wire ClaudeApiClient → JiraApiClient
4. Apply @Async + CompletableFuture for Slack 3-second timeout handling
5. POST /api/slack/event controller endpoint skeleton
   (security filter will be added by security-config-engineer)

File ownership: src/main/java/.../service/, src/main/java/.../client/
Do NOT touch SecurityConfig.java or AsyncConfig.java — those are owned by
security-config-engineer.
API credentials are in environment variables."
```

---

#### Teammate 3 — `security-config-engineer`

| 항목 | 내용 |
|------|------|
| **역할** | Spring Security Slack 서명 검증 + `@Async` 설정 |
| **파일 소유** | `src/main/java/.../config/SecurityConfig.java`, `AsyncConfig.java`, `SlackSignatureFilter.java` |
| **계획 승인** | **필요** — 필터 체인 순서 확정 후 구현 시작 |
| **의존성** | Step 1 완료 후 spring-service-engineer와 병렬 진행 가능 |

**작업 목록 (5개)**
1. `SlackSignatureFilter` 구현 (`X-Slack-Signature` HMAC-SHA256 검증)
2. Replay Attack 방지 (`X-Slack-Request-Timestamp` 5분 이내 요청만 수락)
3. Spring Security 필터 체인에 `SlackSignatureFilter` 등록 (올바른 순서 확인)
4. `AsyncConfig` 구현 (`ThreadPoolTaskExecutor` 설정, `@EnableAsync`)
5. `SecurityConfig` + `AsyncConfig` 통합 테스트 (잘못된 서명 → 403 확인)

**Claude Code 생성 프롬프트:**
```
Spawn a teammate named security-config-engineer with this prompt:

"Implement Spring Security Slack signature verification and @Async config.
Require plan approval before writing any code — draw the filter chain order first.

Tasks:
1. SlackSignatureFilter: verify X-Slack-Signature (HMAC-SHA256)
2. Replay attack prevention: reject requests where
   X-Slack-Request-Timestamp is older than 5 minutes
3. Register SlackSignatureFilter in SecurityConfig at correct position
4. AsyncConfig: ThreadPoolTaskExecutor setup, @EnableAsync
5. Smoke test: invalid signature → 403, valid signature → passes through

File ownership: SecurityConfig.java, AsyncConfig.java, SlackSignatureFilter.java
Do NOT touch IssueCreateService.java or ClaudeApiClient.java."
```

---

### 팀 시작 프롬프트 (리더에게)

환경 세팅 완료 후 Claude Code에 아래를 입력:

```
Step 1 환경 세팅이 완료됐어.
이제 Phase 1 병렬 구현을 위한 Agent Team을 만들어줘.

3명의 teammate를 생성해:
- go-bot-engineer: Go Slack Bot 구현 (bot/ 디렉토리)
- spring-service-engineer: IssueCreateService (Claude API + Jira API) — 계획 승인 필요
- security-config-engineer: Slack 서명 검증 + @Async 설정 — 계획 승인 필요

각자 담당 파일 영역 외에는 절대 건드리지 않도록 지시해줘.
go-bot-engineer는 Go 전문이라 계획 승인 없이 바로 진행해도 돼.
```

---

### 파일 소유권 경계

충돌 방지를 위해 각 teammate의 파일 소유권을 명확히 한다.

```
bot/                          → go-bot-engineer 전용
src/
  main/java/.../
    controller/               → spring-service-engineer (엔드포인트 골격)
    service/IssueCreateService.java  → spring-service-engineer
    client/                   → spring-service-engineer
    config/SecurityConfig.java       → security-config-engineer
    config/AsyncConfig.java          → security-config-engineer
    filter/SlackSignatureFilter.java → security-config-engineer
```

---

### 작업 종속성 관계

```
[환경 세팅 완료]
      │
      ├──→ go-bot-engineer (독립, 즉시 시작)
      │
      ├──→ spring-service-engineer (계획 승인 후 시작)
      │         │
      │         └── IssueCreateService 인터페이스 확정
      │                   │
      └──→ security-config-engineer (계획 승인 후 시작, spring-service-engineer와 병렬)
                          │
                          └── 필터 체인 확정 → /api/slack/event에 연결
```

---

## Step 3 — E2E 연결 검증 (혼자)

모든 teammate 완료 후 리더가 직접 검증한다.

- [ ] Go Bot 실행 → Slack 메시지 전송 → Spring Boot 수신 확인 (로그)
- [ ] 잘못된 Slack 서명 요청 → 403 응답 확인
- [ ] 유효한 자연어 입력 ("로그인 페이지에서 500 에러 남") → Claude 분류 결과 확인
- [ ] Jira에 이슈 실제 등록 확인
- [ ] "hello world" E2E 흐름 완성

---

## 리스크

| 리스크 | 영향 | 대응 |
|--------|------|------|
| Slack 3초 제한 | `@Async` 미적용 시 응답 누락 | security-config-engineer가 `AsyncConfig` 먼저 완성, spring-service-engineer가 이를 활용 |
| Claude 프롬프트 품질 낮음 | 엉뚱한 이슈 타입으로 Jira 등록 | spring-service-engineer에게 Few-shot 3~5개 예시 포함 명시 지시 |
| Spring Security 필터 순서 실수 | 서명 검증 필터가 동작 안 함 | security-config-engineer 계획 승인 시 필터 체인 순서 직접 확인 |
| 파일 충돌 | 두 teammate가 동일 파일 편집 | 파일 소유권 경계 프롬프트에 명시. 충돌 감지 시 즉시 중단 후 재할당 |
| Agent Team 토큰 비용 | 3명 × 독립 컨텍스트 → 단일 세션 대비 3배 이상 소비 | 3명으로 제한. 환경 세팅 등 단순 작업은 팀 없이 진행 |

---

## 학습 병행

- Spring Boot 프로젝트 구조, `@RestController`, `application.yml`
- Bean / DI 컨테이너 동작
- `@Async` / ThreadPoolTaskExecutor
- WebClient 비동기 HTTP 호출
- Spring Security 필터 체인
