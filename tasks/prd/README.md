# PRD 목록

| # | 기능 | 우선순위 | 난이도 | 상태 | 후속 TODO | 파일 |
|---|------|---------|--------|------|-----------|------|
| 01 | 이슈 검색 (`@지라 검색`) | 높음 | 낮음 | ✅ 머지 완료 ([#2](https://github.com/Kim-YeongHyeon/slackbot/pull/2)) | ⚠️ `findAll()` 확장성, JPQL LIMIT, 루트 프롬프트 정리 → [상세](01-issue-search.md#7-todo--후속-개선) | [01-issue-search.md](01-issue-search.md) |
| 02 | Jira 변경 알림 | 높음 | 중간 | ⬚ 미착수 | — | [02-jira-change-notification.md](02-jira-change-notification.md) |
| 03 | Slack 버튼 UI | 중간 | 중간 | ⬚ 미착수 | — | [03-slack-interactive-buttons.md](03-slack-interactive-buttons.md) |
| 04 | 해결된 버그 조회 (트러블슈팅) | 중간 | 낮음~중간 | ⬚ 미착수 | — | [04-bug-troubleshooting-query.md](04-bug-troubleshooting-query.md) |
| 05 | 스프린트 통계 (`@지라 통계`) | 중간 | 중간 | ⬚ 미착수 | — | [05-sprint-statistics.md](05-sprint-statistics.md) |

## 상태 범례

| 아이콘 | 의미 |
|--------|------|
| ⬚ | 미착수 |
| 🔨 | 구현 중 |
| ✅ | PR 생성 / 머지 완료 |

## 사용법

- PRD 추가 시 번호를 순서대로 부여하고 이 표에 행 추가
- 상태가 변경될 때마다 이 표 업데이트
- 후속 TODO가 있으면 각 PRD 파일 하단 섹션에 상세 기록, 이 표에는 요약만 기재
