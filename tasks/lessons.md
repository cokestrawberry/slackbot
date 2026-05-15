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

---

## L5 — Gradle 시스템 프로퍼티는 test 워커 JVM 에 자동 전파되지 않는다

**Context**: 평가용 통합 테스트를 `@EnabledIfSystemProperty(named = "intent.eval", matches = "true")` 로 격리했는데, `./gradlew test -Dintent.eval=true` 를 줘도 테스트가 계속 SKIPPED 로 떨어짐. Gradle 호스트 JVM 만 -D 를 받고 test executor 워커 JVM 에는 전달 안 됨.

**Mistake**: "JUnit 표준 어노테이션이라 build.gradle 손 안 댐" 으로 plan 을 짠 것. JUnit 의 SystemProperty 조건은 워커 JVM 의 System.getProperty 만 봄.

**Rule**: `@EnabledIfSystemProperty` / `@EnabledIfEnvironmentVariable` 같은 JUnit 조건 어노테이션과 Gradle CLI 옵션을 연결하려면 build.gradle 의 test 태스크에 명시적 forward 필요.

```groovy
tasks.named('test') {
    useJUnitPlatform()
    systemProperty 'intent.eval', System.getProperty('intent.eval', 'false')
}
```

**Why**: Gradle 의 fork 된 워커 JVM 은 host JVM 의 system property 를 상속하지 않음 (격리 + 재현성). 환경 변수는 inherit 되지만 -D 시스템 프로퍼티는 명시 forward 필요.

**How to apply**: 비용 드는 통합 테스트 (실 LLM 호출, 외부 API 호출 등) 를 격리할 때 다음 두 가지 중 택일.
1. **`@EnabledIfEnvironmentVariable`** + `INTENT_EVAL=true ./gradlew test ...` — env var 는 child process inherit 되어 build.gradle 변경 불요.
2. **`@EnabledIfSystemProperty`** + build.gradle 에 `systemProperty` 한 줄 — 더 Gradle 다운 관용적 패턴.

선택 기준: 다른 빌드 설정과 일관성 우선이면 (1) env var 가 더 잘 어울리는 프로젝트가 많음. CI 매트릭스에서 -D 로 통제하는 게 자연스러우면 (2).

---

## L6 — LLM 분류기에서 매우 짧은 입력은 분류 대상이 아니라 직접 인사로 해석된다

**Context**: Haiku intent classifier 평가셋(99건) 첫 실행에서 정확도 0.848. 실패 15건 중 10건이 `confidence=0.0` fallback (시스템 에러 경로). 직접 CLI 재현 결과 "안녕하세요" / "thanks" / "ok" 등 매우 짧은 입력에서 모델이 JSON 대신 **"안녕하세요. 저는 Jira Slack 봇의 의도 분류기로 동작하는 Claude 에이전트입니다..." 같은 대화 응답** 반환.

**Mistake**: 시스템 프롬프트에 "Respond with ONLY valid JSON — no preamble" 을 넣어두는 것만으로 충분하다고 가정. 모델은 짧고 인사로 보이는 user 입력을 "분류 대상" 이 아니라 "시스템에 대한 직접 인사" 로 받음.

**Rule**: 외부 LLM 을 분류기로 쓸 때 다음 셋을 함께 적용해야 짧은 입력에서도 안정 분류.

1. **입력 프레이밍 (코드 단)**: user 메시지에 `"Classify this user message: "` 같은 명시적 프리픽스. stdin 한 줄짜리 입력이라도 분류 대상임이 자체 명시되어야 함.
2. **출력 규칙 강화 (시스템 프롬프트 단)**: "Strict Output Rules" 섹션을 따로 두고 (a) 인사·짧은 입력에도 JSON 만 (b) 마크다운 펜스 금지 (c) preamble/explanation/follow-up question 금지를 모두 명시.
3. **edge-case few-shot**: greetings ("안녕하세요" → skip), chit-chat ("점심 뭐 먹지" → unknown), 영어 입력 ("my tasks please" → my_tasks) 등 약한 경계 케이스의 expected JSON 예시를 system prompt 에 직접 박아둠.

**Why**: LLM 의 instruction following 은 입력 길이와 명확성에 비례. 짧은 user 입력 + 강한 일반 지시 조합에선 모델이 입력을 "사용자 발화" 보다 "시스템 인사" 로 오인하기 쉬움. 코드 단 프레이밍이 가장 robust (모델 변경/시스템 프롬프트 캐시 무효화에도 영향 안 받음).

**How to apply**: LLM 분류기를 도입하거나 회귀 추적할 때 평가셋에 다음 패턴을 반드시 포함.
- 한 단어 영어 입력 (`"ok"`, `"thanks"`)
- 한 단어/짧은 한국어 인사 (`"안녕하세요"`, `"감사"`)
- 분류기에 직접 묻는 듯한 형태 (`"뭐 해야 해?"` 1인칭 암시 + 짧음)
- 분류기 사용자가 평소 안 보낼 잡담 (`"점심 뭐 먹지"`) — skip vs unknown 경계 검증

이 패턴이 fallback (`confidence=0`) 으로 떨어지면 모델 정확도 문제가 아니라 **프레이밍 결함**. 코드 단 프리픽스부터 적용 후 prompt 강화.

**Side note**: Haiku 호출이 정상 6~8s 인데 일부 입력(예: `"이번 스프린트 완료된 SP 몇 점이야"`)에서 20s+ outlier 관찰됨. timeout default 는 typical latency 의 **3배 이상** 으로 잡아야 outlier 가 시스템 에러로 떨어지지 않음 (이번에 15s → 25s).
