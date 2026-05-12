package com.jirabot.slack.service;

import com.jirabot.slack.entity.IssueEntity;
import com.jirabot.slack.repository.IssueRepository;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// STUDY: 2-pass 설계의 1단계(DB 키워드 필터). 제목에서 의미 있는 키워드를 추출하고
//        각 키워드로 DB 검색 → 후보 합산 → 중복 제거. Claude 비교(2단계)는 추후 확장.
@Service
@Transactional(readOnly = true)
public class DuplicateDetectionServiceImpl implements DuplicateDetectionService {

    private static final Logger log = LoggerFactory.getLogger(DuplicateDetectionServiceImpl.class);

    // STUDY: 한국어 조사/접미사, 영어 불용어 등 의미 없는 단어를 제외해야 검색 정확도가 올라간다.
    private static final Set<String> STOP_WORDS = Set.of(
            "에서", "에서의", "으로", "되는", "하는", "있는", "없는", "된", "할", "를", "이", "가",
            "the", "a", "an", "is", "are", "in", "on", "at", "for", "to", "of", "and", "or",
            "추가", "수정", "변경", "필요", "관련", "대한", "해주세요", "부탁", "합니다", "있습니다"
    );

    private static final Pattern SPLIT_PATTERN = Pattern.compile("[\\s/\\-_.,()\\[\\]{}]+");

    private final IssueRepository issueRepository;
    private final int minKeywordLength;
    private final int minKeywordMatches;
    private final int maxResults;

    public DuplicateDetectionServiceImpl(IssueRepository issueRepository,
                                         @Value("${slackbot.duplicate.min-keyword-length:2}") int minKeywordLength,
                                         @Value("${slackbot.duplicate.min-keyword-matches:2}") int minKeywordMatches,
                                         @Value("${slackbot.duplicate.max-results:5}") int maxResults) {
        this.issueRepository = issueRepository;
        this.minKeywordLength = minKeywordLength;
        this.minKeywordMatches = minKeywordMatches;
        this.maxResults = maxResults;
    }

    @Override
    public List<IssueEntity> findSimilar(String title) {
        if (title == null || title.isBlank()) {
            return List.of();
        }

        List<String> keywords = extractKeywords(title);
        if (keywords.size() < minKeywordMatches) {
            return List.of();
        }

        // 각 키워드로 DB 검색 → 이슈별 매칭 키워드 수 집계
        java.util.Map<Long, IssueEntity> issueMap = new java.util.LinkedHashMap<>();
        java.util.Map<Long, Integer> matchCount = new java.util.HashMap<>();

        for (String keyword : keywords) {
            List<IssueEntity> matches = issueRepository.findBySummaryContaining(keyword);
            for (IssueEntity issue : matches) {
                issueMap.putIfAbsent(issue.getId(), issue);
                matchCount.merge(issue.getId(), 1, Integer::sum);
            }
        }

        // 2개 이상 키워드가 겹치는 이슈만 필터링, 매칭 수 내림차순 정렬
        List<IssueEntity> result = matchCount.entrySet().stream()
                .filter(e -> e.getValue() >= minKeywordMatches)
                .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .limit(maxResults)
                .map(e -> issueMap.get(e.getKey()))
                .toList();

        log.debug("Duplicate check for '{}': keywords={} candidates={}", title, keywords, result.size());
        return result;
    }

    private List<String> extractKeywords(String title) {
        String[] tokens = SPLIT_PATTERN.split(title.strip().toLowerCase());
        List<String> keywords = new ArrayList<>();
        for (String token : tokens) {
            if (token.length() >= minKeywordLength && !STOP_WORDS.contains(token)) {
                keywords.add(token);
            }
        }
        return keywords;
    }
}
