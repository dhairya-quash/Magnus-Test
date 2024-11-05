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
@Document(collection = "members")
public class Member {
    @Id
    private String id;
    @DBRef
    private User user;  // Reference to User
    private List<String> organisationIds;  // Reference to Organisation
    private String vcsAccessToken;
    private String vcsRefreshToken;
    private LocalDateTime vcsTokenExpiry;
    private boolean hasAccepted;
    private String role;
}
