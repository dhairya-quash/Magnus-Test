package com.quashbugs.magnus.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quashbugs.magnus.controller.SSEController;
import com.quashbugs.magnus.dto.CallbackDataDTO;
import com.quashbugs.magnus.dto.PrCallbackDataDTO;
import com.quashbugs.magnus.model.*;
import com.quashbugs.magnus.repository.PullRequestRepository;
import com.quashbugs.magnus.repository.RepoRepository;
import com.quashbugs.magnus.repository.TestCaseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Service
public class WebhookService {

    private final PullRequestRepository pullRequestRepository;
    private final TestCaseRepository testCaseRepository;
    private final RepoRepository repoRepository;
    private final ObjectMapper objectMapper;
    private final SSEController sseController;
    private static final Logger LOGGER = LoggerFactory.getLogger(WebhookService.class);

    @Autowired
    public WebhookService(PullRequestRepository pullRequestRepository,
                          TestCaseRepository testCaseRepository,
                          RepoRepository repoRepository,
                          ObjectMapper objectMapper, SSEController sseController) {
        this.pullRequestRepository = pullRequestRepository;
        this.testCaseRepository = testCaseRepository;
        this.repoRepository = repoRepository;
        this.objectMapper = objectMapper;
        this.sseController = sseController;
    }

    @Transactional
    public void handleScanningCallback(String payload) throws JsonProcessingException {
        CallbackDataDTO callbackData = parseCallbackData(payload);
        LOGGER.info("Processing scanning callback for analysis ID: {}, status: {}",
                callbackData.getAnalysisId(), callbackData.getStatus());

        Repo repo = findRepoByAnalysisId(callbackData.getAnalysisId());
        BranchDetails branchDetails = findBranchByAnalysisId(repo, callbackData.getAnalysisId());

        try {
            switch (callbackData.getStatus()) {
                case "scanned" -> handleScannedStatus(repo, branchDetails, callbackData);
                case "failed" -> handleErrorStatus(repo, branchDetails, callbackData.getMessage());
                default -> throw new IllegalArgumentException("Unknown status: " + callbackData.getStatus());
            }

            repoRepository.save(repo);
            sendScanUpdateEvent(repo, branchDetails, callbackData);
        } catch (Exception e) {
            LOGGER.error("Error processing callback for repo {}, branch {}: {}",
                    repo.getName(), branchDetails.getName(), e.getMessage(), e);
            handleErrorStatus(repo, branchDetails, "Error processing callback: " + e.getMessage());
            throw e;
        }
    }

    private CallbackDataDTO parseCallbackData(String payload) throws JsonProcessingException {
        JsonNode data = objectMapper.readTree(payload);
        return CallbackDataDTO.builder()
                .status(data.path("status").asText())
                .analysisId(data.path("analysis_id").asText())
                .appSummary(data.path("app_summary").asText())
                .knowledgeGraphRef(data.path("knowledgeGraph_mediaRef").asText())
                .message(data.path("message").asText())
                .build();
    }

