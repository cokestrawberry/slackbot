# PRD — Jira 변경 알림 (C방식: 스레드/채널)

> **Branch:** `feature/jira-change-notification`
> **우선순위:** 높음
> **난이도:** 중간

## 1. 배경

현재 봇은 Slack → Jira 방향만 지원한다. **Jira에서 직접 변경한 내용은 Slack에 반영되지 않는다.**
팀원이 Jira 웹에서 이슈 상태를 변경하면, Slack에서도 알림을 받아야 한다.

## 2. 구현 방식: 폴링 (Polling)

### Webhook vs Polling 비교

| 항목 | Webhook | Polling |
|------|---------|---------|
| 실시간성 | 즉시 | 지연 (N분) |
| 설정 복잡도 | Jira 관리자 + ngrok URL 등록 필요 | 없음 (코드만) |
| ngrok 의존성 | URL 변경 시 재등록 | 없음 |
| 운영 안정성 | ngrok 재시작 시 알림 끊김 | 안정적 |

**결론: Polling 선택** — ngrok 무료 플랜에서 URL이 변경되면 Webhook이 끊기므로, 현 단계에서는 폴링이 안정적.

### 폴링 주기
- `@Scheduled(fixedRate = 120000)` — **2분 간격**
- 마지막 확인 시점 이후 변경된 이슈만 조회 (Jira JQL `updated >= "..."`)
- 너무 빈번하면 Jira API Rate Limit에 걸릴 수 있으므로 2분이 적절

## 3. 사용자 시나리오

### 3-1. 스레드 알림 (slackThreadTs가 있는 이슈)
```
[원래 이슈 생성 스레드]
봇: 🔄 SLAC-7 상태 변경
    진행 중 → 완료
    담당자: 김영현
    https://xxx.atlassian.net/browse/SLAC-7
```

### 3-2. 채널 알림 (slackThreadTs가 없는 이슈 = Jira에서 직접 생성)
```
[채널 메시지]
봇: 🔄 SLAC-20 상태 변경
    해야 할 일 → 진행 중
    담당자: 최아록
    https://xxx.atlassian.net/browse/SLAC-20
```

### 3-3. 알림 대상 이벤트
- **상태 변경** (statusCategory 변경): 해야 할 일 → 진행 중 → 완료 등
- 단순 필드 수정 (summary 변경, SP 변경 등)은 **알림하지 않음** (노이즈 방지)

## 4. 기술 설계

### 4-1. 변경 감지 메커니즘

**DB 기반 비교:**
1. `@Scheduled`로 2분마다 실행
2. DB의 모든 활성 이슈(`statusCategory <> '완료'`)를 가져옴
3. Jira API로 해당 이슈들의 현재 상태 조회
4. DB 상태 ↔ Jira 상태 비교 → 차이 있으면 알림 + DB 업데이트

**대안 — JQL 기반:**
1. `updated >= "{lastCheckTime}"` JQL로 최근 변경 이슈만 조회
2. DB 상태와 비교 → 차이 있으면 알림

**선택: JQL 기반** — 네트워크 효율적 (변경된 것만 가져옴)

### 4-2. JQL 쿼리

```
project = {projectKey} AND updated >= "{lastCheckTime}" ORDER BY updated ASC
```

- `lastCheckTime`: 이전 폴링 시점 (메모리 또는 DB에 저장)
- 시작 시 `lastCheckTime = now - 2분`으로 초기화

### 4-3. 알림 조건 필터링

```java
// 상태 카테고리가 변경된 경우만 알림
if (!Objects.equals(dbIssue.getStatusCategory(), jiraStatusCategory)) {
    sendNotification(dbIssue, oldStatus, newStatus);
    dbIssue.updateFrom(...);
}
```

### 4-4. 알림 라우팅 (C방식)

```java
void sendNotification(IssueEntity issue, String oldStatus, String newStatus) {
    String message = formatChangeMessage(issue, oldStatus, newStatus);
    
    if (issue.getSlackChannel() != null && issue.getSlackThreadTs() != null) {
        // A: 스레드 알림 — 봇이 생성한 이슈
        slackNotifier.postThreadReply(issue.getSlackChannel(), issue.getSlackThreadTs(), message);
    } else if (defaultNotificationChannel != null) {
        // B: 채널 알림 — Jira에서 직접 생성된 이슈
        slackNotifier.postMessage(defaultNotificationChannel, message);
    }
}
```

### 4-5. 기본 알림 채널

- `application.yml`에 `slack.notification-channel` 추가
- 환경변수: `SLACK_NOTIFICATION_CHANNEL`
- 값이 없으면 채널 알림 비활성화 (스레드 알림만 동작)

### 4-6. 영향 범위

| 파일 | 변경 내용 |
|------|-----------|
| **신규** `JiraChangeNotificationService.java` | 폴링 + 변경 감지 + 알림 전송 서비스 |
| `JiraApiClient.java` / `Impl` | `searchIssuesByJql(String jql)` 메서드 추가 |
| `application.yml` | `slack.notification-channel` 추가 |
| `IssueEntity.java` | 변경 없음 (기존 필드로 충분) |

### 4-7. JiraApiClient 추가 메서드

```java
// JQL로 이슈 검색 → SprintIssue 리스트 반환
List<SprintIssue> searchByJql(String jql);
```

기존 `getSprintIssues()`의 파싱 로직을 재활용하되, endpoint를 `/rest/api/3/search`로 변경.

## 5. 엣지 케이스

| 케이스 | 처리 |
|--------|------|
| Jira API 실패 (네트워크/Rate Limit) | warn 로그, lastCheckTime 유지 (다음 폴링에서 재시도) |
| DB에 없는 이슈가 JQL 결과에 포함 | DB에 새로 추가 (sync 효과) + 알림 생략 (첫 등장이므로 "변경"이 아님) |
| 동일 이슈가 2분 내 여러 번 변경 | 마지막 상태만 알림 (중간 상태 스킵) |
| 봇이 Slack에서 완료 처리한 직후 폴링 | DB가 이미 업데이트되어 있으므로 중복 알림 없음 |
| 앱 재시작 직후 | lastCheckTime이 null → 최근 2분만 조회 |
| notification-channel 미설정 | 스레드 정보 없는 이슈는 알림 생략 (warn 로그) |

## 6. 테스트 계획

### 단위 테스트
- 상태 변경 감지 로직 (같은 상태 → 무시, 다른 상태 → 알림)
- 알림 라우팅 (스레드 있으면 스레드, 없으면 채널, 채널 미설정 → 생략)
- JQL 쿼리 생성 검증
- 메시지 포맷 검증

### 통합 테스트
- Jira 웹에서 이슈 상태 변경 → 2분 내 Slack 알림 수신 확인
- 스레드 알림 vs 채널 알림 분기 확인

## 7. 설정 추가

```yaml
# application.yml
slack:
  notification-channel: ${SLACK_NOTIFICATION_CHANNEL:}
```

```bash
# .env
SLACK_NOTIFICATION_CHANNEL=C0XXXXXXXXX  # 알림 받을 채널 ID
```
