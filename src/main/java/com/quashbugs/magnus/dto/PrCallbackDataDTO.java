package com.quashbugs.magnus.dto;

import com.quashbugs.magnus.model.TestCase;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PrCallbackDataDTO {
    private String status;
    private String analysisId;
    private String prAnalysisId;
    private String pullRequestNumber;
    private String summary;
    private List<String> scopes;
    private String scriptMediaRef;
    private List<Map<String, Object>> testCases;
    private String message;
}
