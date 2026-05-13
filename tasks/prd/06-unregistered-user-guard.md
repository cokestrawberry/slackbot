# PRD — 미등록 사용자 이슈 생성 차단

> **Branch:** `feature/unregistered-user-guard`
> **우선순위:** 높음
> **난이도:** 낮음

## 1. 배경

현재 이슈 생성 시 Slack ↔ Jira 매핑이 없으면 Slack API로 실명을 가져와 **자동으로 Jira 이름으로 간주**한다.
Slack 실명과 Jira 이름이 다른 경우(대부분), 보고자가 기본값("김영현" 등)으로 잘못 지정되거나
Jira에서 해당 이름을 찾지 못해 미배정으로 등록된다.

`@지라 등록 <Jira 사용자명>` 기능이 이미 존재하지만, 사용자가 이 기능을 모르고 바로 이슈를 생성하는 경우가 많다.

## 2. 목표

- **이슈 생성 시점**에 Slack ↔ Jira 매핑(`user_mappings`)이 없으면 **이슈를 생성하지 않고** 등록 안내 메시지를 반환
- 검색, 버그 조회, 통계, 스크럼 등 **다른 기능은 영향 없음** — 조회 기능은 등록 없이 사용 가능
- 자동 매핑(Slack 실명 → Jira 이름 추론) 제거 — 잘못된 매핑을 만드는 원인

## 3. 사용자 시나리오

### 3-1. 미등록 사용자가 이슈 생성 시도
```
사용자: @지라 로그인 페이지에서 500 에러 발생
봇:
  :warning: Jira 계정이 연결되지 않았습니다.
  먼저 아래 명령으로 등록해주세요:
  `@지라 등록 <Jira에 표시되는 이름>`
  예: `@지라 등록 홍길동`
  등록 후 다시 시도해주세요!
```

### 3-2. 등록된 사용자가 이슈 생성 (기존 동작 유지)
```
사용자: @지라 로그인 페이지에서 500 에러 발생
봇:
  ✅ Jira 이슈가 등록되었습니다! [SLAC-99] 로그인 500 에러 ...
```

### 3-3. 등록 후 재시도
```
사용자: @지라 등록 홍길동
봇: ✅ 등록 완료! Slack: 홍길동 / Jira: 홍길동

사용자: @지라 로그인 페이지에서 500 에러 발생
봇: ✅ Jira 이슈가 등록되었습니다! (보고자: 홍길동)
```

## 4. 기술 설계

### 4-1. 변경 파일: `IssueCreateServiceImpl.java`

**`createFromSlackText()` 메서드 시작부에 매핑 체크 추가:**

```java
// 매핑 존재 여부 확인 (DB only, 자동 매핑 없이)
var mapping = userMappingRepository.findBySlackUserId(command.slackUserId());
if (mapping.isEmpty()) {
    // 이슈 생성하지 않고 등록 안내 반환
    notifyRegistrationRequired(command);
    return CompletableFuture.completedFuture(IssueCreateResult.failure("unregistered"));
}
```

**`resolveReporterName()` 단순화:**
- 자동 매핑(Slack API 실명 조회 → DB 저장) 로직 제거
- DB 매핑만 조회, 없으면 slackUserId 반환 (여기까지 오지 않지만 안전장치)

**`notifyRegistrationRequired()` 추가:**
```java
private void notifyRegistrationRequired(IssueCreateCommand command) {
    String message = ":warning: Jira 계정이 연결되지 않았습니다.\n"
            + "먼저 아래 명령으로 등록해주세요:\n"
            + "`@지라 등록 <Jira에 표시되는 이름>`\n"
            + "예: `@지라 등록 홍길동`\n"
            + "등록 후 다시 시도해주세요!";
    slackNotifier.postThreadReply(command.channel(), command.eventTs(), message);
}
```

### 4-2. 영향 범위

| 파일 | 변경 내용 |
|------|-----------|
| `IssueCreateServiceImpl.java` | 매핑 체크 + 안내 메시지 + 자동 매핑 제거 |

### 4-3. 영향 **없는** 기능

| 기능 | 이유 |
|------|------|
| `@지라 검색` | IssueSearchService — 매핑 불필요 |
| `@지라 버그` | BugQueryService — 매핑 불필요 |
| `@지라 통계` | ScrumReportService — 매핑 불필요 |
| `@지라 scrum` | ScrumReportService — 매핑 불필요 |
| `@지라 내작업` | ScrumReportService — 이미 자체적으로 매핑 조회 |
| `@지라 완료` | SlackEventController — 스레드 기반, 매핑 불필요 |
| `@지라 등록` | SlackEventController — 등록 자체이므로 당연히 통과 |

### 4-4. 새 파일: 없음

## 5. 엣지 케이스

| 케이스 | 처리 |
|--------|------|
| `@지라 등록` 후 바로 이슈 생성 | DB에 매핑 저장됨 → 정상 동작 |
| 자동 매핑으로 이미 저장된 사용자 | 기존 매핑이 있으므로 정상 통과 (기존 자동 매핑 데이터는 유지) |
| Slack API 실명 조회가 Jira 이름과 같은 사용자 | 자동 매핑 제거로 인해 등록 필요 — 의도된 동작 |

## 6. 테스트 계획

### 단위 테스트
- 미등록 사용자 → `notifyRegistrationRequired()` 호출, 이슈 미생성
- 등록 사용자 → 기존 흐름 정상 동작
- `resolveReporterName()` — 자동 매핑 제거 확인
