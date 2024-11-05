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
@Document(collection = "organisations")
@TypeAlias("org")
public abstract class Organisation {
    @Id
    private String id;
    private String name;
    private OrganisationType type; // PERSONAL, WORK
    @DBRef
    private User owner;
    private String vcsProvider;
    private LocalDateTime createdAt;
}
