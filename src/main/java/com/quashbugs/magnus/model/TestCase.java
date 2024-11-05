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
@Document(collection = "test_cases")
public class TestCase {
    @Id
    private String id;
    @DBRef
    private PullRequest pullRequest;
    private String title;
    private List<String> steps;
    private LocalDateTime createdAt;
}
