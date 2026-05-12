# 03. Jira API Token 발급 & 프로젝트 확인

## 최종 산출물

```env
JIRA_BASE_URL=https://your-domain.atlassian.net
JIRA_EMAIL=you@example.com
JIRA_API_TOKEN=<your-api-token>
JIRA_PROJECT_KEY=SLAC
```

## 전제

- Atlassian Cloud 계정 (jira-software 무료 플랜 OK)
- 본인이 **이슈 생성 권한**을 가진 프로젝트 1개
  - Bug / Task / Story 이슈 타입 존재해야 함 (Phase 1 분류 대응)

## 단계

### 1) Base URL 확인

브라우저에서 Jira 접속 시 주소창:
```
https://your-domain.atlassian.net/jira/your-work
```
→ `JIRA_BASE_URL=https://your-domain.atlassian.net` (끝 슬래시 없이)

### 2) Email 확인

- Atlassian 계정 이메일. Jira 로그인에 쓰는 것.

### 3) API Token 발급

1. https://id.atlassian.com/manage-profile/security/api-tokens
2. **Create API token** → Label: `jirabot-dev`
3. 생성된 토큰 즉시 복사 (재확인 불가)
4. `.env` 의 `JIRA_API_TOKEN` 에 붙여넣기

> Basic Auth 형식: `email:api_token` 을 base64. `WebClientConfig` 에서 자동 처리.

### 4) Project Key 확인

- Jira → **Projects** → 해당 프로젝트 → 좌측 **Project settings** → **Details** → **Key**
- 예: `SLAC`, `PROJ`, `TEST`. `.env` 의 `JIRA_PROJECT_KEY` 에 입력.

### 5) Issue Types 확인

Project settings → **Issue types** 에 아래 3개가 있어야 합니다:

- `Bug` → Slack 의 BUG 분류
- `Task` → OTHER
- `Story` → FEATURE

> 회사/팀마다 이름이 다를 수 있음. 다른 이름이면 `ClaudeApiClientImpl` 의 매핑 수정 필요.

## 검증 (API 호출 테스트)

```bash
# 프로젝트 조회
curl -s -u "$JIRA_EMAIL:$JIRA_API_TOKEN" \
  -H "Accept: application/json" \
  "$JIRA_BASE_URL/rest/api/3/project/$JIRA_PROJECT_KEY" \
  | jq '.key, .name, .issueTypes[].name'
```

200 + issueTypes 나열되면 정상.

## 트러블슈팅

- **401 Unauthorized**: email 오타 or token 만료/잘못. 토큰 재발급.
- **403 Forbidden**: 프로젝트 접근 권한 없음. 프로젝트 role 확인.
- **404 Not Found**: `JIRA_PROJECT_KEY` 철자 확인 (대소문자 구분).
- **Story Point 필드가 안 보임**: Custom field `customfield_10016` (클라우드 기본) 가 프로젝트에 추가되어 있어야 함. Project settings → Fields → Story Points 활성화.
