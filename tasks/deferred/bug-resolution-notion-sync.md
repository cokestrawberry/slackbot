# Deferred — 버그 완료 시 Notion 자동 정리

> **등록일:** 2026-04-23
> **우선순위:** Phase 4 후보
> **요청자:** 사용자

## 요구사항

버그 이슈가 "완료"로 전환되면, 해당 버그의 원인과 해결 방법을 특정 Notion 페이지에 자동으로 정리한다.

## 예시 흐름

```
@지라봇 완료 (스레드에서)
    ↓
Jira 상태 → 완료 전환
    ↓
Claude에게 Jira 이슈 내용 전달 → 원인/해결방법 요약 생성
    ↓
Notion API로 지정된 페이지에 추가
    ↓
Slack 스레드에 알림: "✅ SLAC-7 완료 + Notion에 정리되었습니다"
```

## Notion 페이지 구조 (안)

| 이슈 | 날짜 | 원인 | 해결 방법 | Jira 링크 |
|---|---|---|---|---|
| SLAC-7 로그인 500 에러 | 2026-04-22 | 세션 토큰 만료 미처리 | 토큰 갱신 로직 추가 | 링크 |

## 구현 계획

### 1. Notion API 연동
- Notion Integration 생성 + API Token 발급
- 대상 페이지/데이터베이스 ID 설정 (`application.yml`)
- `NotionApiClient` 인터페이스 + 구현체

### 2. 버그 요약 생성
- 완료 전환 시 Jira 이슈 설명 + 댓글을 Claude에 전달
- 원인(root cause)과 해결 방법(fix)을 구조화된 형태로 요약
- 프롬프트: "이 버그의 원인과 해결 방법을 각각 1-2문장으로 요약해줘"

### 3. 트리거
- `@지라봇 완료` 시 이슈 타입이 "버그"면 Notion 정리 실행
- 또는 Jira Webhook으로 상태 변경 감지 (운영 환경)

### 영향 범위
- `NotionApiClient` (새 파일) — Notion API 호출
- `SlackEventController` — 완료 처리 후 Notion 정리 분기
- `application.yml` — Notion 설정 추가
- `.env` — `NOTION_API_TOKEN`, `NOTION_DATABASE_ID`

### 선행 조건
- Notion Integration 생성 및 페이지 공유
- Notion API Token 발급
- 대상 데이터베이스 구조 확정
