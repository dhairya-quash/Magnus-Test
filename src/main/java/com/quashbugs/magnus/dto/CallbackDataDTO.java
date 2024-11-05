package com.quashbugs.magnus.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CallbackDataDTO {
    private String status;
    private String analysisId;
    private String appSummary;
    private String knowledgeGraphRef;
    private String message;
}
