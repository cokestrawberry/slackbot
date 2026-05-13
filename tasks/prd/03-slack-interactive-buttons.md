# PRD — Slack 버튼 UI (Interactive Components)

> **Branch:** `feature/slack-interactive-buttons`
> **우선순위:** 중간
> **난이도:** 중간

## 1. 배경

이슈 생성 알림에 "진행 중", "완료" 버튼을 추가하여 **클릭 한 번으로 Jira 상태를 전환**할 수 있게 한다.
현재는 `@지라 완료`를 스레드에 댓글로 입력해야 하므로, 버튼 UI가 더 직관적이다.

## 2. 사용자 시나리오

### 2-1. 이슈 생성 시 버튼 포함 알림
```
✅ Jira 이슈가 등록되었습니다!
[SLAC-15] 결제 금액 0원 표시 버그
분류: BUG | Story Point: 5
https://xxx.atlassian.net/browse/SLAC-15

[🔨 진행 중]  [✅ 완료]     ← 클릭 가능한 버튼
```

### 2-2. 버튼 클릭 후 메시지 업데이트
```
✅ Jira 이슈가 등록되었습니다!
[SLAC-15] 결제 금액 0원 표시 버그
분류: BUG | Story Point: 5
https://xxx.atlassian.net/browse/SLAC-15

✅ 완료 처리됨 (by 김영현)
```
- 버튼이 사라지고 결과 텍스트로 교체됨

### 2-3. 이미 완료된 이슈의 버튼 클릭
```
이 이슈는 이미 완료 상태입니다.
```
- ephemeral 메시지로 클릭한 사용자에게만 표시

## 3. 기술 설계

### 3-1. Slack Block Kit 메시지 구조

현재 `slackNotifier.postThreadReply(channel, threadTs, text)` → 순수 텍스트
**변경:** Block Kit JSON으로 메시지 전송

```json
{
  "channel": "C0XXX",
  "thread_ts": "1234.5678",
  "blocks": [
    {
      "type": "section",
      "text": {
        "type": "mrkdwn",
        "text": "✅ Jira 이슈가 등록되었습니다!\n*[SLAC-15] 결제 금액 0원 표시 버그*\n분류: BUG | Story Point: 5\nhttps://..."
      }
    },
    {
      "type": "actions",
      "elements": [
        {
          "type": "button",
          "text": { "type": "plain_text", "text": "🔨 진행 중" },
          "action_id": "jira_transition_in_progress",
          "value": "SLAC-15"
        },
        {
          "type": "button",
          "text": { "type": "plain_text", "text": "✅ 완료" },
          "action_id": "jira_transition_done",
          "style": "primary",
          "value": "SLAC-15"
        }
      ]
    }
  ],
  "text": "✅ Jira 이슈가 등록되었습니다! [SLAC-15]"
}
```

`text` 필드는 Block Kit 미지원 클라이언트용 fallback.

### 3-2. Interaction Endpoint

Slack 버튼 클릭 시 Slack이 **Interaction Request URL**로 POST를 보낸다.
이 URL은 Event Subscriptions URL과 **별도 설정**이다.

**요청 형식:**
- Content-Type: `application/x-www-form-urlencoded`
- Body: `payload=<URL-encoded JSON>`
- HMAC 서명 검증 동일 (X-Slack-Signature, X-Slack-Request-Timestamp)

**Payload 구조 (핵심 필드):**
```json
{
  "type": "block_actions",
  "user": { "id": "U03L1TJ0EBB", "name": "kim" },
  "channel": { "id": "C0XXX" },
  "message": { "ts": "1234.5678" },
  "actions": [{
    "action_id": "jira_transition_in_progress",
    "value": "SLAC-15"
  }]
}
```

### 3-3. Go Bot 변경

Go Bot에 interaction 포워딩 경로 추가:

```go
// main.go
mux.Handle("/slack/interactions", interactionHandler)
```

- Slack Interactivity URL: `https://<ngrok-url>/slack/interactions`
- Go Bot이 Spring Boot `/api/slack/interaction`으로 포워딩
- **주의:** Content-Type이 `application/x-www-form-urlencoded` (JSON이 아님)

### 3-4. Spring Boot Interaction Controller

