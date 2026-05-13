# PRD — 해결된 버그 조회 (트러블슈팅)

> **Branch:** `feature/bug-troubleshooting-query`
> **우선순위:** 중간
> **난이도:** 낮음~중간

## 1. 배경

특정 기간에 해결된 버그를 한눈에 조회하는 기능이 없다.
회고, 일일 리포트, 트러블슈팅 이력 확인 시 유용하다.

## 2. 사용자 시나리오

### 2-1. 날짜 지정 조회
```
사용자: @지라 버그 2026.03.11
봇:
🐛 2026.03.11 이후 해결된 버그 (3건)

  • <URL|SLAC-7> 로그인 페이지 500 에러 (완료 2026.04.22, SP 2, 담당: 김영현)
  • <URL|SLAC-15> 결제 금액 0원 표시 (완료 2026.04.20, SP 5, 담당: 최아록)
  • <URL|SLAC-22> 세션 만료 무한 로딩 (완료 2026.03.15, SP 3, 담당: 김영현)

📊 총 3건 해결 / 10 SP 완료
```

### 2-2. 자연어 조회
```
사용자: @지라 2026.03.11 부터 해결된 버그 알려줘
→ Haiku: intent=bug_query, extracted.date="2026.03.11"
→ 동일 결과
```

### 2-3. 날짜 없이 조회
```
사용자: @지라 버그
봇:
🐛 최근 7일간 해결된 버그 (1건)
  • <URL|SLAC-42> 검색 결과 정렬 오류 (완료 2026.05.10, SP 2, 담당: 김영현)

📊 총 1건 해결 / 2 SP 완료
```

### 2-4. 결과 없음
```
사용자: @지라 버그 2026.05.01
봇:
🐛 2026.05.01 이후 해결된 버그가 없습니다.
```

## 3. 기술 설계

### 3-1. 라우팅 (SlackEventController)

**키워드 매칭:**
```
"버그" 로 시작 → 뒤에 날짜 패턴이 있으면 파싱, 없으면 최근 7일
"버그조회", "bug" 도 동의어로 처리
```

```java
// routeCommand() 추가
if (lower.startsWith("버그") || lower.startsWith("bug")) {
    handleBugQuery(event, cleaned);
    return;
}
```

**Haiku fallback:**
- 현재 Haiku 프롬프트에 `bug_query` intent 없음 → 추가 필요 없음
- `statistics` intent로 분류될 수 있으므로, statistics case에서 버그 키워드 감지 시 분기

### 3-2. 날짜 파싱

```java
// 지원 패턴
private static final Pattern DATE_PATTERN = Pattern.compile(
    "(\\d{4})[.\\-/](\\d{1,2})[.\\-/](\\d{1,2})");

// "버그 2026.03.11" → "2026.03.11"
// "2026-03-11 부터 해결된 버그" → "2026-03-11"
// "버그" (날짜 없음) → 최근 7일
```

파싱 실패 또는 날짜 없음 → `LocalDate.now().minusDays(7)` 기본값

### 3-3. DB 쿼리

```java
// IssueRepository 추가
@Query("SELECT i FROM IssueEntity i WHERE i.issueType = '버그' " +
       "AND i.statusCategory = '완료' AND i.completedAt >= :since " +
       "ORDER BY i.completedAt DESC")
List<IssueEntity> findResolvedBugsSince(@Param("since") Instant since);
```

**`completedAt` 사용 이유:**
- `jiraUpdated`는 완료 후에도 다른 필드 수정으로 갱신될 수 있음
- `completedAt`은 완료 시점만 기록하므로 정확

**주의:** `completedAt`이 null인 이슈 = 완료 전 생성된 이슈 (sync 시 completedAt이 설정되지 않았을 수 있음)
→ `completedAt IS NULL AND statusCategory = '완료'`인 케이스는 `jiraUpdated`로 fallback

```java
// 보완 쿼리: completedAt이 없지만 완료 상태인 이슈도 포함
@Query("SELECT i FROM IssueEntity i WHERE i.issueType = '버그' " +
       "AND i.statusCategory = '완료' " +
       "AND (i.completedAt >= :since OR (i.completedAt IS NULL AND i.jiraUpdated >= :since)) " +
       "ORDER BY COALESCE(i.completedAt, i.jiraUpdated) DESC")
List<IssueEntity> findResolvedBugsSince(@Param("since") Instant since);
```

### 3-4. 결과 포맷팅

```
🐛 {시작일} 이후 해결된 버그 ({N}건)

  • <URL|KEY> 요약 (완료 YYYY.MM.DD, SP N, 담당: 이름)
  • ...

📊 총 N건 해결 / M SP 완료
```

- Jira URL 하이퍼링크 포함
- 완료일: `completedAt` 또는 `jiraUpdated` 사용
- SP 합계 표시
- 최대 15건 표시, 초과 시 "외 N건" 안내

### 3-5. 영향 범위

| 파일 | 변경 내용 |
|------|-----------|
| `SlackEventController.java` | `routeCommand()`에 `버그` 키워드 추가, `handleBugQuery()` 메서드 |
| `IssueRepository.java` | `findResolvedBugsSince()` 쿼리 추가 |
| `HELP_TEXT` | 버그 조회 명령 추가 |

### 3-6. 새 파일: 없음

별도 서비스 불필요. Controller에서 Repository 직접 조회 + 포맷팅.

## 4. 엣지 케이스

| 케이스 | 처리 |
|--------|------|
| 날짜 형식 오류 (`2026.13.01`) | 파싱 실패 → 최근 7일 기본값 + 안내 |
| 미래 날짜 | 그대로 검색 (결과 0건) |
| `completedAt`이 null인 완료 이슈 | `jiraUpdated`로 fallback |
| DB 동기화 안 됨 (sync 미실행) | "sync를 먼저 실행해주세요" 안내 — 단, 결과 0건일 때만 |
| "버그" 키워드가 이슈 등록 요청과 혼동 | "버그" 단독 또는 "버그 + 날짜"만 매칭. "버그 발생했어요"는 Haiku로 넘김 |

### 라우팅 분기 규칙

```
"버그" 단독 → 버그 조회 (최근 7일)
"버그 2026.03.11" → 버그 조회 (날짜 지정)
"버그조회" → 버그 조회 (최근 7일)
"버그 발생했어요" → 날짜 없고 서술형 → Haiku로 넘김 (이슈 등록)
```

**판별:** 날짜 패턴 있으면 버그 조회. 없으면 텍스트가 `버그` 또는 `버그조회`와 정확히 일치할 때만 조회.
그 외("버그 + 서술문")는 Haiku fallback.

## 5. 테스트 계획

### 단위 테스트
- 날짜 파싱: `2026.03.11`, `2026-03-11`, `2026/03/11`, 날짜 없음, 잘못된 형식
- `findResolvedBugsSince()` 쿼리 검증
- 포맷팅: 0건, 1건, 15건 초과
- SP 합계 계산
- 라우팅: "버그" vs "버그 발생했어요" 분기

### 통합 테스트
- `@지라 버그 2026.03.11` → 스레드 응답 확인
- `@지라 버그` → 최근 7일 결과 확인
- 결과 없음 → 적절한 안내 메시지

## 6. HELP_TEXT 업데이트

```
  `@지라 버그` — 최근 7일간 해결된 버그 조회
  `@지라 버그 2026.03.11` — 특정 날짜 이후 해결된 버그 조회
```
