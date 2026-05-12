# Deferred — 중복 감지 정확도 최적화

> **등록일:** 2026-04-22
> **우선순위:** Phase 3 후보
> **요청자:** 사용자

## 현재 방식

키워드 기반 DB LIKE 검색 + 최소 2개 키워드 매칭 필터.
`DuplicateDetectionServiceImpl.java` 참조.

## 개선 방향

### 1. 불용어 확장
- 현재 30개 수준 → 한국어 불용어 사전 100개+ 확대
- "페이지", "시스템", "서비스" 등 도메인 범용어도 가중치 낮추기

### 2. 가중치 기반 스코어링
- 단순 키워드 수 카운트 → TF-IDF 또는 키워드 희귀도 기반 가중치
- "로그인"(희귀) vs "페이지"(범용)에 다른 가중치 부여

### 3. PostgreSQL 전문 검색 (Full-Text Search)
- `tsvector` / `tsquery`로 한국어 형태소 분석
- pg_bigm 또는 pgroonga 확장으로 한국어 지원 강화

### 4. 임베딩 기반 유사도 (고급)
- 이슈 제목을 임베딩 벡터로 변환하여 DB에 저장
- pgvector 확장으로 cosine similarity 검색
- Claude CLI 또는 외부 임베딩 API 활용

### 5. Claude 2-pass 비교 (Phase 2 원안)
- DB 키워드 필터로 상위 N개 후보 추출 (현재)
- 후보를 Claude에 넘겨 실제 의미적 유사도 판단 (추가)
- 단, claude -p 응답시간 ~30초 → 비동기 처리 필수
