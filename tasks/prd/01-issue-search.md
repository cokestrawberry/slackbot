# PRD — 이슈 검색 (`@지라 검색 <키워드>`)

> **Branch:** `feature/issue-search`
> **우선순위:** 높음
> **난이도:** 낮음

## 1. 배경

현재 봇은 이슈를 생성/조회/완료할 수 있지만, **키워드로 이슈를 검색하는 기능이 없다.**
Haiku intent classifier에 `search` 의도가 이미 정의되어 있으나, "준비 중" 안내만 반환하고 있다.

## 2. 사용자 시나리오

### 2-1. 키워드 명령 (즉시 실행)
```
사용자: @지라 검색 로그인
봇:
🔍 "로그인" 검색 결과 (3건)
  • <URL|SLAC-7> 로그인 페이지 /auth/login 500 에러 (진행 중, SP 2, 담당: 김영현)
  • <URL|SLAC-11> 로그인 세션 만료 처리 (해야 할 일, SP 3, 담당: -)
  • <URL|SLAC-15> 로그인 UI 리뉴얼 (완료, SP 5, 담당: 최아록)
```

### 2-2. 검색 결과 없음
```
사용자: @지라 검색 블록체인
봇:
🔍 "블록체인" 검색 결과가 없습니다.
```

### 2-3. Haiku fallback
```
사용자: @지라 로그인 관련 이슈 찾아줘
→ Haiku가 intent=search, extracted.keyword="로그인" 추출
→ 동일한 검색 결과 반환
```

## 3. 기술 설계

### 3-1. 라우팅 (SlackEventController)

**키워드 매칭 추가** — `routeCommand()` 메서드에:
```
"검색 " 또는 "search " 으로 시작 → handleSearch(event, keyword)
```

**Haiku fallback** — `handleWithIntent()` 메서드의 `case "search"` 분기:
```
intent.extracted().get("keyword") → handleSearch(event, keyword)
keyword 없으면 rawText에서 추출 시도
```

### 3-2. 검색 로직

**기존 Repository 활용:**
- `IssueRepository.findBySummaryContaining(keyword)` — 이미 존재하나 `statusCategory <> '완료'` 조건 있음
- **새 쿼리 추가:** 모든 상태 포함 검색 (완료 이슈도 검색 가능해야 함)

```java
// IssueRepository 추가
@Query("SELECT i FROM IssueEntity i WHERE LOWER(i.summary) LIKE LOWER(CONCAT('%', :keyword, '%')) ORDER BY i.jiraUpdated DESC")
List<IssueEntity> searchByKeyword(@Param("keyword") String keyword);
```

### 3-3. 결과 포맷팅

- Jira URL 하이퍼링크: `<URL|SLAC-7>`
- 상태 표시: 진행 중, 해야 할 일, 완료
- SP 표시: SP 2
- 담당자: 있으면 표시, 없으면 `-`
- **최대 10건** 표시, 초과 시 "외 N건" 안내

### 3-4. 영향 범위

| 파일 | 변경 내용 |
|------|-----------|
| `SlackEventController.java` | `routeCommand()`에 `검색` 키워드 추가, `handleSearch()` 메서드, `handleWithIntent()`의 `search` case 수정 |
| `IssueRepository.java` | `searchByKeyword()` 쿼리 추가 |
| `HELP_TEXT` | 검색 명령 추가 |

### 3-5. 새 파일: 없음

기존 파일만 수정. 별도 서비스 불필요 (Controller에서 Repository 직접 조회 가능).

## 4. 엣지 케이스

| 케이스 | 처리 |
|--------|------|
| 키워드 없이 `@지라 검색` | "검색어를 입력해주세요. 예: `@지라 검색 로그인`" |
| 키워드가 1글자 | 그대로 검색 (DB LIKE 쿼리) |
| 결과 10건 초과 | 최신 10건 + "외 N건이 더 있습니다" |
| DB가 비어있음 | "검색 결과가 없습니다" |
| 특수문자 포함 키워드 | JPQL 파라미터 바인딩이므로 SQL injection 안전 |

## 5. 테스트 계획

### 단위 테스트
- `IssueRepository.searchByKeyword()` — 매칭/비매칭/대소문자 무시 검증
- `handleSearch()` — 결과 포맷팅 검증 (0건, 1건, 10건 초과)
- `stripMention()` + 검색 키워드 추출 검증

### 통합 테스트
- Slack에서 `@지라 검색 <키워드>` → 스레드 응답 확인
- Haiku `search` intent → 동일 결과 확인

## 6. HELP_TEXT 업데이트

```
  `@지라 검색 <키워드>` — DB에서 이슈 검색
```

## 7. TODO — 후속 개선

### 7-1. `findAll()` 제거: semantic search 확장성 문제 (높음)

**현재 문제:**
`IssueSearchServiceImpl.searchSemantic()`에서 `issueRepository.findAll()`로 전체 이슈를 메모리에 로드한 뒤 Sonnet에게 텍스트로 직렬화해서 전달하고 있음. 이슈가 수천 건 이상이면 OOM 위험 + LLM 입력 토큰 비용 선형 증가.

**개선 방향 — 검토 필요:**
- **방향 A: 상태 필터링** — 완료/미완료로 나눠서 검색 범위를 좁힘. 예: 기본은 미완료 이슈만 검색, `@지라 검색 -all 로그인` 또는 `@지라 검색 -완료 로그인` 옵션으로 완료 포함
- **방향 B: DB 키워드 검색 → LLM 재정렬** — `findAll()` 대신 `searchByKeyword()`로 후보를 먼저 좁힌 뒤 Sonnet에게 관련도 재정렬만 시킴 (입력 크기 대폭 감소)
- **방향 C: 벡터 검색 (pgvector 등)** — 이슈 동기화 시 임베딩을 미리 저장하고, 검색 시 코사인 유사도로 조회. LLM 호출 자체를 제거
- **방향 D: Jira JQL 직접 검색** — DB를 거치지 않고 Jira API의 JQL 검색을 활용 (`summary ~ "키워드"`)

**결정 기준:**
- 현재 이슈 규모와 증가 추세
- Sonnet 호출 비용 허용 범위
- 인프라 변경 부담 (pgvector 도입 등)

### 7-2. `searchByKeyword` JPQL에 LIMIT 추가

DB 쿼리에 결과 수 제한이 없어서 짧은 키워드(`로`, `a` 등)로 수천 건이 반환될 수 있음. `Pageable` 파라미터 추가 또는 Spring Data의 `findTop50By...` 패턴 적용 필요.

### 7-3. 루트 레벨 프롬프트 파일 제거

`prompts/sonnet-issue-search.md` (프로젝트 루트)가 `src/main/resources/prompts/`로 이동했으므로 루트 파일 삭제 필요.
