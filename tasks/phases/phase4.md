# Phase 4 — 슬랙봇 통계 기능

> **목표:** 팀 워크플로우 가시성 향상을 위한 자동 알림 / 통계 기능  
> **기간:** 6월 2주차 ~ 6월 3주차  
> **이전 Phase:** [phase3.md](../phases/phase3.md)  
> **다음 Phase:** [phase5.md](../phases/phase5.md)

---

## 체크리스트

### 일일 브리핑 — 어제 추가된 이슈 소개
- [ ] 매일 오전 9시 Slack 채널에 전날 등록된 이슈 목록 게시 (`@Scheduled`)
- [ ] 이슈 타입별(버그/Feature) 집계 포함
- [ ] Claude 요약: "어제 총 N개 이슈 — 버그 X, Feature Y. 주요: ..."

### 방치 이슈 알림 — In-Progress 오래된 이슈
- [ ] In-Progress 상태가 N일(기본 7일) 이상인 이슈 감지
- [ ] 담당자에게 Slack DM 또는 채널 멘션 알림
- [ ] 임계일 설정 외부화 (`application.yml` 또는 Slack 명령어로 조정 가능하게)

### 추가 통계 기능
- [ ] `/stats` 명령어: 이번 주 이슈 현황 (담당자별 / 상태별 카운트)
- [ ] `/overdue` 명령어: 마감일 초과 이슈 목록
- [ ] 주간 리포트 (매주 금요일 오후 6시 자동 게시)

---

## 리스크

| 리스크 | 영향 | 대응 |
|--------|------|------|
| 알림 피로 (alert fatigue) | 알림 무시 → 봇 사용 중단 | 채널별 알림 빈도 조절 옵션 처음부터 설계 |
| 통계 쿼리 풀스캔 | 응답 느림, DB 부하 | Phase 3에서 설계한 인덱스 활용 여부 EXPLAIN으로 검증 |
| Slack Block Kit 복잡도 | 메시지 빌더 코드 난잡 | 재사용 가능한 SlackMessageBuilder 유틸 클래스 분리 |

---

## 학습 병행

- Slack Block Kit (Section, Header, Divider 블록 구성)
- `@Scheduled` cron 표현식 고급 패턴
- PostgreSQL EXPLAIN / EXPLAIN ANALYZE (쿼리 성능 검증)
