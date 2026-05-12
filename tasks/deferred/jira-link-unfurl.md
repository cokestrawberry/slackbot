# Deferred — Jira 링크 프리뷰 (unfurl)

> **등록일:** 2026-05-12
> **우선순위:** 중간
> **요청자:** 공식 봇 비교 분석

## 요구사항

Slack 채널에 Jira URL을 붙여넣으면 이슈 요약 정보를 자동 표시한다.

## 예시

```
사용자: https://your-site.atlassian.net/browse/SLAC-7 이거 확인해봐
    ↓ 자동 unfurl
┌─────────────────────────────────┐
│ 🐛 SLAC-7 로그인 500 에러       │
│ 상태: 진행 중 | 담당: 김영현     │
│ SP: 2 | 생성: 2026-04-22        │
└─────────────────────────────────┘
```

## 구현 계획

- Slack Events API `link_shared` 이벤트 구독
- Jira URL 패턴 매칭 → 이슈 키 추출
- DB 또는 Jira API로 이슈 정보 조회
- Slack `chat.unfurl` API로 Attachment/Block 전송
- Slack App 설정에서 Event Subscriptions + unfurl domains 등록 필요
