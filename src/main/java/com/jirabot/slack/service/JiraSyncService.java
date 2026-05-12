package com.jirabot.slack.service;

public interface JiraSyncService {

    /**
     * 활성 스프린트의 모든 이슈를 Jira에서 조회하여 로컬 DB에 동기화한다.
     * 이미 존재하는 이슈는 업데이트, 없는 이슈는 신규 생성.
     *
     * @return 동기화 결과 요약 메시지
     */
    String syncActiveSprint();
}
