package com.jirabot.slack.service;

import com.jirabot.slack.entity.IssueEntity;
import java.util.List;

public interface DuplicateDetectionService {

    /**
     * 새 이슈 제목과 유사한 기존 이슈를 검색한다.
     *
     * @param title 새로 생성할 이슈의 제목
     * @return 유사한 기존 이슈 목록 (빈 리스트면 중복 없음)
     */
    List<IssueEntity> findSimilar(String title);
}
