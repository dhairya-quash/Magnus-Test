package com.quashbugs.magnus.controller;

import com.quashbugs.magnus.adapter.VcsAdapter;
import com.quashbugs.magnus.dto.*;
import com.quashbugs.magnus.model.Configuration;
import com.quashbugs.magnus.model.Repo;
import com.quashbugs.magnus.model.User;
import com.quashbugs.magnus.repository.RepoRepository;
import com.quashbugs.magnus.service.RepoService;
import com.quashbugs.magnus.service.VcsProviderFactory;
import io.micrometer.common.util.StringUtils;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/repo")
@SecurityRequirement(name = "jwtAuth")
public class RepoController {

    private final RepoService repoService;
    private final VcsProviderFactory vcsProviderFactory;
    private final RepoRepository repoRepository;

    @Autowired
    public RepoController(RepoService repoService,
                          VcsProviderFactory vcsProviderFactory,
                          RepoRepository repoRepository) {
        this.repoService = repoService;
        this.vcsProviderFactory = vcsProviderFactory;
        this.repoRepository = repoRepository;
    }

    @GetMapping("/get-repo")
    public ResponseEntity<ResponseDTO> listRepositories(
            Authentication authentication,
            @RequestParam String orgId) {
        try {
            User user = (User) authentication.getPrincipal();
            if (orgId == null || orgId.trim().isEmpty()) {
                throw new IllegalArgumentException("Organisation ID cannot be empty");
            }

            VcsAdapter vcsAdapter = vcsProviderFactory.getVcsProvider(user.getVcsProvider());

            List<Repo> repositories = vcsAdapter.fetchAndSaveRepositories(user, orgId);

            return ResponseEntity.ok(new ResponseDTO(true, "Repositories fetched successfully", repositories));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(new ResponseDTO(false, "Error fetching repositories", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(new ResponseDTO(false, "Unexpected error occurred", e.getMessage()));
        }
    }

    @GetMapping("/get-status")
    public ResponseEntity<ResponseDTO> getRepoStatus(@RequestParam List<String> repoIds) {
        try {
            if (repoIds == null || repoIds.isEmpty()) {
                throw new IllegalArgumentException("Repository IDs list cannot be empty");
            }

            List<Repo> repoStatus = repoService.getReposByIds(repoIds);
            return ResponseEntity.ok(new ResponseDTO(true, "Repository statuses fetched successfully", repoStatus));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(new ResponseDTO(false, "Error fetching status for Repo", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(new ResponseDTO(false, "Unexpected error occurred", e.getMessage()));
        }
    }

    @GetMapping("/fetch-branches")
    public ResponseEntity<ResponseDTO> fetchRepoBranch(Authentication authentication, @RequestParam String orgId, @RequestParam String repoId) {
        try {
            User user = (User) authentication.getPrincipal();
            if (repoId == null || repoId.trim().isEmpty()) {
                throw new IllegalArgumentException("Repository ID cannot be empty");
            }
            if (orgId == null || orgId.trim().isEmpty()) {
                throw new IllegalArgumentException("Primary branch cannot be empty");
            }

            VcsAdapter vcsAdapter = vcsProviderFactory.getVcsProvider(user.getVcsProvider());
            List<String> branches = vcsAdapter.fetchRepoBranches(user, orgId, repoId);

            return ResponseEntity.ok(new ResponseDTO(true, "message", branches));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(new ResponseDTO(false, "Invalid input: " + e.getMessage(), null));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest()
                    .body(new ResponseDTO(false, "Operation failed", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(new ResponseDTO(false, "Internal server error", e.getMessage()));
        }
    }

    @PostMapping("/save-branch")
    public ResponseEntity<ResponseDTO> saveRepoBranch(@RequestBody SaveBranchDTO saveBranchDTO) {
        try {
            if (saveBranchDTO.getRepoId() == null || saveBranchDTO.getRepoId().trim().isEmpty()) {
                throw new IllegalArgumentException("Repository ID cannot be empty");
            }
            if (saveBranchDTO.getPrimaryBranch() == null || saveBranchDTO.getPrimaryBranch().trim().isEmpty()) {
                throw new IllegalArgumentException("Primary branch cannot be empty");
            }
            if (saveBranchDTO.getSecondaryBranch() == null || saveBranchDTO.getSecondaryBranch().trim().isEmpty()) {
                throw new IllegalArgumentException("Secondary branch cannot be empty");
            }

            boolean saved = repoService.saveBranches(saveBranchDTO);

            String message = saved ?
                    "Branch information updated successfully" :
                    "No changes were made to branch information";

            return ResponseEntity.ok(new ResponseDTO(true, message, saved));

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(new ResponseDTO(false, "Invalid input: " + e.getMessage(), null));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest()
                    .body(new ResponseDTO(false, "Operation failed", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(new ResponseDTO(false, "Internal server error", e.getMessage()));
        }
    }

    @PostMapping("/start-scanning")
    public ResponseEntity<ResponseDTO> startScanning(
            Authentication authentication,
            @RequestBody StartScanningDTO scanningDTO) {
        User user = (User) authentication.getPrincipal();
        String vcsProvider = user.getVcsProvider();
        VcsAdapter vcsAdapter = vcsProviderFactory.getVcsProvider(vcsProvider);

        try {
            if (scanningDTO.getRepoId() == null || scanningDTO.getRepoId().trim().isEmpty()) {
                throw new IllegalArgumentException("Repository ID cannot be empty");
            }

            ScanningResponseDTO scanningResponse = vcsAdapter.startScanning(scanningDTO.getRepoId(), user);
            return createResponse(scanningResponse);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(new ResponseDTO(false, "Invalid input", e.getMessage()));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest()
                    .body(new ResponseDTO(false, "Error while sending request to AI", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(new ResponseDTO(false, "Unexpected error occurred", e.getMessage()));
        }
    }

    private ResponseEntity<ResponseDTO> createResponse(ScanningResponseDTO scanningResponse) {
        if ("started".equals(scanningResponse.getStatus())) {
            Map<String, Object> responseData = new HashMap<>();
            responseData.put("analysisIds", scanningResponse.getAnalysisIds());
            responseData.put("message", scanningResponse.getMessage());

            return ResponseEntity.ok(new ResponseDTO(
                    true,
                    "Scanning started successfully",
                    responseData
            ));
        } else {
            return ResponseEntity.badRequest()
                    .body(new ResponseDTO(
                            false,
                            "Failed to start scanning",
                            Map.of(
                                    "message", scanningResponse.getMessage(),
                                    "status", scanningResponse.getStatus()
                            )
                    ));
        }
    }

    @PostMapping("/save-config")
    public ResponseEntity<ResponseDTO> saveConfiguration(@RequestBody ConfigurationRequestDTO requestDTO) {
        try {
            validateRequest(requestDTO);

            // Fetch repo by ID
            Repo repo = repoRepository.findById(requestDTO.getRepoId())
                    .orElseThrow(() -> new IllegalArgumentException("Repository not found with ID: " + requestDTO.getRepoId()));

            Configuration configuration = Configuration.builder()
                    .repo(repo)
                    .primaryBranch(requestDTO.getPrimaryBranch())
                    .secondaryBranch(requestDTO.getSecondaryBranch())
                    .trigger(requestDTO.getTrigger())
                    .device(requestDTO.getDevice())
                    .mediaAccess(requestDTO.isMediaAccess())
                    .locationAccess(requestDTO.isLocationAccess())
                    .cameraAccess(requestDTO.isCameraAccess())
                    .microphoneAccess(requestDTO.isMicrophoneAccess())
                    .filesAccess(requestDTO.isFilesAccess())
                    .keys(requestDTO.getKeys())
                    .build();

            Configuration savedConfig = repoService.saveConfiguration(configuration);

            return ResponseEntity.ok(new ResponseDTO(
                    true,
                    "Configuration saved successfully",
                    savedConfig
            ));

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(new ResponseDTO(false, "Invalid input: " + e.getMessage(), null));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest()
                    .body(new ResponseDTO(false, "Operation failed", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(new ResponseDTO(false, "Internal server error", e.getMessage()));
        }
    }

    private void validateRequest(ConfigurationRequestDTO requestDTO) {
        if (StringUtils.isBlank(requestDTO.getRepoId())) {
            throw new IllegalArgumentException("Repository ID cannot be empty");
        }
        if (StringUtils.isBlank(requestDTO.getPrimaryBranch())) {
            throw new IllegalArgumentException("Primary branch cannot be empty");
        }
        if (StringUtils.isBlank(requestDTO.getSecondaryBranch())) {
            throw new IllegalArgumentException("Secondary branch cannot be empty");
        }
        if (requestDTO.getTrigger() == null) {
            throw new IllegalArgumentException("Trigger type cannot be null");
        }
        if (StringUtils.isBlank(requestDTO.getDevice())) {
            throw new IllegalArgumentException("Device cannot be empty");
        }
    }
}