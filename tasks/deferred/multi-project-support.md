# Deferred — 멀티 프로젝트 지원

> **등록일:** 2026-05-12
> **우선순위:** 중간
> **요청자:** 공식 봇 비교 분석

## 요구사항

채널별로 다른 Jira 프로젝트에 이슈를 생성할 수 있도록 한다.
현재는 `JIRA_PROJECT_KEY=SLAC` 하나만 지원.

## 예시

```
#frontend 채널 → FRONT 프로젝트에 이슈 생성
#backend 채널  → BACK 프로젝트에 이슈 생성
#general 채널  → SLAC 프로젝트 (기본값)
```

## 구현 계획

- `channel_project_mappings` DB 테이블 생성
- 매핑 등록: `@지라봇 프로젝트 설정 FRONT` 또는 scripts로 등록
- 이슈 생성 시 채널 ID → 프로젝트 키 조회
- 매핑 없으면 기본 프로젝트 사용 (application.yml의 JIRA_PROJECT_KEY)

## 영향 범위

- `JiraApiClientImpl` — projectKey를 동적으로 받도록 변경
- `IssueCreateServiceImpl` — 채널 기반 프로젝트 키 조회
- 새 Entity/Repository — ChannelProjectMapping
