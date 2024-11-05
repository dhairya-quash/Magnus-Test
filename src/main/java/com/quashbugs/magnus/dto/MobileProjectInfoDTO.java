package com.quashbugs.magnus.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MobileProjectInfoDTO {
    private boolean isMobile;
    private String platform;
}
