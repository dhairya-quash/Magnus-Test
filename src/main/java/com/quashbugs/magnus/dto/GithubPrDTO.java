package com.quashbugs.magnus.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GithubPrDTO {
    private String githubAccessToken;
    private String ownerAccessToken;
    private String ownerWorkEmail;
}
