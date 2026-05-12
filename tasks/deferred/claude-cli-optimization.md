# Deferred — Claude CLI 호출 최적화

> **등록일:** 2026-04-22
> **우선순위:** Phase 3 후보
> **요청자:** 사용자

## 현재 구조

- `ClaudeApiClientImpl.java`에서 `claude -p` 서브프로세스를 매번 스폰
- 모델: `claude-sonnet-4-5`
- 시스템 프롬프트: Java 코드에 하드코딩 (`SYSTEM_PROMPT` 상수), stdin으로 전달
- CLAUDE.md: 매 호출마다 프로젝트 루트에서 자동 로드됨 (분류에 불필요한 토큰 소비)
- skill: `-p` 모드에서는 실행되지 않지만 로드 시도는 함

## 문제점

1. **불필요한 토큰 소비**: CLAUDE.md (프로젝트 지침)가 매번 로드되어 분류와 무관한 컨텍스트 포함
2. **프로세스 스폰 오버헤드**: 매 호출마다 프로세스 생성 + Claude CLI 초기화 + CLAUDE.md 탐색
3. **프롬프트 관리**: Java 코드 안에 프롬프트가 하드코딩 → 수정 시 재컴파일 필요
4. **모델 선택**: sonnet 사용 중이지만 분류 작업에는 haiku로 충분할 수 있음

## 개선 항목

### 1. `--bare` 옵션 추가
- CLAUDE.md, skill, 메모리, hook 등 자동 탐색 전부 스킵
- 분류 전용 호출에 불필요한 오버헤드 제거
- 적용: `buildCommand()`에 `--bare` 추가

### 2. 프롬프트 파일 분리
- `prompts/classify.md`에 시스템 프롬프트 작성
- `--system-prompt-file prompts/classify.md` 옵션으로 로드
- 프롬프트 수정 시 Java 재컴파일 불필요
- Few-shot 예시 추가/수정이 쉬워짐

### 3. 모델 변경 검토
- `claude-haiku-4-5`: 속도 2~3배, 비용 절감, 분류에 충분한 성능
- `claude-sonnet-4-5`: 현재 사용 중, 분류에는 과스펙일 수 있음
- 비교 테스트 후 결정 (같은 입력 10건으로 분류 정확도 비교)

### 4. 라우팅용 경량 분류 (하이브리드 C안 확장)
- 현재: 키워드 매칭 실패 시 Claude로 이슈 분류
- 개선: 키워드 매칭 실패 시 먼저 "의도 분류"용 경량 호출 (haiku)
  - 이슈 생성 / 조회 / SKIP 판단만 (제목/SP 추정 없이)
  - 이슈 생성이면 sonnet으로 2차 호출 (제목/SP/분류)

## 영향 범위

- `ClaudeApiClientImpl.java` — buildCommand() 수정, SYSTEM_PROMPT 외부화
- `application.yml` — claude 설정 섹션 확장
- `prompts/classify.md` — 새 파일 생성
