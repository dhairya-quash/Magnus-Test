package com.quashbugs.magnus.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SaveBranchDTO {
    private String repoId;
    private String primaryBranch;
    private String secondaryBranch;
}
