# Lessons

## L1 — `@Async` fire-and-forget 에 `CallerRunsPolicy` 금지

**Context**: Task #6 (AsyncConfig). Slack 3초 ack 제약이 있는 fire-and-forget 비동기 경로.

**Mistake**: pool+queue 포화 시 `CallerRunsPolicy` 를 선택. "조용한 누락보다 낫다"는 논리였으나 실제 영향은 "tomcat http-nio 스레드가 task 완료까지 블록 → `ResponseEntity.ok()` 지연 → Slack 3초 내 ack 실패 → 재전송 → 이슈 중복 생성".

**Rule**:
- Slack/webhook 처럼 외부가 timeout 후 재시도하는 계약에서 `@Async` 내부 executor 는 `AbortPolicy` 또는 커스텀 reject handler 사용.
- `AsyncConfigurer.getAsyncUncaughtExceptionHandler()` override 해서 `RejectedExecutionException` 을 warn 로그로 흡수.
- 포화는 "조용히 버리고 가시화" — caller 스레드 블록으로 ack 계약을 깨면 더 비싼 중복을 만든다.

**How to apply**: 외부 webhook 을 받아 비동기 처리하는 모든 ThreadPoolTaskExecutor 설정 시 rejection policy 선택을 먼저 고민. CPU 작업 내부 파이프라인일 때만 CallerRunsPolicy 후보.

---

## L2 — Verification Before Done: 환경 제약은 blocker 로 보고, completed 로 닫지 말 것

**Context**: Task #6 완료 보고. JDK 17 미설치로 `./gradlew test` 실행 불가능. 정적 리뷰만으로 completed 표시.

**Mistake**: "환경 복구 후 실패 시 대응" 이라는 소급 약속으로 completed 마킹. 10년차 관점에서는 반-패턴.

**Rule**:
- 테스트가 실행 불가능한 환경 이슈 = 작업의 Definition-of-Done 미충족.
- `in_progress` 로 유지 + blocker 를 team-lead 에게 명시적으로 보고.
- team-lead 가 환경 복구 후 테스트 통과 확인 → team-lead 가 completed 마킹.

**How to apply**: task 완료 시점 checklist — (1) 코드 리뷰 (2) 로컬 테스트 실행 성공 (3) 관련 문서/contract 반영. (2) 가 환경으로 막히면 task 는 열어두고 blocker 로 넘긴다.

---

## L3 — 외부 프로세스 호출은 얇은 interface 뒤에 둔다

**Context**: Anthropic HTTP API → `claude -p` CLI 서브프로세스 전환. `ClaudeApiClientImpl` 이 `ProcessBuilder` 를 직접 사용하면 단위 테스트가 OS 바이너리(`/bin/true`, 특정 버전의 `claude`)에 의존 → CI 환경별로 결과가 달라짐.

**Rule**: 외부 프로세스 호출은 반드시 얇은 interface (예: `ProcessRunner`) 뒤에 둔다. 구현체 하나(`DefaultProcessRunner`)는 실제 `ProcessBuilder` 를 쓰고, 테스트에서는 Mockito mock 을 주입한다.

**Why**: 직접 `ProcessBuilder` 호출은 테스트에서 OS-specific 바이너리 존재에 의존 → CI flaky. 또한 stdout/stderr 드레인, 타임아웃, 자식 프로세스 정리 같은 반복 로직을 한 곳에 모을 수 있다.

**How to apply**: 로컬 CLI 통합을 제안하기 전에 다음 3가지를 먼저 확인한다.
1. **배포 환경**: 바이너리가 실제로 실행 경로에 배치되는가? (컨테이너/CI 이미지 포함)
2. **인증 수명**: 세션/토큰의 만료/회전 주체가 누구인가? (사용자? 배포 스크립트?)
3. **스폰 latency**: 서브프로세스 스폰 + 언어 런타임 init 을 응답 SLA 가 수용할 수 있는가?
위 3가지가 모두 녹색이면 interface + 기본 구현 + Mockito 테스트 패턴으로 채택.

---

## L4 — UI 표시명(localized) ≠ 저장된 name. API 관점에서는 저장된 name 이 진실

**Context**: Jira Cloud Team-managed 프로젝트. 사용자가 UI 에서 이슈 타입을 "Task/Bug/Story" 로 본다고 말했으나, `/rest/api/3/project/{KEY}` 호출 결과 `issueTypes` 의 `.name` 이 한글 "작업/버그/스토리" 로 반환됨.

**Mistake**: 사용자 UI 보고를 그대로 받아들여 봇이 "Task"/"Bug"/"Story" 를 Jira 로 보내게 두면 400 `issuetype ... does not exist`. UI 는 사용자 언어 설정에 따라 자동 번역 레이어를 씌우지만, **저장소의 실제 `name` 은 사이트 생성 시점 언어**로 고정됨.

**Rule**:
- Jira/Atlassian API 로 이슈 타입·상태·우선순위·필드명을 보낼 때는 **반드시 REST API 응답의 raw `name` 을 기준**으로 매핑.
- 사용자 스크린샷/구두 보고에 "영어로 보인다" 는 답변이 나와도 UI 착시 가능성을 의심. `curl ... /project/{KEY}` 한 번이 진실.
- Team-managed (next-gen) 프로젝트는 `PUT /rest/api/3/issuetype/{id}` 리네임 불가 (Jira 가 400 "전역 이슈 유형이 아님" 으로 거절). 수정 불가 상황에서는 **봇 코드에서 매핑**이 최소 변경이자 회복 탄력성 높음.

**Why**: 표시명과 저장명의 분리는 i18n 이 있는 모든 SaaS 에서 공통 패턴 (Jira, Linear, Salesforce 등). API 호환성은 "로컬화된 UI" 가 아니라 "저장된 식별자/이름" 에만 성립함.

**How to apply**:
1. 외부 SaaS 와의 enum/name 매핑 작업이 생기면 **UI 스크린샷 대신 API 응답을 1순위 근거**로.
2. 사용자가 "영어로 보인다/한국어로 보인다" 라고 할 때 API raw 결과로 교차 확인 후 진행.
3. 저장된 name 이 바꾸기 어려운 제약이면 (예: Team-managed 리네임 불가, 레거시 시스템) 자기 코드 매핑이 가장 저렴한 해결책. UI 변경 협상은 비용이 크고 깨지기 쉬움.
