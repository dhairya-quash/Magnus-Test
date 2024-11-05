package com.quashbugs.magnus.model;


import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.TypeAlias;
import org.springframework.data.mongodb.core.mapping.DBRef;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "repositories")
@TypeAlias("repository")
public class Repo {
    @Id
    private String id;
    private String name;
    private boolean isPrivate;
    @DBRef
    private Organisation organisation;
    private BranchDetails primaryBranchDetails;
    private BranchDetails secondaryBranchDetails;
    private String language;
    private LocalDateTime createdAt;
    private boolean isMobile;
    private String platform;
    private RepoState state;
    private String appSummary;
}
