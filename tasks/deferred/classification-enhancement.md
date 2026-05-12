# Deferred — Claude 분류 고도화

> **등록일:** 2026-04-22
> **우선순위:** Phase 2 핵심
> **요청자:** 사용자

## 배경

현재 분류는 BUG/FEATURE/OTHER 3가지만 존재.
`@봇더지라 고마워~` 같은 비업무 메시지도 OTHER로 분류되어 Jira 이슈가 생성됨.
프롬프트 위치: `ClaudeApiClientImpl.java:23-58` SYSTEM_PROMPT 상수.

## 개선 항목

### 1. SKIP 분류 추가
- 인사, 감사, 잡담, 질문 등 이슈화 불필요한 메시지 → `SKIP` 반환
- Spring에서 SKIP이면 Jira 생성 건너뛰고 Slack에 안내 메시지 전송
  (예: "이슈로 등록할 내용이 아닌 것 같아요. 버그나 기능 요청을 작성해주세요!")

### 2. Story Point 사용자 지정
- `@봇더지라 로그인 에러 / 3일 걸림 예상` 형태로 사용자가 직접 SP 힌트 제공
- Claude가 사용자 힌트를 참고하되 자체 판단과 조합
- 파싱: 프롬프트에서 처리 vs Spring에서 전처리 후 별도 파라미터로 전달

### 3. 하위 작업(Sub-task) 자동 생성
- 큰 이슈를 Claude가 판단하여 하위 작업으로 분해
- Jira API `POST /rest/api/3/issue` + `parent` 필드로 sub-task 생성
- 이슈 타입: "하위 작업" (한글 매핑 필요)

### 4. 분류 정확도 개선
- Few-shot 예시 확대 (현재 5개 → 10개+)
- SKIP 케이스 예시 추가 ("고마워", "ㅋㅋㅋ", "알겠어", "회의 몇시야?" 등)
- 실사용 데이터 기반으로 프롬프트 반복 튜닝

## 영향 범위

- `ClaudeApiClientImpl.java` — SYSTEM_PROMPT 수정
- `IssueClassification.java` — IssueType enum에 SKIP 추가
- `IssueCreateServiceImpl.java` — SKIP 시 분기 처리
- `SlackEventController.java` — 사용자 힌트 파싱 (SP 지정 시)
- `JiraApiClientImpl.java` — sub-task 생성 로직 추가
