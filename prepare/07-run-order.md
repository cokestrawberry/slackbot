# 07. `.env` 작성 & 실행 순서

## 1) `.env` 작성

```bash
cd /Users/kim-yeonghyeon/Desktop/slackbot
cp .env.example .env
vi .env   # 또는 code .env, nano .env
```

앞 단계에서 모은 값으로 채웁니다.

```env
# ===== Slack =====
SLACK_SIGNING_SECRET=01234567890abcdef...   # 01-slack-app.md
SLACK_BOT_TOKEN=xoxb-...                    # 01-slack-app.md

# ===== Claude Code CLI =====
# API Key 불요. `claude login` 상태면 이 섹션은 비워둡니다.
# CLAUDE_CLI_PATH=claude   # PATH 에 없을 때만 절대경로 지정

# ===== Jira =====
JIRA_BASE_URL=https://your-domain.atlassian.net   # 03-jira.md
JIRA_EMAIL=you@example.com
JIRA_API_TOKEN=<your-api-token>
JIRA_PROJECT_KEY=SLAC

# ===== Postgres =====
POSTGRES_DB=jirabot
POSTGRES_USER=jirabot
POSTGRES_PASSWORD=아무거나-강한-값   # 예: openssl rand -base64 24
```

`.env` 는 `.gitignore` 에 걸려 있어 커밋 안 됩니다. 그래도 실수 방지 위해 `git status` 확인.

## 2) 사전 점검 (deps 는 이미 설치됨)

```bash
# Docker Desktop 기동
open -a "Docker Desktop"
# 상태창에 고래 아이콘 Running 될 때까지 30~60초 대기
docker ps   # 빈 출력이면 데몬 OK

# Claude CLI 스모크 (401/권한 에러 없어야 함)
echo 'ping' | claude -p --output-format json \
  --permission-mode plan --max-turns 1 --model claude-sonnet-4-5
# → {"type":"result","subtype":"success","is_error":false,"result":"..."}
```

## 3) 터미널별 실행 (4개 창)

### Terminal #1 — PostgreSQL

```bash
cd /Users/kim-yeonghyeon/Desktop/slackbot
docker compose up -d postgres
docker compose ps
# STATE: healthy 확인
```

### Terminal #2 — Spring Boot

```bash
cd /Users/kim-yeonghyeon/Desktop/slackbot
set -a && source .env && set +a
./gradlew bootRun
# Started SlackbotServerApplication in X.XXX seconds
# Tomcat started on port 8080
```

Health check:
```bash
curl http://localhost:8080/health
# {"status":"UP",...}
```

### Terminal #3 — Go Bot Proxy

```bash
cd /Users/kim-yeonghyeon/Desktop/slackbot/bot
set -a && source ../.env && set +a
go run .
# listening on :3000, forwarding to http://localhost:8080/api/slack/event
```

### Terminal #4 — ngrok

```bash
ngrok http 3000
# Forwarding https://abcd-1234.ngrok-free.app -> http://localhost:3000
```

이 URL 을 Slack App → Event Subscriptions → Request URL 에 붙여넣고 Verify.
**주의**: 무료 플랜은 재시작마다 URL 이 바뀌므로 Slack 설정 재입력 필요.

## 4) Slack 에서 실제 테스트

Slack 채널에서:

```
@JiraBot 로그인 버튼 클릭 시 앱이 죽습니다. 안드로이드 14 기기에서 재현 됨.
```

기대 동작:
1. Slack → ngrok → Go bot → Spring `POST /api/slack/event`
2. Go bot 이 200 즉시 반환 (3초 제약 통과)
3. Spring 이 `@Async` 로 IssueCreateService 실행
4. Claude CLI 서브프로세스 호출 → `{type:"BUG", storyPoint:3, title:"...", summary:"..."}`
5. Jira REST 호출 → `SLAC-123` 생성
6. (Phase 2 에서 Slack 에 링크 회신 — 지금은 로그로만 확인)

Spring 로그에서 `Created Jira issue: SLAC-...` 패턴 확인 → E2E 성공.
`command=[claude, -p, ...]` DEBUG 라인도 확인 가능.

## 5) 중단 / 재시작

```bash
# Postgres 중단
docker compose down

# 데이터까지 삭제 (초기화)
docker compose down -v
```

## 6) 흔한 문제

| 증상 | 원인 | 해결 |
|---|---|---|
| Slack Request URL Verify 실패 | Go bot 또는 Spring 안 떠 있음 | 터미널 2, 3 확인 |
| 403 Forbidden | Signing Secret 불일치 | `.env` 재확인, Spring 재시작 |
| Claude CLI 에러 / 401 | `claude login` 세션 만료 | `claude login` 재실행 |
| Claude fallback (OTHER/3) 만 생성 | 파싱 실패 | Spring WARN 로그 `Claude inner JSON parse failed` 확인 |
| 404 Jira | Project Key 오타 / 권한 없음 | `03-jira.md` |
| 중복 이슈 생성 | Slack 재전송 (ack 지연) | 로그에서 응답 시간 확인, AsyncConfig 포화 여부 |
| Postgres 연결 실패 | 컨테이너 미기동 / 포트 충돌 | `docker compose logs postgres` |

## 준비 완료 선언

위 과정까지 전부 잘 돌아가면 저한테 **"#7 시작"** 이라고 말씀해주세요. E2E 검증 체크리스트를 돌립니다.
