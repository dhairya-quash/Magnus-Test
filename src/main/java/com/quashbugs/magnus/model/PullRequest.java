package com.quashbugs.magnus.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.DBRef;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "pull_requests")
public class PullRequest {
    @Id
    private String id;
    @DBRef
    private Repo repo;
    private String pullRequestNumber;
    private String pullRequestTitle;
    private String prAnalysisId;
    private String sourceBranch;
    private String targetBranch;
    private String authorName;
    private LocalDateTime createdAt;
    private LocalDateTime testsGeneratedAt;
    private String testSummary;
    private List<String> scopes;
    private String scriptMediaRef;
    private PullRequestState prState; 
}
