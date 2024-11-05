package com.quashbugs.magnus.dto;

import com.quashbugs.magnus.model.TriggerType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConfigurationRequestDTO {
    private String repoId;  // Changed from Repo to String
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

