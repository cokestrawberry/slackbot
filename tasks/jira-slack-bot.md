# Jira Slack Bot — 사이드 프로젝트

> 업데이트: 2026-04-16  

> **개발 시작: 4월 2주차 (2026-04-20)** — Spring 기초는 프로젝트 기반 학습으로 병행

---

### 공식 Jira Slack 앱과의 차별점

| 기능 | 공식 Jira 앱 | 이 프로젝트 |
|------|------------|-----------|
| 이슈 생성 | 폼 직접 입력 (수동) | 자연어 → AI가 구조화 |
| 버그/Feature 분류 | 수동 선택 | Claude API 자동 판단 |
| Story Point | 수동 입력 | 내용 기반 자동 추천 |
| 중복 검색 | 키워드 검색 수준 | 의미론적 유사도 |
| 업무 조회 | 복잡한 명령어 | "나 지금 뭐 해?" 자연어 |

---

## 핵심 기능 (MVP 범위)

| 우선순위 | 기능 | 비고 |
|---------|------|------|
| 1 | **이슈 생성** — 자연어 → Claude 분류(버그/Feature) + Story Point 추천 → Jira 등록 | 핵심 |
| 2 | **중복 이슈 감지** — 입력 시 기존 유사 이슈 검색 후 알림 | 기술적 차별점 |
| 3 | **진행 중 업무 조회** — "나 / 팀원 지금 뭐 해?" 자연어 질의 | 편의 기능 |

> 완료 처리는 공식 앱과 겹쳐 MVP 제외

---

## 아키텍처

```
[Slack]
  ↓ Event (사용자 메시지)
[Slack Bot — Go, 경량 진입점]
  ↓ HTTP
[Spring Boot Server — 핵심 두뇌]
  ├── IssueCreateService
  │     └── 자연어 → Claude API (분류/Story Point) → Jira API 등록
  ├── DuplicateDetectionService
  │     └── 새 이슈 → Claude API → 로컬 DB 유사도 비교 → 결과 반환
  ├── IssueQueryService
  │     └── 자연어 질의 → DB 조회 → 요약 응답
  ├── JiraSyncService (@Scheduled)
  │     └── 주기적으로 Jira 이슈 → PostgreSQL 동기화
  └── JPA + PostgreSQL (이슈 로컬 캐시)
       ↕                    ↕
  [Claude API]         [Jira REST API]
```

---

## 기술 스택

| 구분 | 기술 | 선택 이유 |
|------|------|---------|
| Slack Bot | Go | 기존 강점, 경량 진입점 |
| API 서버 | Java + Spring Boot 3.x | Spring Boot 학습 목적, 학습 자료 풍부 |
| DB | PostgreSQL + JPA (Hibernate) | 이슈 로컬 캐시, 빠른 조회 |
| 빌드 | Gradle | Java + Spring Boot 표준 |
| AI | Anthropic Claude API | 분류 / 유사도 판단 |
| Jira 연동 | Jira REST API v3 | 이슈 CRUD |
| 인프라 | Docker Compose | 로컬 실행 환경 |
| 트레이싱 | OpenTelemetry | 기존 경험 적용 |

### Spring Boot 핵심 기술 사용처

| Spring Boot 기술 | 적용 위치 | 이유 |
|----------------|---------|------|
| `@Scheduled` | Jira 동기화 | 주기적 이슈 동기화 |
| JPA + Entity | 이슈 저장/조회 | 빠른 중복 검색 |
| `@Async` / CompletableFuture | Slack 응답 처리 | Slack 3초 응답 제한 해결 |
| Spring Security | Slack 서명 검증 | 위변조 요청 방지 |
| WebClient | Claude / Jira API 호출 | 비동기 HTTP |
| Spring Retry | 외부 API 실패 처리 | 일시 오류 자동 재시도 |

---

## Phase별 개발 계획

| Phase | 파일 | 기간 | 목표 |
|-------|------|------|------|
| Phase 1 | [phases/phase1.md](phases/phase1.md) | 4월 2~3주차 (4/20~) | Slack Bot + Spring Boot + IssueCreateService |
| Phase 2 | [phases/phase2.md](phases/phase2.md) | 4월 4주차 ~ 5월 2주차 | DB 연결 + DuplicateDetectionService + IssueQueryService |
| Phase 3 | [phases/phase3.md](phases/phase3.md) | 5월 3주차 ~ 6월 1주차 | JiraSyncService + JPA + PostgreSQL 로컬 캐시 |
| Phase 4 | [phases/phase4.md](phases/phase4.md) | 6월 2~3주차 | 슬랙봇 통계 기능 |
| Phase 5 | [phases/phase5.md](phases/phase5.md) | 6월 4주차 ~ (점진적) | 10년차 관점: 프로덕션 수준 보강 |

> **MVP 완성 목표: 6월 말**  
> 7월부터는 카카오 기출 / 코테 실전 / 수시 지원에 집중

---

## 전체 리스크 / 부정적 측면

- **학습 부채 누적** — "learn by doing"은 핵심 개념을 우회할 위험. Bean 생명주기, 트랜잭션 전파(Propagation), AOP 등은 최소 1회 정독 필요
- **Java 감각 회복 지연** — 초반 1~2주는 생산성 매우 낮을 수 있음. Stream / Optional / Lambda 문법은 주말에 집중 복습
- **코딩 테스트와 충돌** — 저녁 시간 쪼개 쓰기 때문에 한쪽이 밀리면 다른 쪽도 밀림. 우선순위: 주중 코테 > 사이드 / 주말 사이드 집중
- **기능 욕심** — Phase 5 항목을 Phase 1~3에 끼워 넣으려 하면 일정 무너짐. MVP 우선
- **Claude API 비용** — 중복 감지(Phase 2)가 모든 이슈에 대해 호출하면 비용 폭증. 2-pass 설계(DB 필터링 후 Claude 호출) 필수

---

## Java / Spring Boot 학습 전략

### 원칙
- 기초 학습 사이클을 길게 갖지 않는다 — 필요한 개념을 필요한 시점에 학습
- 다음 개념은 **주말에 별도 시간 확보해서 반드시 한 번은 깊게** 볼 것:
  - Spring Bean 생명주기 / DI 컨테이너 동작
  - `@Transactional` 전파 / 롤백 규칙
  - Spring Security 필터 체인
  - JPA 영속성 컨텍스트 / N+1 문제

### 4월 (병행)
- Java 문법 복습 (Stream, Optional, Lambda, Generics) — 주말 2~3시간
- Spring Initializr로 프로젝트 생성하며 필요한 개념 그때그때 학습

### 5월 (병행)
- Spring Security 필터 체인 정독 (Phase 1 서명 검증 구현 전에)
- JPA 영속성 컨텍스트 정독 (Phase 2 중복 감지 구현 전에)

---

## 주의사항

- 회사 Jira 데이터 사용 → API 토큰 `.env`로 관리, 절대 커밋 금지
- MVP 완성 우선 — Phase 5는 MVP 이후 점진적 적용
