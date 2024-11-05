package com.quashbugs.magnus.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.springframework.data.annotation.TypeAlias;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "organisations")
@TypeAlias("github_org")
public class GithubOrganisation extends Organisation{
    private String githubInstallationId;
    private String githubInstallationToken;
    private String githubAccessToken;
    private LocalDateTime githubInstallationTokenExpiry; // jwt
    private LocalDateTime githubAccessTokenExpiry; // github token
}
