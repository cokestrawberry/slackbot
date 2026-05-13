# PR #5 비판적 리뷰: Add Slack interactive buttons for issue status transitions

> **PR**: https://github.com/Kim-YeongHyeon/slackbot/pull/5
> **상태**: OPEN
> **변경**: +875 / -28, 16 files (Java + Go)

---

## 1. 심각: Block Kit JSON을 StringBuilder로 수동 조립

```java
static String buildIssueCreatedBlocks(String key, String url,
                                      IssueClassification classification,
                                      List<IssueEntity> similar) {
    StringBuilder blocks = new StringBuilder("[");
    blocks.append("{\"type\":\"section\",\"text\":{\"type\":\"mrkdwn\",\"text\":")
            .append("\"")
            .append(escapeBlockJson(String.format(
                    ":white_check_mark: ...\\n*<%s|[%s] %s>*\\n분류: %s | Story Point: %d",
                    url, key, classification.title(), ...)))
            .append("\"}}");
    ...
```

이미 프로젝트에 Jackson `ObjectMapper`가 있는데, Block Kit JSON을 StringBuilder 문자열 연결로 직접 만들고 있다. 주석에 "구조가 복잡해지면 ObjectMapper 사용을 고려"라고 적었지만, **이미 충분히 복잡하다.** 이 코드에서 이스케이프를 한 번이라도 빠뜨리면 유효하지 않은 JSON이 Slack에 전송되어 메시지가 표시되지 않는다.