**신규 엔드포인트:** `POST /api/slack/interaction`

```java
@PostMapping(path = "/interaction", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
public ResponseEntity<Map<String, Object>> onInteraction(@RequestParam("payload") String payloadJson) {
    // 1. payload JSON 파싱
    // 2. action_id + value(issueKey) 추출
    // 3. JiraApiClient.transitionIssue(issueKey, targetStatus) 호출
    // 4. 원본 메시지 업데이트 (chat.update API)
    // 5. 200 OK 반환 (3초 이내)
}
```

### 3-5. 메시지 업데이트 (chat.update)

버튼 클릭 후 원본 메시지를 업데이트하여 버튼 제거 + 결과 표시:

```java
// SlackNotifier 추가
void updateMessage(String channel, String messageTs, String blocksJson);
```

### 3-6. SlackSignatureFilter 수정

현재 `shouldNotFilter()`가 `/api/slack/` prefix만 확인하므로 `/api/slack/interaction`은 자동으로 HMAC 검증 대상.

**주의:** interaction 요청은 `application/x-www-form-urlencoded`이므로, `CachedBodyFilter`가 이 Content-Type도 캐시하는지 확인 필요.

### 3-7. 영향 범위

| 파일 | 변경 내용 |
|------|-----------|
| **신규** `SlackInteractionController.java` | `/api/slack/interaction` 엔드포인트 |
| `SlackNotifier.java` / `Impl` | `postBlockMessage()`, `updateMessage()` 추가 |
| `IssueCreateServiceImpl.java` | 이슈 생성 알림을 Block Kit 메시지로 변경 |
| `SecurityConfig.java` | `/api/slack/interaction` permitAll 추가 |
| `bot/main.go` | `/slack/interactions` 경로 추가 |
| `bot/handler.go` | interaction 요청 포워딩 핸들러 (또는 기존 Handler 재사용) |
| `bot/config.go` | `SPRING_INTERACTION_URL` 환경변수 (또는 기존 URL 재사용) |

### 3-8. 새 DTO

```java
// SlackInteractionPayload.java (record)
public record SlackInteractionPayload(
    String type,
    SlackUser user,
    SlackChannel channel,
    SlackMessage message,
    List<SlackAction> actions
) {
    public record SlackUser(String id, String name) {}
    public record SlackChannel(String id) {}
    public record SlackMessage(String ts) {}
    public record SlackAction(String actionId, String value) {}
}
```

## 4. Slack App 설정 (사용자 수동)

1. https://api.slack.com/apps → 앱 선택
2. **Interactivity & Shortcuts** → Enable → ON
3. **Request URL:** `https://<ngrok-url>/slack/interactions`
4. Save Changes

## 5. 엣지 케이스

| 케이스 | 처리 |
|--------|------|
| Jira transition 실패 (권한 없음 등) | ephemeral 에러 메시지 + 버튼 유지 |
| 이미 해당 상태인 이슈 | ephemeral "이미 X 상태입니다" |
| 3초 제한 초과 | 비동기 처리 + `response_url`로 deferred response |
| DB에 이슈 없음 (issueKey로 조회 실패) | 에러 메시지 + 로그 |
| 동시에 여러 사용자가 같은 버튼 클릭 | 첫 번째 클릭만 성공, 이후는 "이미 X 상태" |
| ngrok URL 변경 | Interactivity URL도 재등록 필요 (Event URL과 동일 문제) |

## 6. 테스트 계획

### 단위 테스트
- Block Kit JSON 생성 검증
- Interaction payload 파싱 검증
- action_id → Jira status 매핑 검증
- 메시지 업데이트 블록 생성 검증

### 통합 테스트
- 이슈 생성 → 버튼 포함 메시지 확인
- "진행 중" 버튼 클릭 → Jira 상태 확인 + 메시지 업데이트 확인
- "완료" 버튼 클릭 → Jira 완료 + DB completedAt 업데이트 확인
- 이미 완료된 이슈 버튼 클릭 → ephemeral 메시지 확인

## 7. 선행 조건

- Slack App에서 Interactivity 활성화 (사용자 수동)
- Go Bot에 interaction 포워딩 경로 추가
