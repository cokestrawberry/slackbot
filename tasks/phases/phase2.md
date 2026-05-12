# Phase 2 — DB 연결 + DuplicateDetectionService + IssueQueryService

> **목표:** PostgreSQL 연결 후 중복 감지 및 자연어 업무 조회 기능 완성  
> **기간:** 4월 4주차 ~ 5월 2주차  
> **이전 Phase:** [phase1.md](../phases/phase1.md)  
> **다음 Phase:** [phase3.md](../phases/phase3.md)

---

## 체크리스트

### DB 연결 및 Entity 설계
- [ ] JPA Entity 설계 (`Issue`, `User`, `Project`)
- [ ] Repository 패턴 구현 (`JpaRepository` 상속)
- [ ] `application.yml` 환경별 profile 분리 (local / prod)
- [ ] HikariCP Connection Pool 기본 튜닝

### DuplicateDetectionService
- [ ] 신규 이슈 내용 → Claude API 유사도 판단 프롬프트 설계
  - 후보 전체를 Claude에 넘기지 말고 DB 필터링 후 상위 N개만 → 2-pass 설계
- [ ] 기존 이슈 DB 검색 + Claude 비교 로직
- [ ] 유사 이슈 발견 시 Slack 알림 ("비슷한 이슈 있어요: #123 — 등록하시겠어요?")
- [ ] Spring Retry 적용 (Claude / Jira API 일시 오류 자동 재시도)

### IssueQueryService
- [ ] "나 지금 뭐 해?" 자연어 파싱 → DB 조회 쿼리 매핑
- [ ] "팀원 @kim 뭐 해?" 지원 (담당자 조회)
- [ ] 조회 결과 → Claude 요약 → Slack 응답 포맷팅

---

## 리스크

| 리스크 | 영향 | 대응 |
|--------|------|------|
| N+1 문제 | Issue ↔ User 조회 시 쿼리 폭증 | `@EntityGraph` / `fetch join` 초반 설계 필수. 나중에 고치면 전체 쿼리 재검토 |
| Claude API 비용 폭증 | 이슈 수 증가 시 유사도 판단 호출 급증 | DB 키워드 필터 → 후보 N개 추출 → Claude 호출 순서로 2-pass 설계 |
| `@Transactional` 전파 실수 | Service 간 호출 시 예상 못한 롤백 | 전파 규칙 (`REQUIRED`, `REQUIRES_NEW`) 미리 정리 후 적용 |

---

## 학습 병행

- JPA / Hibernate Entity 매핑
- Repository 패턴, JPQL / `@Query`
- `@Transactional` 전파 / 롤백 규칙
- JPA 영속성 컨텍스트 / N+1 문제 해결 (`fetch join`, `@EntityGraph`)
- Spring Retry (`@Retryable`, `RetryTemplate`)
