# Haiku Intent Classifier — 설계 문서

> **작성일:** 2026-04-24
> **상태:** 승인 대기

## 목표

`@봇더지라` 메시지에 대해 Haiku 모델로 빠른 1차 의도 분류를 수행하고, 분류 결과에 따라 적절한 후속 처리로 라우팅한다. 기존 Sonnet 기반 이슈 분류는 그대로 유지하되, Haiku 의도를 힌트로 전달하여 정확도를 높인다.

## 결정 사항

| 항목 | 결정 |
|---|---|
| 키워드 명령 | 기존 유지 (help, scrum, 내작업, sync, 완료, 작업), Haiku 안 거침 |
| Haiku 호출 방식 | `claude -p --model claude-haiku-4-5` (CLI 서브프로세스) |
| 프롬프트 전달 | `--bare` + `--system-prompt-file` (토큰 최적화) |
| 아키텍처 | IntentClassifier를 별도 컴포넌트로 분리 (A안) |
| Sonnet 연동 | Haiku intent를 힌트로 전달 |
| Haiku 실패 시 | DB에 저장 + 안내 메시지 반환, Sonnet 호출 안 함 |

## 전체 흐름

```
@봇더지라 {메시지}
    ↓
SlackEventController.routeCommand()
    ↓
[1차] 키워드 매칭 (0ms)
    help, scrum, 내작업, sync, 완료, 작업 → 즉시 실행
    ↓ 매칭 실패
[2차] IntentClassifier.classify() — Haiku (~300-600ms)
    claude -p --bare --system-prompt-file prompts/haiku-classifier.md
             --model claude-haiku-4-5 --max-turns 1 --output-format json
    ↓ intent JSON 반환
[3차] intent별 분기
    register_bug   → IssueCreateService (Sonnet + 힌트 → Jira 생성)
    register_story → IssueCreateService (Sonnet + 힌트 → Jira 생성)
    search         → 검색 로직 (추후 구현, 현재 안내 메시지)
    statistics     → 통계 로직 (추후 구현, 현재 안내 메시지)
    unknown        → DB 저장 + "이해 못했어요" 반환
    confidence<0.6 → DB 저장 + "이해 못했어요" 반환
    실패           → DB 저장 + "일시적 오류" 반환
```

## 파일 구조

### 신규 파일

```
prompts/haiku-classifier.md                           ← Haiku skill 프롬프트
src/.../client/IntentClassifier.java                  ← 인터페이스
src/.../client/IntentClassifierImpl.java              ← Haiku CLI 호출 구현
src/.../client/dto/IntentResult.java                  ← intent JSON DTO
src/.../entity/IntentFailureEntity.java               ← 실패 로그 Entity
src/.../repository/IntentFailureRepository.java       ← 실패 로그 Repository
```

### 수정 파일

```
SlackEventController.java    ← routeCommand() 키워드 실패 시 IntentClassifier 호출
ClaudeApiClient.java         ← classify(text, intentHint) 오버로드 추가
ClaudeApiClientImpl.java     ← 힌트를 stdin에 포함하는 로직
application.yml              ← claude.intent 설정 추가
```

## 컴포넌트 상세

### IntentResult DTO

```java
public record IntentResult(
    String intent,                // "search", "register_bug", "register_story", "statistics", "unknown"
    double confidence,            // 0.0 ~ 1.0
    Map<String, String> extracted, // keyword, project, priority (옵션)
    String rawInput               // 원본 메시지
) {}
```

### IntentClassifierImpl

- `ProcessRunner` 재사용 (기존 ClaudeApiClient와 동일한 인프라)
- CLI 명령: `claude -p --bare --system-prompt-file prompts/haiku-classifier.md --model claude-haiku-4-5 --max-turns 1 --output-format json`
- stdin: 사용자 메시지만 전달 (시스템 프롬프트는 파일로)
- 응답: JSON envelope에서 result 추출 → IntentResult 파싱
- 실패 시: IntentResult(unknown, 0, {}, rawInput) 반환

### IntentFailureEntity

