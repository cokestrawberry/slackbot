# PR #4 비판적 리뷰: Add sprint statistics feature (@지라 통계)

> **PR**: https://github.com/Kim-YeongHyeon/slackbot/pull/4
> **상태**: OPEN
> **변경**: +441 / -1, 4 files (ScrumReportServiceImpl, SlackEventController, ScrumReportStatisticsTest)

---

## 1. 심각: 또다시 `findAll()` — 세 번째 PR에서 같은 패턴

```java
List<IssueEntity> allIssues = issueRepository.findAll();
```

PR #2의 semantic search에서도 `findAll()`이 문제였고, 이번에도 통계 리포트에서 전체 이슈를 메모리에 로드한다. 통계 집계(SP 합산, 상태별 카운트, 7일 번업)는 DB 쿼리(`GROUP BY`, `SUM`, `COUNT`)로 처리해야 할 작업을 전부 Java 스트림으로 하고 있다.

**구체적 문제:**
- 번업 차트에서 7일 × 전체 이슈를 필터링 → O(7 * N) 반복
- 이슈 5,000건이면 매 통계 요청마다 전체 entity + description을 메모리에 올림
- DB aggregate 쿼리로 대체하면 네트워크 전송량, 메모리, CPU 모두 절감

## 2. 번업 차트: N+1이 아니라 7*N 스트림 필터링

```java
for (int d = 6; d >= 0; d--) {
    LocalDate date = today.minusDays(d);
    Instant dayEnd = date.plusDays(1).atStartOfDay(kst).toInstant();
    cumulativeSp = issues.stream()
            .filter(i -> "완료".equals(i.getStatusCategory()))
            .filter(i -> { ... effective.isBefore(dayEnd) ... })
            .mapToDouble(...)
            .sum();
}
```

7일 동안 매일 전체 이슈를 스트림으로 순회한다. 한 번만 정렬/그룹핑하고 누적 합산하면 O(N)으로 끝난다. 혹은 DB에서 `GROUP BY DATE(completedAt)` 쿼리 한 번이면 된다.

## 3. "오늘 해결된 이슈" 판정의 `completedAt` fallback 문제

```java
Instant effectiveCompleted = i.getCompletedAt() != null
        ? i.getCompletedAt() : i.getJiraUpdated();
return effectiveCompleted != null && !effectiveCompleted.isBefore(todayStart);
```

PR #3 리뷰에서도 지적한 문제와 동일. `jiraUpdated`는 완료 시점이 아니라 마지막 업데이트 시점이다. 완료된 지 한 달 된 이슈에 오늘 댓글이 달리면 "오늘 해결된 이슈"에 잘못 포함된다. 이 fallback 로직이 세 개의 PR에서 반복되고 있으므로 공통 유틸로 추출하고, 부정확할 수 있다는 점을 사용자에게 표시해야 한다.

## 4. `statusCategory` 하드코딩: "완료", "진행 중", "해야 할 일"

```java
.filter(i -> "완료".equals(i.getStatusCategory()))
byStatus.get("완료")
byStatus.get("진행 중")
byStatus.get("해야 할 일")
```

상태 카테고리 문자열이 코드 전체에 매직 스트링으로 흩어져 있다. Jira 설정에 따라 다를 수 있고 (`Done`, `완료`, `To Do`, `해야 할 일`), 하나라도 불일치하면 통계가 누락된다. enum 또는 상수 클래스로 추출해야 한다.

## 5. `handleStatistics()`에서 `postMessage` 사용 — 다른 핸들러와 불일치

```java
slackNotifier.postMessage(event.channel(), report);
```

다른 모든 핸들러는 `replyThread()`로 스레드에 응답하는데, 통계만 `postMessage()`로 채널에 직접 보낸다. 채널에 긴 통계 리포트가 올라가면 대화 흐름을 방해한다. 의도적인 설계라면 주석이 필요하고, 아니라면 `replyThread`로 통일해야 한다.

## 6. SP 포맷팅 로직이 읽기 어려움

```java
sb.append(String.format("  • %s %s%s, 담당: %s)\n",
        issueLink(i.getIssueKey()), i.getSummary(),
        sp.isEmpty() ? " (" : sp.substring(0, sp.length() - 1) + ", ",
        assignee));
```

`sp.substring(0, sp.length() - 1) + ", "` — `spText()` 메서드가 반환하는 문자열의 마지막 문자를 잘라내고 쉼표를 붙이는 트릭인데, `spText()`의 반환 형식이 바뀌면 바로 깨진다. 포맷팅 로직이 두 메서드에 걸쳐 암묵적으로 결합되어 있다. SP 값과 포맷을 분리해서 명확하게 조합해야 한다.

## 7. `spText()` 메서드가 diff에 없음

`spText()`와 `issueLink()`를 호출하지만, 이 PR의 diff에는 해당 메서드 정의가 없다. 기존 `ScrumReportServiceImpl`에 있는 private 메서드로 추정되는데, 이 메서드들의 반환 형식을 모르면 포맷팅 결과를 예측할 수 없다. 리뷰어가 전체 파일을 열어봐야 하는 구조.

## 8. 통계 리포트가 "스프린트" 통계라고 하지만 스프린트 범위가 없음

리포트 제목이 "스프린트 통계 요약"인데, 실제로는 DB 전체 이슈를 대상으로 한다. 스프린트 필터링이 없으므로 모든 과거 이슈가 포함된다. 사용자가 "스프린트 통계"를 기대하면 현재 스프린트의 이슈만 나올 거라 생각할 수 있다.

## 9. 프로그레스 바의 시각적 한계

```java
String progressBar(double ratio) {
    int filled = (int) (ratio * 20);
    return "█".repeat(filled) + "░".repeat(20 - filled);
}
```

Slack에서 `█`과 `░`는 모노스페이스 환경에서만 일정하게 표시된다. 모바일 Slack이나 일부 클라이언트에서는 폰트에 따라 깨질 수 있다. 사소한 문제지만 인지해둘 필요 있음.

## 10. 테스트에서 reflection으로 `completedAt` 설정

```java
java.lang.reflect.Field completedField = IssueEntity.class.getDeclaredField("completedAt");
completedField.setAccessible(true);
completedField.set(issue, completedAt);
```

테스트를 위해 reflection으로 private 필드를 조작하고 있다. `IssueEntity`의 생성자나 setter가 `completedAt`을 적절히 지원하지 않는다는 의미인데, entity 설계를 고쳐야 할 신호이지 reflection으로 우회할 문제가 아니다.

---

**요약:**
- `findAll()` + Java 스트림 집계는 DB aggregate 쿼리로 대체해야 함
- 번업 차트의 7*N 반복은 비효율적
- `completedAt` fallback 문제가 세 번째 PR에서 반복
- "스프린트" 통계라고 하면서 스프린트 범위 필터링 없음
- 상태 카테고리 매직 스트링을 상수로 추출 필요
