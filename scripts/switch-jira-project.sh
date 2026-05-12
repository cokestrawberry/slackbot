#!/bin/bash
# Jira 프로젝트/사이트 변경 스크립트
#
# 사용법:
#   ./scripts/switch-jira-project.sh                          # 대화형
#   ./scripts/switch-jira-project.sh --show                   # 현재 설정 표시
#   ./scripts/switch-jira-project.sh \
#     --url https://company.atlassian.net \
#     --email you@company.com \
#     --token ATATT3x... \
#     --project PROJ                                          # 직접 지정
#
# 변경 후 Spring Boot 재시작이 필요합니다.

set -euo pipefail

ENV_FILE="${ENV_FILE:-.env}"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
CYAN='\033[0;36m'
NC='\033[0m'

show_current() {
    if [ ! -f "$ENV_FILE" ]; then
        echo -e "${RED}.env 파일이 없습니다.${NC}"
        exit 1
    fi
    echo -e "${CYAN}현재 Jira 설정:${NC}"
    echo ""
    grep -E "^JIRA_" "$ENV_FILE" | sed 's/JIRA_API_TOKEN=.*/JIRA_API_TOKEN=****(마스킹)/'
    echo ""
}

validate_connection() {
    local url="$1"
    local email="$2"
    local token="$3"
    local project="$4"

    echo -e "${YELLOW}연결 검증 중...${NC}"
    response=$(curl -s -o /dev/null -w "%{http_code}" -u "${email}:${token}" \
        "${url}/rest/api/3/project/${project}")

    if [ "$response" = "200" ]; then
        echo -e "${GREEN}✅ 연결 성공 (프로젝트 ${project} 확인됨)${NC}"
        return 0
    else
        echo -e "${RED}❌ 연결 실패 (HTTP ${response})${NC}"
        echo "URL, 이메일, 토큰, 프로젝트 키를 확인하세요."
        return 1
    fi
}

update_env() {
    local key="$1"
    local value="$2"

    if grep -q "^${key}=" "$ENV_FILE"; then
        # macOS sed 호환
        sed -i '' "s|^${key}=.*|${key}=${value}|" "$ENV_FILE"
    else
        echo "${key}=${value}" >> "$ENV_FILE"
    fi
}

apply_settings() {
    local url="$1"
    local email="$2"
    local token="$3"
    local project="$4"

    update_env "JIRA_BASE_URL" "$url"
    update_env "JIRA_EMAIL" "$email"
    update_env "JIRA_API_TOKEN" "$token"
    update_env "JIRA_PROJECT_KEY" "$project"

    echo -e "${GREEN}✅ .env 업데이트 완료${NC}"
    echo ""
    echo -e "${YELLOW}⚠️  Spring Boot를 재시작해야 적용됩니다:${NC}"
    echo "   1. 실행 중인 Spring Boot 종료 (Ctrl+C)"
    echo "   2. set -a && source .env && set +a && ./gradlew bootRun"
    echo ""
    echo -e "${YELLOW}⚠️  DB 동기화도 필요합니다:${NC}"
    echo "   @지라봇 sync"
}

interactive() {
    echo -e "${CYAN}Jira 프로젝트 변경${NC}"
    echo ""
    show_current

    read -p "새 Jira URL (예: https://company.atlassian.net): " url
    [ -z "$url" ] && echo -e "${RED}URL은 필수입니다.${NC}" && exit 1

    read -p "Jira 이메일: " email
    [ -z "$email" ] && echo -e "${RED}이메일은 필수입니다.${NC}" && exit 1

    read -sp "Jira API Token: " token
    echo ""
    [ -z "$token" ] && echo -e "${RED}토큰은 필수입니다.${NC}" && exit 1

    read -p "프로젝트 키 (예: PROJ): " project
    [ -z "$project" ] && echo -e "${RED}프로젝트 키는 필수입니다.${NC}" && exit 1

    validate_connection "$url" "$email" "$token" "$project" || exit 1
    apply_settings "$url" "$email" "$token" "$project"
}

# 메인
case "${1:-}" in
    --help|-h)
        head -11 "$0" | tail -10
        ;;
    --show|-s)
        show_current
        ;;
    --url)
        # 직접 지정 모드
        url="$2"; email=""; token=""; project=""
        shift 2
        while [ $# -gt 0 ]; do
            case "$1" in
                --email) email="$2"; shift 2 ;;
                --token) token="$2"; shift 2 ;;
                --project) project="$2"; shift 2 ;;
                *) shift ;;
            esac
        done
        [ -z "$url" ] || [ -z "$email" ] || [ -z "$token" ] || [ -z "$project" ] && \
            echo -e "${RED}--url, --email, --token, --project 모두 필요합니다.${NC}" && exit 1
        validate_connection "$url" "$email" "$token" "$project" || exit 1
        apply_settings "$url" "$email" "$token" "$project"
        ;;
    "")
        interactive
        ;;
    *)
        echo -e "${RED}알 수 없는 옵션: $1${NC}"
        echo "사용법: $0 [--show | --help | --url ...]"
        exit 1
        ;;
esac
