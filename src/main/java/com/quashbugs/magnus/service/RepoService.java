package com.quashbugs.magnus.service;

import com.quashbugs.magnus.dto.SaveBranchDTO;
import com.quashbugs.magnus.model.BranchDetails;
import com.quashbugs.magnus.model.Configuration;
import com.quashbugs.magnus.model.Repo;
import com.quashbugs.magnus.repository.ConfigurationRepository;
import com.quashbugs.magnus.repository.RepoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.aggregation.ComparisonOperators;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class RepoService {
    
    private final RepoRepository repoRepository;
    private final ConfigurationRepository configurationRepository;
    private static final Logger LOGGER = LoggerFactory.getLogger(RepoService.class);

    @Autowired
    public RepoService(RepoRepository repoRepository,
                       ConfigurationRepository configurationRepository) {
        this.repoRepository = repoRepository;
        this.configurationRepository = configurationRepository;
    }

    public List<Repo> getReposByIds(List<String> repoIds) {
        if (repoIds == null || repoIds.isEmpty()) {
            throw new IllegalArgumentException("Repository IDs list cannot be empty");
        }
        try {
            List<Repo> repos = repoRepository.findAllById(repoIds);

            if (repos.size() != repoIds.size()) {
                Set<String> foundIds = repos.stream()
                        .map(Repo::getId)
                        .collect(Collectors.toSet());
                List<String> missingIds = repoIds.stream()
                        .filter(id -> !foundIds.contains(id))
                        .collect(Collectors.toList());

                LOGGER.warn("Some repositories were not found: {}", missingIds);
            }

            return repos;

        } catch (Exception e) {
            LOGGER.error("Error fetching repositories", e);
            throw new RuntimeException("Failed to fetch repositories", e);
        }
    }

    @Transactional
    public Boolean saveBranches(SaveBranchDTO saveBranchDTO) {
        Repo repo = repoRepository.findById(saveBranchDTO.getRepoId())
                .orElseThrow(() -> new RuntimeException("Repository not found with ID: " + saveBranchDTO.getRepoId()));

        boolean isModified = false;

        // Handle primary branch
        BranchDetails primaryDetails = repo.getPrimaryBranchDetails();
        if (primaryDetails == null) {
            primaryDetails = new BranchDetails();
            repo.setPrimaryBranchDetails(primaryDetails);
            isModified = true;
        }

        // Only update if the branch name has changed
        if (!saveBranchDTO.getPrimaryBranch().equals(primaryDetails.getName())) {
            primaryDetails.setName(saveBranchDTO.getPrimaryBranch());
            primaryDetails.setLastAnalyzed(LocalDateTime.now());
            isModified = true;
        }

        // Handle secondary branch
        BranchDetails secondaryDetails = repo.getSecondaryBranchDetails(); // Fixed from getPrimaryBranchDetails
        if (secondaryDetails == null) {
            secondaryDetails = new BranchDetails();
            repo.setSecondaryBranchDetails(secondaryDetails);
            isModified = true;
        }

        // Only update if the branch name has changed
        if (!saveBranchDTO.getSecondaryBranch().equals(secondaryDetails.getName())) {
            secondaryDetails.setName(saveBranchDTO.getSecondaryBranch());
            secondaryDetails.setLastAnalyzed(LocalDateTime.now());
            isModified = true;
        }

        // Validate that branches are different
        if (saveBranchDTO.getPrimaryBranch().equals(saveBranchDTO.getSecondaryBranch())) {
            throw new IllegalArgumentException("Primary and secondary branches cannot be the same");
        }

        // Save only if changes were made
        if (isModified) {
            try {
                repoRepository.save(repo);
                LOGGER.info("Successfully updated branches for repo ID: {}", saveBranchDTO.getRepoId());
            } catch (Exception e) {
                LOGGER.error("Error saving repository branches: {}", e.getMessage());
                throw new RuntimeException("Failed to save branch updates", e);
            }
        } else {
            LOGGER.debug("No changes required for repo ID: {}", saveBranchDTO.getRepoId());
        }

        return isModified;
    }

    @Transactional
    public Configuration saveConfiguration(Configuration configuration) {
        try {
            LOGGER.info("Saving configuration for repo: {}", configuration.getRepo().getId());
            return configurationRepository.save(configuration);
        } catch (Exception e) {
            LOGGER.error("Error saving configuration: {}", e.getMessage());
            throw new RuntimeException("Failed to save configuration", e);
        }
    }
}
