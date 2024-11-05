package com.quashbugs.magnus.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BranchScanningResponseDTO {
    private String status;
    private String analysisId;
    private String message;
    private String branch;
}
