# Phase 5 — 10년차 관점: 프로덕션 수준 보강

> **목표:** "돌아가는 프로젝트"에서 "신뢰할 수 있는 서비스"로 격상  
> **기간:** 6월 4주차 ~ (점진적 적용)  
> **이전 Phase:** [phase4.md](../phases/phase4.md)  
> **주의:** MVP 완성 전 이 항목에 손대지 말 것 — 일정 무너짐

---

## 보안 — Slack 유저 정당성 검증

Phase 1의 Slack 서명 검증은 **"Slack에서 온 요청"**임을 확인할 뿐,  
**"누가" 보냈는지**는 검증하지 않는다. 운영 환경에서는 아래가 필요하다.

### 체크리스트

- [ ] **허용 유저 화이트리스트**
  - `SlackUser` Entity: Slack User ID ↔ Jira 계정 매핑
  - 미등록 Slack 유저의 이슈 생성 / 조회 요청 차단
  - 등록 방법: 슬랙 명령어 `/register` 또는 관리자 수동 등록

- [ ] **Role 기반 권한 분리**
  - `ROLE_USER`: 이슈 생성, 본인 업무 조회
  - `ROLE_ADMIN`: 타 유저 통계 조회, 알림 설정 변경, 방치 이슈 임계값 조정
  - Spring Security `@PreAuthorize` 또는 커스텀 인터셉터 적용

- [ ] **Rate Limiting** (Claude API 비용 폭증 방지)
  - Bucket4j 적용 — 유저별 분당 요청 수 제한
  - 초과 시 Slack 응답: "요청이 너무 많아요. 1분 후 다시 시도해주세요."

- [ ] **Replay Attack 방지**
  - `X-Slack-Request-Timestamp` 검증: 현재 시각 기준 5분 이내 요청만 수락
  - Phase 1 서명 검증에 타임스탬프 유효성 체크 추가

---

## Spring Boot 서비스 수준 구현

### 체크리스트

- [ ] **Spring Actuator**
  - `/actuator/health`, `/actuator/metrics`, `/actuator/info` 노출
  - DB / Jira API / Claude API 커스텀 `HealthIndicator` 등록
  - 외부 노출 범위 제한 (`management.endpoints.web.exposure.include`)

- [ ] **Graceful Shutdown**
  - `server.shutdown=graceful` 설정
  - 진행 중인 Slack 요청 완료 후 종료 (특히 `@Async` 스레드 고려)

- [ ] **Circuit Breaker — Resilience4j**
  - Claude API / Jira API 장애 시 빠른 실패(fail-fast) + fallback 응답
  - fallback: "지금 Jira 연결이 불안정해요. 잠시 후 다시 시도해주세요."
  - Half-Open 상태 복구 전략 설정

- [ ] **에러 응답 표준화 (`@ControllerAdvice`)**
  - 예외 종류별 Slack 친화적 메시지 포맷 통일
  - 스택트레이스 외부 노출 금지 (로그에만 기록)

- [ ] **Config 관리**
  - `application-local.yml` / `application-prod.yml` profile 분리 확인
  - Secret(API Key, DB 비밀번호)은 환경변수로 분리 — `.env` 커밋 절대 금지
  - 민감 설정 목록 README에 문서화

- [ ] **HikariCP 튜닝**
  - `maximumPoolSize`, `connectionTimeout`, `idleTimeout` 워크로드 기반 조정
  - 동시 슬랙 요청 수 예측 후 설정값 산정

- [ ] **테스트**
  - Unit: JUnit5 + Mockito — Service 레이어 핵심 로직 (분류 로직, 중복 감지 로직)
  - Integration: MockMvc — Controller 요청/응답 검증
  - Integration: Testcontainers — 실제 PostgreSQL 기반 Repository 테스트
  - Slack 서명 검증 필터 단위 테스트 별도 작성

- [ ] **OpenTelemetry 트레이싱**
  - Slack 요청 → Spring Boot → Claude API → Jira API 전 구간 `trace_id` 연결
  - 기존 Go Bot에도 동일 `trace_id` 전파 (HTTP 헤더 전달)

- [ ] **README / 포트폴리오 정비**
  - 아키텍처 다이어그램 포함
  - 설계 결정 근거 (왜 `@Async`인지, 왜 2-pass 중복 감지인지) 기술
  - GitHub public repo 공개

---

## 면접 포인트 (이 Phase에서 얻는 것)

> "처음부터 팀 확장을 전제로 설계했습니다. Slack 서명 검증만으로는 내부 악의적 사용자를 막을 수 없어서 유저 화이트리스트와 Role 기반 권한을 추가했습니다. Claude API는 호출당 비용이 발생하므로 Rate Limiting과 Circuit Breaker로 비용 폭증과 장애 전파를 방지했습니다. Testcontainers로 실제 DB 기반 통합 테스트를 작성해서 Mock/실제 DB 불일치 문제를 사전 차단했습니다."

---

## 리스크 / 한계

| 리스크 | 영향 | 대응 |
|--------|------|------|
| MVP 전 Phase 5 항목 착수 | 6월 말 완성 불가 | Phase 5는 체크리스트 준비만. 손은 MVP 완성 후 |
| Rate Limiting 임계값 과엄격 | 정상 사용도 차단 | 처음엔 넉넉하게 설정, 실사용 후 튜닝 |
| Testcontainers CI 설정 복잡 | 로컬은 되는데 CI에서 실패 | Docker-in-Docker 또는 GitHub Actions `services` 설정 필요 |
| Circuit Breaker 임계값 부정확 | 정상 트래픽에서 오픈 → 기능 중단 | 슬로우 콜 기준 / 실패율 기준 분리 설정 후 충분한 테스트 |
