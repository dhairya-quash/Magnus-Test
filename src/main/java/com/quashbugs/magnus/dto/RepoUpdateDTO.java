package com.quashbugs.magnus.dto;

import com.quashbugs.magnus.model.RepoState;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RepoUpdateDTO {
    private String id;
    private String name;
    private RepoState state;
    private boolean isMobile;
    private String platform;
}
