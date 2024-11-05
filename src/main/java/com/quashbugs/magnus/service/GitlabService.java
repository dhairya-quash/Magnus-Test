package com.quashbugs.magnus.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quashbugs.magnus.adapter.VcsAdapter;
import com.quashbugs.magnus.dto.MobileProjectInfoDTO;
import com.quashbugs.magnus.dto.RepoFile;
import com.quashbugs.magnus.dto.ScanningResponseDTO;
import com.quashbugs.magnus.model.*;
import com.quashbugs.magnus.repository.GitlabRepoRepository;
import com.quashbugs.magnus.repository.MemberRepository;
import com.quashbugs.magnus.repository.OrganisationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.*;

@Service
public class GitlabService implements VcsAdapter {

    @Value("${spring.gitlab.application.id}")
    private String application_id;

    @Value("${spring.gitlab.application.secret}")
    private String application_secret;

    @Value("${spring.gitlab.redirect.uri}")
    private String redirect_uri;
    private final ExecutorService executorService = Executors.newFixedThreadPool(10);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MemberRepository memberRepository;
    private final OrganisationRepository organisationRepository;
    private final GitlabRepoRepository gitlabRepoRepository;
    private final MobileDetectorService mobileDetectorService;
    private static final Logger LOGGER = LoggerFactory.getLogger(GitlabService.class);

    @Autowired
    public GitlabService(MemberRepository memberRepository,
                         OrganisationRepository organisationRepository,
                         GitlabRepoRepository gitlabRepoRepository, MobileDetectorService mobileDetectorService) {
        this.memberRepository = memberRepository;
        this.organisationRepository = organisationRepository;
        this.gitlabRepoRepository = gitlabRepoRepository;
        this.mobileDetectorService = mobileDetectorService;
    }

    RestTemplate restTemplate = new RestTemplate();

    public HashMap getRefreshedToken(String refreshToken) {
        String url = "https://gitlab.com/oauth/token";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "refresh_token");
        body.add("refresh_token", refreshToken);
        body.add("client_id", application_id);
        body.add("client_secret", application_secret);
        body.add("redirect_uri", redirect_uri);

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<HashMap> response = restTemplate.exchange(
                    url, HttpMethod.POST, request, HashMap.class);
            if (response.getBody() != null) {
                return (HashMap<String, Object>) response.getBody();
            }
            return null;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String newTokens(Member member) {
        HashMap<String, Object> tokenData = getRefreshedToken(member.getVcsRefreshToken());

        long createdAt = ((Number) tokenData.get("created_at")).longValue();
        long expiresIn = ((Number) tokenData.get("expires_in")).longValue();
        LocalDateTime tokenExpiry = getTokenExpiration(createdAt, expiresIn);

        member.setVcsAccessToken(tokenData.get("access_token").toString());
        member.setVcsRefreshToken(tokenData.get("refresh_token").toString());
        member.setVcsTokenExpiry(tokenExpiry);
        Member updatedMember = memberRepository.save(member);
        return updatedMember.getVcsAccessToken();
    }

    @Override
    public Organisation createPersonalOrganisation(User user) {
        GitlabOrganisation personalOrg = GitlabOrganisation.builder()
                .name(user.getUsername())
                .owner(user)
                .type(OrganisationType.PERSONAL)
                .vcsProvider("gitlab")
                .createdAt(LocalDateTime.now())
                .build();
        return organisationRepository.save(personalOrg);
    }

    @Override
    public void createNewMember(User user, String orgId, Map<String, Object> userData) {
        long expiresAt = ((Number) userData.get("expires_at")).longValue();
        LocalDateTime tokenExpiry = LocalDateTime.ofInstant(Instant.ofEpochSecond(expiresAt), ZoneId.systemDefault());
        List<String> orgIds = new ArrayList<>();
        orgIds.add(orgId);
        Member newMember = Member.builder()
                .user(user)
                .organisationIds(orgIds)
                .vcsAccessToken(userData.get("access_token").toString())
                .vcsRefreshToken(userData.get("refresh_token").toString())
                .vcsTokenExpiry(tokenExpiry)
                .build();
        memberRepository.save(newMember);
    }

