package com.quashbugs.magnus.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.DBRef;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "configuration")
public class Configuration {
    @Id
    private String id;
    @DBRef
    private Repo repo;
    private String primaryBranch;
    private String secondaryBranch;
    private TriggerType trigger;
    private String device;
    private boolean mediaAccess;
    private boolean locationAccess;
    private boolean cameraAccess;
    private boolean microphoneAccess;
    private boolean filesAccess;
    private Map<String, String> keys;
}
