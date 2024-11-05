package com.quashbugs.magnus.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BranchDetails {
    private String name;
    private String analysisId;
    private String knowledgeGraphRef;
    private LocalDateTime lastAnalyzed;
    private BranchAnalysisState state;
}