    @Override
    public void updateExistingMember(User savedUser, Map<String, Object> userData) {
        long expiresAt = ((Number) userData.get("expires_at")).longValue();
        LocalDateTime tokenExpiry = LocalDateTime.ofInstant(Instant.ofEpochSecond(expiresAt), ZoneId.systemDefault());
        memberRepository.findByUser(savedUser).ifPresent(existingMember -> {
            existingMember.setVcsAccessToken(userData.get("access_token").toString());
            existingMember.setVcsRefreshToken(userData.get("refresh_token").toString());
            existingMember.setVcsTokenExpiry(tokenExpiry);
            memberRepository.save(existingMember);
        });
    }

    @Override
    public List<Repo> fetchAndSaveRepositories(User user, String orgId) {
        Organisation organisation = organisationRepository.findById(orgId).orElseThrow(() -> new RuntimeException("Organisation not found"));
        // Casting each GitlabRepo to Repo
        return gitlabRepoRepository.findByOrganisation(organisation)
                .stream()
                .map(repo -> (Repo) repo) // Casting each GitlabRepo to Repo
                .toList();
    }

    @Override
    public List<Organisation> fetchAndUpdateUserOrganizations(User user) {
        List<Organisation> allGroups = new ArrayList<>();

        Optional<Organisation> personalOrgOptional = organisationRepository.findByOwnerAndVcsProviderAndType(user, "gitlab", OrganisationType.PERSONAL);
        personalOrgOptional.ifPresent(allGroups::add);

        Optional<Member> memberOptional = memberRepository.findByUser(user);
        String accessToken;
        Member member = memberOptional.get();
        if (LocalDateTime.now().isAfter(member.getVcsTokenExpiry())) {
            accessToken = newTokens(member);
        } else {
            accessToken = member.getVcsAccessToken();
        }
        List<HashMap<String, Object>> usersGroups = getUsersGroups(accessToken);
        List<Organisation> savedUserGroups = saveUsersGroups(user, usersGroups);
        allGroups.addAll(savedUserGroups);
        updateMemberOrganizations(memberOptional.get(), allGroups);
        return allGroups;
    }

    @Override
    public List<String> fetchRepoBranches(User user, String orgId, String repoId) {
        Member member = memberRepository.findByUser(user).orElseThrow(() -> new RuntimeException("Member not found"));
        String accessToken;
        if (LocalDateTime.now().isAfter(member.getVcsTokenExpiry())) {
            accessToken = newTokens(member);
        } else {
            accessToken = member.getVcsAccessToken();
        }

        GitlabRepo repo = gitlabRepoRepository.findById(repoId).orElseThrow(() -> new RuntimeException("Repository not found"));
        return fetchBranches(repo.getProjectId(), accessToken);
    }

    @Override
    public ScanningResponseDTO startScanning(String repoId, User user) {
        return null;
    }

//    public List<String> fetchRepoBranches(User user, String projectId) {
//        Optional<Member> memberOptional = memberRepository.findByUser(user);
//        if (memberOptional.isEmpty()) {
//            throw new RuntimeException("Member not found");
//        }
//        String vcsAccessToken = memberOptional.get().getVcsAccessToken();
//        return fetchBranches(projectId, vcsAccessToken);
//    }

