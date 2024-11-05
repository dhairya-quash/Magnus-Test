package com.quashbugs.magnus.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RepoFile {
    private String name;
    private String path;
    private String type;
}