**구체적 위험:**
- `classification.title()`에 `"` (따옴표)가 포함되면? `escapeBlockJson`이 처리하지만, `String.format` 안에서 `\\n`을 리터럴로 쓰고 있어서 이스케이프 레이어가 뒤섞임
- `similar` 이슈의 summary에 `\` 백슬래시가 있으면 이중 이스케이프 문제
- 테스트에서 `"Title with \"quotes\""` 케이스를 확인하지만, `\n`이 포함된 제목은 테스트하지 않음

**개선:** Jackson `ObjectNode`/`ArrayNode`로 구조적으로 조립하면 이스케이프 문제가 원천 차단된다.

## 2. 심각: HMAC 서명 검증 없음

`SlackInteractionController`에 Slack 요청의 HMAC 서명 검증이 없다. PR 설명에 "HMAC signature validation works for interaction endpoint"라고 적었지만, 코드에 검증 로직이 보이지 않는다.

```java
@PostMapping(path = "/interaction", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
public ResponseEntity<String> onInteraction(@RequestParam("payload") String payloadJson) {
    // 서명 검증 없이 바로 파싱
    SlackInteractionPayload payload = objectMapper.readValue(payloadJson, ...);
```

누구나 이 엔드포인트에 조작된 payload를 보내서 Jira 이슈 상태를 변경할 수 있다. **보안 취약점.** Go bot에서 HMAC 검증을 하고 있다면 Spring Boot 측은 신뢰할 수 있지만, Go bot의 `InteractionHandler`에도 HMAC 검증 코드가 없다 — 그냥 raw body를 포워딩할 뿐이다.

기존 이벤트 엔드포인트(`SlackEventController`)에는 HMAC 검증이 있는지도 확인 필요.

## 3. `escapeBlockJson` 메서드가 3곳에 중복

```java
// IssueCreateServiceImpl.java
private static String escapeBlockJson(String value) { ... }

// SlackInteractionController.java
private static String escapeBlockJson(String value) { ... }

// SlackNotifierImpl.java
private static String escapeJson(String value) { ... }
```

동일한 JSON 이스케이프 로직이 세 곳에 복붙되어 있다. 하나를 수정하면 나머지도 수정해야 하는데 빠뜨릴 가능성이 높다. 공통 유틸로 추출해야 한다.

## 4. `SlackNotifierImpl`에서 JSON을 수동으로 String.format

```java
String bodyJson = String.format(
        "{\"channel\":\"%s\",\"thread_ts\":\"%s\",\"text\":\"%s\",\"blocks\":%s}",
        escapeJson(channel), escapeJson(threadTs), escapeJson(text), blocksJson);
```

Slack API 요청 body를 `String.format`으로 조립하고 있다. `blocksJson`은 이미 JSON 배열 문자열이라고 가정하지만, 검증 없이 그대로 삽입한다. 잘못된 `blocksJson`이 들어오면 전체 JSON이 깨진다. `ObjectMapper`로 `ObjectNode`를 만들고 `blocks`에 `readTree(blocksJson)`를 넣는 게 안전하다.

## 5. 버튼 클릭 시 누가 클릭해도 전환됨 — 권한 체크 없음

```java
slackExecutor.execute(() ->
        handleTransition(actionId, issueKey, channelId, messageTs, userName));
```

이슈를 생성한 사람이 아닌 아무나 버튼을 클릭해도 Jira 상태가 전환된다. 의도된 동작일 수 있지만, 실수로 다른 사람의 이슈를 "완료"로 전환할 위험이 있다. 최소한 확인 메시지(confirmation dialog)가 있어야 한다. Slack Button에는 `confirm` 속성을 추가할 수 있다.

## 6. 버튼 클릭 후 원본 메시지 전체가 교체됨

```java
String updatedBlocks = buildCompletedBlocks(issueKey, statusEmoji, statusLabel, userName);
slackNotifier.updateMessage(channelId, messageTs, resultText, updatedBlocks);
```

`buildCompletedBlocks`는 원래 이슈 정보(제목, 분류, SP, 유사 이슈 경고)를 모두 버리고 `:white_check_mark: PROJ-1 → 완료 (by testuser)` 한 줄로 교체한다. 사용자가 나중에 이슈 정보를 확인하려면 원본 메시지가 사라져 있다.

**개선:** 기존 blocks 위에 상태 변경 결과를 추가하고, 버튼만 제거하는 게 낫다. 이를 위해 `payload.message()`에서 기존 blocks를 가져와서 수정해야 한다.

## 7. `updateFrom()` 호출 시 summary, issueType 등을 자기 자신의 값으로 덮어씀

```java
issue.updateFrom(issue.getSummary(), issue.getIssueType(),
        targetStatus, targetStatus,
        issue.getAssignee(), issue.getStoryPoint(), Instant.now());
```

상태만 변경하려는데 `updateFrom()`에 모든 필드를 전달해야 해서 자기 자신의 값을 그대로 넣고 있다. `updateFrom()`의 시그니처가 이 사용 사례에 맞지 않는다. 별도 `updateStatus(String statusCategory, Instant updatedAt)` 메서드가 필요하다.

## 8. Go bot의 `InteractionHandler`가 `Forwarder`의 URL을 하드코딩적으로 결정

```go
interactionForwarder := NewForwarder(cfg.SpringInteractionURL, &http.Client{Timeout: cfg.ForwardTimeout}, logger)
```

`Forwarder`가 이벤트와 인터랙션 두 가지 용도로 사용되는데, `Forward()` 메서드 내부에서 `Content-Type`을 어떻게 설정하는지가 중요하다. 이벤트는 `application/json`이고 인터랙션은 `application/x-www-form-urlencoded`인데, Forwarder가 원본 Content-Type 헤더를 전달하는지 확인 필요. 전달하지 않으면 Spring이 form 파싱에 실패한다.

## 9. version bump가 `0.0.2-SNAPSHOT`으로 PR #2와 충돌

```gradle
-version = '0.0.1-SNAPSHOT'
+version = '0.0.2-SNAPSHOT'
```

PR #2에서 이미 `0.0.2-SNAPSHOT`으로 올렸다. PR #2가 먼저 머지되면 이 변경은 diff에서 사라지지만, PR #3, #4도 같은 version bump를 포함하고 있어서 모든 PR이 같은 줄을 수정하는 불필요한 충돌이 발생한다.

## 10. 테스트에서 `updateMessage`의 blocks 내용을 검증하지 않음

```java
verify(slackNotifier).updateMessage(eq("C456"), eq("1234567890.123456"), anyString(), anyString());
```

`anyString()`으로 blocks를 검증하므로 실제로 유효한 Block Kit JSON이 전달되었는지, 원래 이슈 정보가 포함되어 있는지 알 수 없다. `ArgumentCaptor`로 캡처해서 JSON 파싱 + 내용 검증이 필요하다.

## 11. 에러 응답이 항상 200 OK

```java
} catch (Exception e) {
    log.error("Failed to parse interaction payload: {}", e.toString(), e);
    return ResponseEntity.ok("");
}
```

Slack interaction은 200을 반환해야 재전송을 막을 수 있으므로 의도된 동작이지만, 파싱 실패, 알 수 없는 action 등 모든 에러 상황에서 200을 반환하면 디버깅이 어렵다. 로깅은 하고 있지만, 모니터링 시 HTTP 상태 코드로 에러율을 추적할 수 없게 된다.

---

**요약:**
- HMAC 서명 검증 없음 — 보안 취약점
- Block Kit JSON을 StringBuilder로 수동 조립 — Jackson ObjectNode 사용 권장
- `escapeBlockJson`/`escapeJson` 3곳 중복 — 공통 유틸 추출 필요
- 버튼 클릭 시 권한 체크 없음, 확인 dialog 없음
- 원본 메시지가 상태 결과로 완전 교체됨 — 이슈 정보 소실
- `updateFrom()` 시그니처가 상태 변경 사용 사례에 부적합
