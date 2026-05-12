# Deferred Task — GCP Secret Manager 기반 환경 복구

> **상태:** deferred / not blocking Phase 1
> **선행 조건:** Phase 1 Task #7 E2E 완료 후 검토
> **트리거:** 토큰 회전 빈도가 늘거나, 여러 머신/팀원 간 동일 secret 공유 필요성 발생 시

## 배경

이전 리눅스 서버에서는 `setup/install.sh` + `~/.code-assistant.json` + GCP Secret Manager 조합으로 secret 을 중앙 관리했음. 맥북 이관 시 `.env` 방식으로 일단 진행하기로 결정.

현재 `.env` 방식의 한계:
- 토큰 회전 시 파일 수동 수정
- 머신마다 `.env` 따로 유지 → 동기화 drift
- 백업/Time Machine 에 평문으로 들어감
- Claude Code 의 agents/skills/hooks 가 쓰는 여러 개의 외부 secret 을 매번 쉘 env 로 주입해야 함

## install.sh 분석 요약

`setup/install.sh` 동작:
1. `setup/config.json.template` → `~/.code-assistant.json` (mode 600) 복사
2. 두 번째 실행 시 symlink:
   - `setup/claude/{agents,skills,hooks}` → `~/.claude/*`
   - `setup/codex/skills` → `~/.codex/skills`

`~/.code-assistant.json` 의 의미: **GCP Secret Manager 에 저장된 secret 이름들의 매핑 테이블** (실제 값 아님). 형식:
```json
"JIRA_API_TOKEN": "<USERNAME>-jira-token"
```
→ "JIRA_API_TOKEN 환경변수가 필요하면 GCP 프로젝트의 `<USERNAME>-jira-token` 이름에서 꺼내라"

실제로 값을 꺼내는 로직은 symlink 되는 `setup/claude/{agents,skills,hooks}` 와 `setup/codex/skills` 안의 스크립트에 있음. **현재 repo 에는 이 디렉토리들이 없음** (서버에서 맥북 이관 시 누락).

## 복구 작업 목록

### Part A — 원본 repo 확보
- [ ] 서버에 있던 git repo 의 origin URL 확보
  - 서버에 접속 가능하면: `git -C <path-to-setup-repo> remote get-url origin`
  - install.sh L8 이 실행 시 echo 하도록 설계되어 있음
- [ ] Clone 가능 여부 확인 (사설 git/SSO 필요 여부)
- [ ] `setup/claude/`, `setup/codex/` 누락 디렉토리 채우기
- [ ] install.sh 가 `ln -s` broken link 를 조용히 만드는 이슈 방지 — source 존재 검증 추가 (PR 제안)

### Part B — gcloud CLI & GCP 프로젝트
- [ ] `brew install --cask google-cloud-sdk`
- [ ] `gcloud auth login` + `gcloud auth application-default login`
- [ ] `gcloud config set project <GCP_PROJECT_ID>`
- [ ] 조직 IAM 에서 `roles/secretmanager.secretAccessor` 권한 확인
- [ ] Secret Manager 에 해당 secret 들이 실제로 존재하는지 확인:
  ```bash
  gcloud secrets list --filter="name~<USERNAME>-"
  ```

### Part C — Secret Manager 에 값 등록 (첫 머신 한정)
- [ ] 현재 `.env` 의 값들을 Secret Manager 로 마이그레이션
  ```bash
  echo -n "$JIRA_API_TOKEN" | gcloud secrets create <USERNAME>-jira-token --data-file=-
  # 또는 버전 추가
  echo -n "$NEW_TOKEN" | gcloud secrets versions add <USERNAME>-jira-token --data-file=-
  ```
- [ ] `.env.example` 의 Slack 토큰 재발급 (이미 평문 노출 상태라 마이그레이션 전 재발급 필수)

### Part D — install.sh 실행 & 검증
- [ ] 1회차: `./setup/install.sh` → `~/.code-assistant.json` 생성
- [ ] `vi ~/.code-assistant.json` → `<GCP_PROJECT_ID>`, `<USERNAME>` 등 채움
- [ ] 2회차: `./setup/install.sh` → symlink 생성
- [ ] `~/.claude/agents`, `~/.claude/skills` 링크 검증: `ls -la ~/.claude/`
- [ ] 임의의 skill/hook 이 secret 을 실제로 꺼내오는지 확인

### Part E — 슬랙봇과 통합 (선택)
현재 Spring Boot / Go bot 은 `~/.code-assistant.json` 을 모르고 평범한 env 변수만 읽음. 옵션:

**옵션 1 (간단):** wrapper 스크립트가 gcloud 로 secret 꺼내 export 후 `bootRun` 실행
```bash
#!/usr/bin/env bash
export JIRA_API_TOKEN=$(gcloud secrets versions access latest --secret=<USERNAME>-jira-token)
export SLACK_SIGNING_SECRET=$(gcloud secrets versions access latest --secret=<USERNAME>-slack-signing-secret)
# ...
./gradlew bootRun
```
→ `.env` 완전 대체.

**옵션 2 (Spring-native):** `spring-cloud-gcp-starter-secretmanager` 의존성 추가. `application.yml` 에서 `${sm://projects/<proj>/secrets/<name>}` 참조.
→ 빌드 의존성 늘어남, Phase 1 범위 초과.

## 리스크 / 단점

- **GCP Secret Manager 유료**: secret 당 $0.06/월 + access $0.03/10K calls. 개인 용도 기준 월 $1 미만이지만 0은 아님.
- **단일 장애점 전환**: gcloud 인증 세션 만료 / GCP 장애 시 모든 툴 동시 실패. 디버깅 축 증가.
- **secret 이름 자체가 조직 식별자 노출**: `<USERNAME>-jira-token` 포맷이 어딘가 커밋되면 사용자명 특정 가능.
- **원본 agents/skills 코드 없이는 install.sh 의 절반만 복구 가능**: 현재 repo 만으로는 Phase 0 까지만 의미 있음.
- **맥북 로컬 개발 한정이라면 오버엔지니어링 가능성**: 1인 개발 + 단일 머신이면 `.env` + 파일 권한 600 + Time Machine 제외 설정이 충분할 수 있음.

## Definition of Done

- `./setup/install.sh` 를 빈 환경에서 실행 → `~/.claude/agents` 등 유효한 symlink 생성 확인
- 임의의 agent/skill 이 `gcloud secrets versions access` 로 값 꺼내 사용 검증
- 최소 1개 secret 을 Secret Manager 에서 회전 → 툴링이 자동으로 새 값 반영 확인
- 기존 `.env` 기반 경로를 계속 쓸 것인지, gcloud 래퍼로 교체할 것인지 결정 기록
