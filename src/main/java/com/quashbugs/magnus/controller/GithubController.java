package com.quashbugs.magnus.controller;

import com.quashbugs.magnus.dto.ResponseDTO;
import com.quashbugs.magnus.model.Organisation;
import com.quashbugs.magnus.model.User;
import com.quashbugs.magnus.service.GithubService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/github")
@SecurityRequirement(name = "jwtAuth")
public class GithubController {

    private final GithubService githubService;

    @Autowired
    public GithubController(GithubService githubService) {
        this.githubService = githubService;
    }

    @GetMapping("/installation-url")
    public ResponseEntity<ResponseDTO> getInstallationUrl() {
        try {
            String installationUrl = githubService.getInstallationUrl();
            return ResponseEntity.ok(new ResponseDTO(true, "Installation URL generated successfully", installationUrl));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(new ResponseDTO(false, "Error generating installation URL", e.getMessage()));
        }
    }

    @GetMapping("/token/{installationId}")
    public ResponseEntity<ResponseDTO> getInstallationAccessToken(Authentication authentication, @PathVariable String installationId) {
        try {
            User user = (User) authentication.getPrincipal();
            Organisation orgData = githubService.getInstallationAccessToken(user, installationId);
            return ResponseEntity.ok(new ResponseDTO(true, "Installation access token generated successfully", orgData));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(new ResponseDTO(false, "Error generating token", e.getMessage()));
        }
    }

    @GetMapping("/get-branches")
    public ResponseEntity<ResponseDTO> listBranches(
            @RequestParam String orgName,
            @RequestParam String repoName) {
        try {
            List<String> branches = githubService.fetchBranches(orgName, repoName);
            return ResponseEntity.ok(new ResponseDTO(true, "RBranches fetched successfully", branches));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(new ResponseDTO(false, "Unexpected error occurred: " + e.getMessage(), null));
        }
    }
}