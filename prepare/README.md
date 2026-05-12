# Phase 1 E2E 준비 가이드

E2E 검증(Task #7)을 시작하기 전에 남은 수동 작업 체크리스트입니다.

## 이미 끝난 항목 (현재 맥북 환경)

- [x] JDK 17 (`17.0.18` via Homebrew)
- [x] Go 1.25+ (`1.26.1`)
- [x] Docker Desktop CLI (`29.4.0`) — 테스트 시 데몬 기동만 필요
- [x] ngrok 설치 (`3.37.6`) — authtoken 등록만 확인
- [x] `claude` CLI 설치 + 로그인 (Claude Code 세션 재사용)
- [x] Spring Boot 단위 테스트 39/39 green

## 남은 수동 작업

- [ ] **01. Slack App 생성 & 권한 설정** → [`01-slack-app.md`](./01-slack-app.md)
  - Signing Secret, Bot Token 획득 → `.env`
  - Event Subscriptions 은 ngrok URL 먼저 잡은 뒤 등록
- [ ] **03. Jira API Token 발급 & 프로젝트 확인** → [`03-jira.md`](./03-jira.md)
  - base-url, email, API token, project key → `.env`
  - Bug / Task / Story 이슈 타입 존재 확인
  - Story Points 필드 (customfield_10016) 활성 확인
- [ ] **04. ngrok authtoken 확인** → [`04-public-url.md`](./04-public-url.md)
  - 최초 1회만 `ngrok config add-authtoken <TOKEN>`
- [ ] **07. `.env` 작성 & 4터미널 실행** → [`07-run-order.md`](./07-run-order.md)
  - Postgres → Spring → Go bot → ngrok 순서

## 빠른 시작 경로

```bash
cd /Users/kim-yeonghyeon/Desktop/slackbot

# 0) Docker Desktop 기동
open -a "Docker Desktop"

# 1) Claude CLI 스모크 (실패 시 `claude login`)
echo 'ping' | claude -p --output-format json \
  --permission-mode plan --max-turns 1 --model claude-sonnet-4-5

# 2) .env 생성 후 값 채우기 (Slack + Jira 값만)
cp .env.example .env && vi .env

# 3) Postgres
docker compose up -d postgres

# 4) Spring (새 터미널)
set -a && source .env && set +a
./gradlew bootRun

# 5) Go proxy (새 터미널)
cd bot && set -a && source ../.env && set +a && go run .

# 6) ngrok (새 터미널)
ngrok http 3000
```

그 후 Slack Event Subscriptions Request URL 등록 → 채널에서 `@JiraBot ...` 멘션.

준비 완료되면 **"#7 시작"** 이라고 말씀해주시면 E2E 검증 착수합니다.
