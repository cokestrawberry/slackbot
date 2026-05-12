# Deferred — 슬래시 커맨드 지원

> **등록일:** 2026-05-12
> **우선순위:** 낮음
> **요청자:** 공식 봇 비교 분석

## 요구사항

`/jirabot create`, `/jirabot search` 같은 슬래시 커맨드 지원.
현재는 `@지라봇` 멘션 방식만 가능.

## 구현 계획

- Slack App → Slash Commands에 `/jirabot` 등록
- Request URL: Go Bot 또는 Spring Boot 직접 수신
- 슬래시 커맨드는 별도 payload 형식 (Events API와 다름)
- Spring에 `/api/slack/command` 엔드포인트 추가
- 기존 routeCommand 로직 재사용 가능

## 고려사항

- 슬래시 커맨드는 3초 내 응답 필수 (비동기 처리 시 `response_url`로 후속 응답)
- 멘션 방식과 병행 가능 (둘 다 지원)