    public List<String> fetchBranches(String projectId, String accessToken) {
        String userInfoUrl = String.format("https://gitlab.com/api/v4/projects/%s/repository/branches", projectId);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        HttpEntity<String> request = new HttpEntity<>(headers);

        try {
            ResponseEntity<List> response = restTemplate.exchange(
                    userInfoUrl,
                    HttpMethod.GET,
                    request,
                    List.class
            );
            List<HashMap<String, Object>> branches = response.getBody();
            List<String> branchList = new ArrayList<>();

            if (branches != null) {
                for (HashMap<String, Object> branch : branches) {
                    branchList.add(branch.get("name").toString());
                }
            }
            return branchList;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private List<Organisation> saveUsersGroups(User user, List<HashMap<String, Object>> groups) {
        List<Organisation> savedUsersGroups = new ArrayList<>();
        for (HashMap<String, Object> group : groups) {
            Optional<GitlabOrganisation> existingGroupOptional = organisationRepository.findByVcsProviderAndGroupId("gitlab", group.get("id").toString());

            if (existingGroupOptional.isPresent()) {
                savedUsersGroups.add(existingGroupOptional.get());
            } else {
                GitlabOrganisation newGroup = GitlabOrganisation.builder()
                        .name(group.get("name").toString())
                        .groupId(group.get("id").toString())
                        .owner(user)
                        .vcsProvider("gitlab")
                        .type(OrganisationType.WORK)
                        .createdAt(LocalDateTime.now())
                        .build();
                GitlabOrganisation savedGroup = organisationRepository.save(newGroup);
                savedUsersGroups.add(savedGroup);
            }
        }
        return savedUsersGroups;
    }

    private void updateMemberOrganizations(Member member, List<Organisation> allOrgs) {
        Set<String> updatedOrgIds = new HashSet<>();

        for (Organisation org : allOrgs) {
            updatedOrgIds.add(org.getId());
        }

        member.setOrganisationIds(new ArrayList<>(updatedOrgIds));
        memberRepository.save(member);
    }

    private List<HashMap<String, Object>> getUsersPersonalProjects(String accessToken) {
        String userInfoUrl = "https://gitlab.com/api/v4/projects?owned=true";
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        HttpEntity<String> request = new HttpEntity<>(headers);

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    userInfoUrl,
                    HttpMethod.GET,
                    request,
                    String.class
            );
            ObjectMapper mapper = new ObjectMapper();
            JsonNode projects = mapper.readTree(response.getBody());

//          List to store project IDs and names
            List<HashMap<String, Object>> personalProjects = new ArrayList<>();

//          Filter projects and extract only ID and name of personal projects
            for (JsonNode project : projects) {
                String namespaceKind = project.get("namespace").get("kind").asText();
                if ("user".equals(namespaceKind)) {
                    HashMap<String, Object> projectData = new HashMap<>();
                    projectData.put("projectId", project.get("id").asInt());
                    projectData.put("name", project.get("name").asText());

                    String language = getLanguage(project.get("id").asText(), accessToken);
                    projectData.put("language", language);

                    projectData.put("isPrivate", "private".equalsIgnoreCase(project.get("visibility").asText()));
                    personalProjects.add(projectData);
                }
            }
            return personalProjects;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private List<HashMap<String, Object>> getUsersGroups(String accessToken) {
        String userInfoUrl = "https://gitlab.com/api/v4/groups";
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        HttpEntity<String> request = new HttpEntity<>(headers);

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    userInfoUrl,
                    HttpMethod.GET,
                    request,
                    String.class
            );
            ObjectMapper mapper = new ObjectMapper();
            JsonNode groups = mapper.readTree(response.getBody());

//          List to store project IDs and names
            List<HashMap<String, Object>> groupList = new ArrayList<>();

//          Filter projects and extract only ID and name of personal projects
            for (JsonNode group : groups) {
                HashMap<String, Object> groupData = new HashMap<>();
                groupData.put("id", group.get("id").asInt());
                groupData.put("name", group.get("name").asText());
                groupList.add(groupData);
            }
            return groupList;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public List<HashMap<String, Object>> getGroupProjects(User user, String orgType, String groupId) {

        Optional<Member> memberOptional = memberRepository.findByUser(user);
        if (memberOptional.isPresent()) {
            Member member = memberOptional.get();
            String accessToken;
            if (LocalDateTime.now().isAfter(member.getVcsTokenExpiry())) {
                accessToken = newTokens(member);
            } else {
                accessToken = member.getVcsAccessToken();
            }
            if (orgType.equalsIgnoreCase("PERSONAL")) {
                return getUsersPersonalProjects(accessToken);
            }
            List<HashMap<String, Object>> groupProjects = getProjectsInGroup(accessToken, groupId);
            return groupProjects;
        }
        return null;
    }

    private List<HashMap<String, Object>> getProjectsInGroup(String accessToken, String groupId) {
        String userInfoUrl = "https://gitlab.com/api/v4/groups/" + groupId + "/projects";
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        HttpEntity<String> request = new HttpEntity<>(headers);

        try {
            ResponseEntity<List> response = restTemplate.exchange(
                    userInfoUrl,
                    HttpMethod.GET,
                    request,
                    List.class
            );
            List<HashMap<String, Object>> projects = response.getBody();
            List<HashMap<String, Object>> simplifiedProjects = new ArrayList<>();

            if (projects != null) {
                for (HashMap<String, Object> project : projects) {
                    // Create a new map containing only the 'id' and 'name'
                    HashMap<String, Object> projectSummary = new HashMap<>();
                    projectSummary.put("projectId", project.get("id"));
                    projectSummary.put("name", project.get("name"));

                    String language = getLanguage(project.get("id").toString(), accessToken);
                    projectSummary.put("language", language);

                    projectSummary.put("isPrivate", "private".equalsIgnoreCase((String) project.get("visibility")));


                    // Add the simplified project to the list
                    simplifiedProjects.add(projectSummary);
                }
            }
            return simplifiedProjects;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void saveProjects(User user, HashMap<String, Object> projectData) {
        try {
            Member member = memberRepository.findByUser(user).orElseThrow(() -> new RuntimeException("Member not found"));
            String accessToken;
            if (LocalDateTime.now().isAfter(member.getVcsTokenExpiry())) {
                accessToken = newTokens(member);
            } else {
                accessToken = member.getVcsAccessToken();
            }

            List<GitlabRepo> newGitlabRepos = new ArrayList<>();

            List<HashMap<String, Object>> repos = (List<HashMap<String, Object>>) projectData.get("repos");

            String orgId = projectData.get("orgId").toString();

            GitlabOrganisation organisation = (GitlabOrganisation) organisationRepository.findById(orgId).orElseThrow(() -> new RuntimeException("Organisation not found"));


            for (HashMap<String, Object> repo : repos) {
                String projectId = repo.get("projectId").toString();

                GitlabRepo repository = gitlabRepoRepository.findByProjectId(projectId).orElse(GitlabRepo.builder().build());

                if (repository.getState() != RepoState.COMPATIBLE && repository.getState() != RepoState.INCOMPATIBLE) {

//                    String language = getLanguage(projectId, accessToken);
                    repo.put("language", repo.get("language"));
                    updateRepoBasicInfo(repository, repo, organisation);
                    repository.setState(RepoState.FETCHING);
                    GitlabRepo savedRepo = gitlabRepoRepository.save(repository);
                    newGitlabRepos.add(repository);

                    CompletableFuture.runAsync(() -> {
                        try {
                            processRepo(savedRepo, accessToken);
                        } catch (Exception e) {
                            LOGGER.error("Error processing repo: {}", repository.getName(), e);
                            updateRepoState(savedRepo, RepoState.ERROR);
                        }
                    }, executorService);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void updateRepoBasicInfo(GitlabRepo repository, Map<String, Object> repoData, GitlabOrganisation org) {
        repository.setName(repoData.get("name").toString());
        repository.setProjectId(repoData.get("projectId").toString());
        repository.setPrivate((Boolean) repoData.get("isPrivate"));
        repository.setLanguage(repoData.get("language").toString());
        repository.setOrganisation(org);
        repository.setCreatedAt(LocalDateTime.now());
    }

    private void processRepo(GitlabRepo repo, String accessToken) {
        try {
            if (repo.getState() == RepoState.COMPATIBLE ||
                    repo.getState() == RepoState.INCOMPATIBLE) {
                return;
            }

            updateRepoState(repo, RepoState.ANALYZING);

            List<RepoFile> files = getAllRepositoryFiles(repo.getProjectId(), accessToken);

            MobileProjectInfoDTO mobileInfo = mobileDetectorService.analyzeMobileProject(files);

            repo.setMobile(mobileInfo.isMobile());
            repo.setPlatform(mobileInfo.getPlatform());

            RepoState newState = mobileInfo.isMobile() ? RepoState.COMPATIBLE : RepoState.INCOMPATIBLE;
            updateRepoState(repo, newState);

        } catch (Exception e) {
            LOGGER.error("Error processing repo: {}", repo.getName(), e);
            updateRepoState(repo, RepoState.ERROR);
        }
    }

    private void updateRepoState(GitlabRepo repository, RepoState state) {
        repository.setState(state);
        gitlabRepoRepository.save(repository);
    }

    public List<RepoFile> getAllRepositoryFiles(String projectId, String token) throws InterruptedException, ExecutionException {
        return getRepositoryContentsRecursively(projectId, "", token);
    }

    private List<RepoFile> getRepositoryContentsRecursively(String projectId, String path, String token) throws InterruptedException, ExecutionException {
        List<RepoFile> allFiles = new CopyOnWriteArrayList<>();
        String encodedPath = java.net.URLEncoder.encode(path, StandardCharsets.UTF_8);
        String baseUrl = String.format("https://gitlab.com/api/v4/projects/%s/repository/tree?path=%s&recursive=true&per_page=100", projectId, encodedPath);

        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
            try {
                fetchAllPages(baseUrl, token, allFiles);
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        }, executorService);

        future.get(); // Wait for all pages to be fetched
        System.out.println(allFiles.size());
        return allFiles;
    }

    private void fetchAllPages(String baseUrl, String token, List<RepoFile> allFiles) throws IOException {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + token);
        headers.set("Accept", "application/json");
        HttpEntity<String> entity = new HttpEntity<>(headers);

        int page = 1;
        boolean hasMorePages = true;

        while (hasMorePages) {
            String url = baseUrl + "&page=" + page;
            try {
                String response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class).getBody();
                JsonNode root = objectMapper.readTree(response);

                if (root.isEmpty()) {
                    hasMorePages = false;
                } else {
                    for (JsonNode node : root) {
                        String type = node.get("type").asText();
                        // Only add items of type "blob" (files) to the list
                        if ("blob".equals(type)) {
                            allFiles.add(new RepoFile(
                                    node.get("name").asText(),
                                    node.get("path").asText(),
                                    type
                            ));
                        }
                    }
                    page++;
                }
            } catch (HttpClientErrorException.NotFound e) {
                // If we get a 404 for a specific page, we've reached the end
                hasMorePages = false;
            }
        }
    }

    public void shutdown() {
        executorService.shutdown();
    }

    private String getLanguage(String projectId, String accessToken) {
        String languageInfoUrl = "https://gitlab.com/api/v4/projects/" + projectId + "/languages";
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);

        HttpEntity<Void> requestEntity = new HttpEntity<>(headers);

        try {
            ResponseEntity<Map<String, Double>> response = restTemplate.exchange(
                    languageInfoUrl, HttpMethod.GET, requestEntity,
                    (Class<Map<String, Double>>) (Class<?>) Map.class
            );

            if (response.getStatusCode().is2xxSuccessful()) {
                Map<String, Double> languages = response.getBody();

                if (languages != null && !languages.isEmpty()) {
                    Optional<Map.Entry<String, Double>> mostUsedLanguage = languages.entrySet()
                            .stream()
                            .max(Map.Entry.comparingByValue());

                    return mostUsedLanguage.map(Map.Entry::getKey).orElse(null);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return null;
    }

//    public Boolean setBranchesInRepo(String primaryBranch, String secondaryBranch, String repoId) {
//        try {
//            Optional<GitlabRepo> repoOptional = gitlabRepoRepository.findById(repoId);
//            if (repoOptional.isEmpty()) {
//                throw new RuntimeException("Repository not found");
//            }
//            GitlabRepo repo = repoOptional.get();
//            repo.setPrimaryBranch(primaryBranch);
//            repo.setSecondaryBranch(secondaryBranch);
//            gitlabRepoRepository.save(repo);
//            return true;
//        } catch (Exception e) {
//            throw new RuntimeException(e.getMessage());
//        }
//    }

    private void handleHttpClientError(HttpClientErrorException ex) {
        if (ex.getStatusCode().equals(HttpStatus.UNAUTHORIZED)) {
            throw new HttpClientErrorException(HttpStatus.UNAUTHORIZED, "Invalid or expired access token");
        } else {
            throw new RuntimeException("Error fetching data from GitHub: " + ex.getMessage(), ex);
        }
    }

    public LocalDateTime getTokenExpiration(long createdAt, long expirationInSec) {
        ZonedDateTime createdAtUtc = Instant.ofEpochSecond(createdAt).atZone(ZoneId.systemDefault());

        // Add the expires_in value to get the exact expiration time
        ZonedDateTime expiryTime = createdAtUtc.plusSeconds(expirationInSec);
        return expiryTime.toLocalDateTime();
    }

    private String extractErrorMessage(String response) {
        try {
            Map<String, Object> responseMap = new ObjectMapper().readValue(response, Map.class);
            String error = responseMap.getOrDefault("error", "Unknown error").toString();
            String errorDescription = responseMap.getOrDefault("error_description", "No description provider").toString();
            return "Error: " + error + "/n Error Description: " + errorDescription;
        } catch (JsonProcessingException e) {
        }
        return "Failed to parse error message from Gitlab response";
    }
}