```java
@Entity
@Table(name = "intent_failures")
public class IntentFailureEntity {
    Long id;
    String rawInput;        // 원본 메시지
    String errorType;       // TIMEOUT, CLI_ERROR, PARSE_ERROR, EMPTY_RESPONSE, LOW_CONFIDENCE, UNKNOWN_INTENT
    String errorDetail;     // 에러 메시지, raw stdout, intent+confidence 등
    String slackUserId;
    String slackChannel;
    Instant failedAt;
}
```

### application.yml 추가

```yaml
claude:
  # 기존 Sonnet (상세 분류용)
  cli-path: claude
  model: claude-sonnet-4-5
  timeout-seconds: 60
  permission-mode: plan
  max-turns: 1

  # 신규 Haiku (의도 분류용)
  intent:
    model: claude-haiku-4-5
    timeout-seconds: 15
    prompt-file: prompts/haiku-classifier.md
```

## Sonnet 힌트 전달

### ClaudeApiClient 변경

```java
// 기존 유지 (하위 호환)
IssueClassification classify(String rawText);

// 신규 — Haiku 힌트 포함
IssueClassification classify(String rawText, IntentResult intentHint);
```

### stdin 구성 변경

```
기존:
  SYSTEM_PROMPT + "\n\n---\nUSER INPUT:\n" + rawText

변경 (힌트 있을 때):
  SYSTEM_PROMPT + "\n\n---\nINTENT HINT: register_bug (confidence: 0.95)\n---\nUSER INPUT:\n" + rawText
```

### SYSTEM_PROMPT 규칙 추가

```
- An INTENT HINT may be provided above the user input.
  Use it as a strong signal but override if the text clearly contradicts it.
  For example, if hint says register_bug but the text is clearly a feature request, classify as FEATURE.
```

## 에러 처리

| 상황 | 처리 | DB 저장 | Sonnet 호출 |
|---|---|---|---|
| Haiku 정상 (bug/story) | intent별 라우팅 | ❌ | ✅ 힌트 전달 |
| Haiku 정상 (search/statistics) | 해당 로직 (추후 구현) | ❌ | ❌ |
| unknown intent | "이해 못했어요" 반환 | ✅ UNKNOWN_INTENT | ❌ |
| confidence < 0.6 | "이해 못했어요" 반환 | ✅ LOW_CONFIDENCE | ❌ |
| CLI 타임아웃 | "일시적 오류" 반환 | ✅ TIMEOUT | ❌ |
| CLI 비정상 종료 | "일시적 오류" 반환 | ✅ CLI_ERROR | ❌ |
| JSON 파싱 실패 | "일시적 오류" 반환 | ✅ PARSE_ERROR | ❌ |
| 빈 응답 | "일시적 오류" 반환 | ✅ EMPTY_RESPONSE | ❌ |

**핵심: Haiku 실패/unknown 시 Sonnet 호출하지 않고 즉시 사용자에게 안내 반환.**

## Haiku Skill 프롬프트

`prompts/haiku-classifier.md`에 저장. 내용은 사용자가 제공한 `jira-intent-classifier` skill 그대로 사용.

### Intent 정의

| intent | 트리거 키워드 |
|---|---|
| search | 찾아, 검색, 조회, 있어?, find, show, list |
| register_story | 스토리, 기능 추가, 만들어 (에러 컨텍스트 없이) |
| register_bug | 버그, 에러, 오류, 안 돼, 깨짐, crash |
| statistics | 통계, 몇 개, 현황, 집계, stats |
| unknown | 위 어디에도 해당하지 않음 |

**Disambiguation rule:** 기능과 에러 신호가 동시에 있으면 `register_bug` 우선.

## 성능 기대치

| 항목 | 값 |
|---|---|
| Haiku 응답 시간 | ~300-600ms |
| Sonnet 응답 시간 | ~20-30초 (기존과 동일) |
| 전체 지연 (bug/story) | Haiku + Sonnet = ~21-31초 |
| 전체 지연 (unknown) | Haiku만 = ~300-600ms |
| 토큰 소비 (Haiku) | 입력 ~250 + 출력 ~80 |

## 추후 확장

- search intent 구현 → DB/Jira 검색
- statistics intent 구현 → DB 집계
- 각 intent별 Sonnet skill 파일 분리 (`prompts/sonnet-bug.md`, `prompts/sonnet-story.md`)
- confidence 임계값 튜닝 (실패 DB 분석 기반)
- 새 intent 추가 시 haiku-classifier.md만 수정