    private Repo findRepoByAnalysisId(String analysisId) {
        return repoRepository.findAll().stream()
                .filter(repo -> isAnalysisIdMatch(repo, analysisId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No repository found for analysis ID: " + analysisId));
    }

    private boolean isAnalysisIdMatch(Repo repo, String analysisId) {
        return (repo.getPrimaryBranchDetails() != null &&
                analysisId.equals(repo.getPrimaryBranchDetails().getAnalysisId())) ||
                (repo.getSecondaryBranchDetails() != null &&
                        analysisId.equals(repo.getSecondaryBranchDetails().getAnalysisId()));
    }

    private BranchDetails findBranchByAnalysisId(Repo repo, String analysisId) {
        if (repo.getPrimaryBranchDetails() != null &&
                analysisId.equals(repo.getPrimaryBranchDetails().getAnalysisId())) {
            return repo.getPrimaryBranchDetails();
        }
        if (repo.getSecondaryBranchDetails() != null &&
                analysisId.equals(repo.getSecondaryBranchDetails().getAnalysisId())) {
            return repo.getSecondaryBranchDetails();
        }
        throw new IllegalStateException("No branch found with analysis ID: " + analysisId);
    }

    private void handleScannedStatus(Repo repo, BranchDetails branchDetails, CallbackDataDTO callbackData) {
        LOGGER.info("Processing scanned status for repo: {}, branch: {}",
                repo.getName(), branchDetails.getName());

        // Update branch details
        branchDetails.setState(BranchAnalysisState.SCANNED);
        branchDetails.setLastAnalyzed(LocalDateTime.now());
        branchDetails.setKnowledgeGraphRef(callbackData.getKnowledgeGraphRef());

        // Check if all branches are scanned
        if (isAllBranchesScanned(repo)) {
            LOGGER.info("All branches scanned for repo: {}. Updating repo state and app summary",
                    repo.getName());
            repo.setState(RepoState.SCANNED);
            repo.setAppSummary(callbackData.getAppSummary());
        }
    }

    private void handleErrorStatus(Repo repo, BranchDetails branchDetails, String errorMessage) {
        LOGGER.error("Processing error status for repo: {}, branch: {}, error: {}",
                repo.getName(), branchDetails.getName(), errorMessage);

        branchDetails.setState(BranchAnalysisState.ERROR);
        branchDetails.setLastAnalyzed(LocalDateTime.now());
        repo.setState(RepoState.ERROR);

        repoRepository.save(repo);
    }

    private void handleErrorStatus(PullRequest pullRequest, PrCallbackDataDTO callbackDataDTO){
        LOGGER.error("Processing error status for pull request: {}, error: {}",
                pullRequest, callbackDataDTO.getMessage());
        pullRequest.setPrState(PullRequestState.ERROR);
        pullRequestRepository.save(pullRequest);
    }

    private boolean isAllBranchesScanned(Repo repo) {
        boolean primaryScanned = repo.getPrimaryBranchDetails() != null &&
                repo.getPrimaryBranchDetails().getState() == BranchAnalysisState.SCANNED;

        if (repo.getSecondaryBranchDetails() == null) {
            return primaryScanned;
        }

        return primaryScanned && repo.getSecondaryBranchDetails().getState() == BranchAnalysisState.SCANNED;
    }

    private void sendScanUpdateEvent(Repo repo, BranchDetails branchDetails, CallbackDataDTO callbackData) {
        try {
            Map<String, Object> eventData = new HashMap<>();
            eventData.put("event", "scan_update");
            eventData.put("repo_id", repo.getId());
            eventData.put("repo_name", repo.getName());
            eventData.put("branch", createBranchEventData(branchDetails));
            eventData.put("status", callbackData.getStatus());

            if ("scanned".equals(callbackData.getStatus()) && repo.getState() == RepoState.SCANNED) {
                eventData.put("app_summary", repo.getAppSummary());
            }

            eventData.put("timestamp", LocalDateTime.now().toString());

            String eventDataJson = objectMapper.writeValueAsString(eventData);
            sseController.sendEvent("repo_update", eventDataJson);

            LOGGER.debug("Sent scan update event for repo: {}, branch: {}",
                    repo.getName(), branchDetails.getName());
        } catch (JsonProcessingException e) {
            LOGGER.error("Error creating SSE event for repo: {}, branch: {}",
                    repo.getName(), branchDetails.getName(), e);
        }
    }

    private Map<String, Object> createBranchEventData(BranchDetails branchDetails) {
        Map<String, Object> branchData = new HashMap<>();
        branchData.put("name", branchDetails.getName());
        branchData.put("analysis_id", branchDetails.getAnalysisId());
        branchData.put("state", branchDetails.getState());

        if (branchDetails.getKnowledgeGraphRef() != null) {
            branchData.put("knowledge_graph_ref", branchDetails.getKnowledgeGraphRef());
        }

        return branchData;
    }


    @Transactional
    public void handlePrCallback(String payload) throws JsonProcessingException {
        PrCallbackDataDTO callbackData = parsePrCallbackData(payload);

        Repo repo = findRepoByAnalysisId(callbackData.getAnalysisId());

        PullRequest pullRequest = findPullRequest(callbackData, repo);
        validateBranchConfiguration(pullRequest);

        try {
            switch (callbackData.getStatus()) {
                case "started" -> handleStartedStatus(pullRequest);
                case "in_progress" -> handleInProgressStatus(pullRequest, callbackData);
                case "completed" -> handleCompletedStatus(pullRequest, callbackData);
                case "failed" -> handleErrorStatus(pullRequest, callbackData);
                default -> throw new IllegalArgumentException("Unknown status: " + callbackData.getStatus());
            }

            sendPrUpdateEvent(pullRequest, callbackData);
        } catch (Exception e) {
            LOGGER.error("Error processing PR callback: {}", e.getMessage(), e);
            handleError(pullRequest, e);
            throw e;
        }
    }

    private PrCallbackDataDTO parsePrCallbackData(String payload) throws JsonProcessingException {
        JsonNode data = objectMapper.readTree(payload);
        return PrCallbackDataDTO.builder()
                .status(data.path("status").asText())
                .analysisId(data.path("analysis_id").asText())
                .prAnalysisId(data.path("pr_analysis_id").asText())
                .pullRequestNumber(data.path("pull_request_number").asText())
                .summary(data.path("summary").asText())
                .scopes(parseScopes(data))
                .testCases(parseTestCases(data))
                .scriptMediaRef(data.path("scriptMediaRef").asText())
                .message(data.path("message").asText())
                .build();
    }

    private List<String> parseScopes(JsonNode data) {
        if (data.has("scopes")) {
            return StreamSupport.stream(data.path("scopes").spliterator(), false)
                    .map(JsonNode::asText)
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    private List<Map<String, Object>> parseTestCases(JsonNode data) {
        try {
            if (data.has("test_cases")) {
                return objectMapper.convertValue(data.path("test_cases"),
                        new TypeReference<List<Map<String, Object>>>() {});
            }
        } catch (Exception e) {
            LOGGER.error("Error parsing test cases", e);
        }
        return Collections.emptyList();
    }

    private void handleCompletedStatus(PullRequest pullRequest, PrCallbackDataDTO callbackData) {
        LOGGER.info("Completing PR analysis for PR: {} in repo: {}",
                pullRequest.getPullRequestNumber(), pullRequest.getRepo().getName());

        // Update PullRequest entity
        pullRequest.setPrAnalysisId(callbackData.getPrAnalysisId());
        pullRequest.setTestsGeneratedAt(LocalDateTime.now());
        pullRequest.setTestSummary(callbackData.getSummary());
        pullRequest.setScopes(callbackData.getScopes());
        pullRequest.setScriptMediaRef(callbackData.getScriptMediaRef());
        pullRequest.setPrState(PullRequestState.PR_ANALYZED);

        // Create and save test cases
        List<TestCase> testCases = createTestCases(pullRequest, callbackData.getTestCases());

        // Save everything
        pullRequestRepository.save(pullRequest);
        testCaseRepository.saveAll(testCases);

        LOGGER.info("Successfully processed completed status for PR: {}. Generated {} test cases.",
                pullRequest.getPullRequestNumber(), testCases.size());
    }

    private void validateBranchConfiguration(PullRequest pullRequest) {
        Repo repo = pullRequest.getRepo();
        String targetBranch = pullRequest.getTargetBranch();

        boolean isValidBranch = false;

        if (repo.getPrimaryBranchDetails() != null &&
                targetBranch.equals(repo.getPrimaryBranchDetails().getName())) {
            isValidBranch = true;
        }

        if (!isValidBranch &&
                repo.getSecondaryBranchDetails() != null &&
                targetBranch.equals(repo.getSecondaryBranchDetails().getName())) {
            isValidBranch = true;
        }

        if (!isValidBranch) {
            LOGGER.error("Invalid target branch {} for PR {} in repo {}. Primary: {}, Secondary: {}",
                    targetBranch,
                    pullRequest.getPullRequestNumber(),
                    repo.getName(),
                    repo.getPrimaryBranchDetails() != null ? repo.getPrimaryBranchDetails().getName() : "null",
                    repo.getSecondaryBranchDetails() != null ? repo.getSecondaryBranchDetails().getName() : "null");

            throw new IllegalStateException(String.format(
                    "Target branch '%s' is not configured for analysis in repo '%s'",
                    targetBranch,
                    repo.getName()));
        }

        LOGGER.debug("Validated target branch {} for PR {} in repo {}",
                targetBranch,
                pullRequest.getPullRequestNumber(),
                repo.getName());
    }

    private List<TestCase> createTestCases(PullRequest pullRequest, List<Map<String, Object>> testCasesData) {
        return testCasesData.stream()
                .map(testCaseData -> TestCase.builder()
                        .pullRequest(pullRequest)
                        .title((String) testCaseData.get("title"))
                        .steps((List<String>) testCaseData.get("steps"))
                        .createdAt(LocalDateTime.now())
                        .build())
                .collect(Collectors.toList());
    }

    private void sendPrUpdateEvent(PullRequest pullRequest, PrCallbackDataDTO callbackData) {
        try {
            Map<String, Object> eventData = new HashMap<>();
            eventData.put("event", "pr_update");
            eventData.put("pr_number", pullRequest.getPullRequestNumber());
            eventData.put("repo_name", pullRequest.getRepo().getName());
            eventData.put("status", callbackData.getStatus());
            eventData.put("target_branch", pullRequest.getTargetBranch());

            switch (callbackData.getStatus()) {
                case "started" -> {
                    eventData.put("analysis_id", callbackData.getAnalysisId());
                    eventData.put("pr_analysis_id", callbackData.getPrAnalysisId());
                    eventData.put("message", callbackData.getMessage());
                }
                case "in_progress" -> {
                    eventData.put("summary", callbackData.getSummary());
                    eventData.put("message", callbackData.getMessage());
                }
                case "completed" -> {
                    eventData.put("summary", callbackData.getSummary());
                    eventData.put("test_count", callbackData.getTestCases().size());
                    eventData.put("scopes", callbackData.getScopes());
                    eventData.put("script_media_ref", callbackData.getScriptMediaRef());
                    eventData.put("message", callbackData.getMessage());
                }
            }

            eventData.put("timestamp", LocalDateTime.now().toString());

            String eventDataJson = objectMapper.writeValueAsString(eventData);
            sseController.sendEvent("pr_update", eventDataJson);

            LOGGER.debug("Sent PR update event for PR: {}, status: {}",
                    pullRequest.getPullRequestNumber(), callbackData.getStatus());
        } catch (JsonProcessingException e) {
            LOGGER.error("Error sending PR update event", e);
        }
    }

    private PullRequest findPullRequest(PrCallbackDataDTO callbackData, Repo repo) {
        return pullRequestRepository.findByPullRequestNumberAndRepo(callbackData.getPullRequestNumber(), repo)
                .orElseThrow(() -> new IllegalStateException(
                        "No PullRequest found for number: " + callbackData.getPullRequestNumber()));
    }

    private void handleStartedStatus(PullRequest pullRequest) {
        LOGGER.info("Starting PR analysis for PR: {} in repo: {}",
                pullRequest.getPullRequestNumber(), pullRequest.getRepo().getName());
        pullRequest.setPrState(PullRequestState.ANALYZING_PR);
        pullRequestRepository.save(pullRequest);
    }

    private void handleInProgressStatus(PullRequest pullRequest, PrCallbackDataDTO callbackData) {
        LOGGER.info("Updating PR analysis progress for PR: {} in repo: {}",
                pullRequest.getPullRequestNumber(), pullRequest.getRepo().getName());
        pullRequest.setTestSummary(callbackData.getSummary());
        pullRequestRepository.save(pullRequest);
    }

    private void handleError(PullRequest pullRequest, Exception e) {
        LOGGER.error("Error in PR analysis for PR: {} in repo: {}",
                pullRequest.getPullRequestNumber(), pullRequest.getRepo().getName(), e);

        pullRequest.setPrState(PullRequestState.ERROR);
        pullRequestRepository.save(pullRequest);

        sendErrorEvent(pullRequest, e.getMessage());
    }

    private void sendErrorEvent(PullRequest pullRequest, String errorMessage) {
        try {
            Map<String, Object> eventData = new HashMap<>();
            eventData.put("event", "pr_error");
            eventData.put("pr_number", pullRequest.getPullRequestNumber());
            eventData.put("repo_name", pullRequest.getRepo().getName());
            eventData.put("error", errorMessage);
            eventData.put("timestamp", LocalDateTime.now().toString());

            String eventDataJson = objectMapper.writeValueAsString(eventData);
            sseController.sendEvent("pr_update", eventDataJson);
        } catch (JsonProcessingException e) {
            LOGGER.error("Error sending PR error event", e);
        }
    }

}
