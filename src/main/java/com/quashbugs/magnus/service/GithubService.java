package com.quashbugs.magnus.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quashbugs.magnus.adapter.VcsAdapter;
import com.quashbugs.magnus.controller.SSEController;
import com.quashbugs.magnus.dto.*;
import com.quashbugs.magnus.model.*;
import com.quashbugs.magnus.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Service
public class GithubService implements VcsAdapter {

    @Value("${spring.github.client.id}")
    private String githubClientId;

    @Value("${spring.github.client.secret}")
    private String githubClientSecret;

    @Value("${spring.github.redirect_url}")
    private String githubRedirectUrl;

    @Value("${spring.github.app.installation.url}")
    private String githubInstallationUrl;

    @Value("${spring.github.app.id}")
    private String githubAppId;

    @Value("${spring.github.app.private.key}")
    private String privateKeyPEM;

    @Value("${spring.github.webhook.secret}")
    private String webhookSecret;

    @Value("${spring.scanning.analysis.url}")
    private String scanningAnalysisUrl;

    @Value("${spring.pr.analysis.url}")
    private String prAnalysisUrl;

    @Value("${spring.scanning.callback.url}")
    private String scanningCallbackUrl;

    @Value("${spring.pr.callback.url}")
    private String prCallbackUrl;

    private static final String GITHUB_API_BASE_URL = "https://api.github.com";

    private final JwtService jwtService;
    private final OrganisationRepository organisationRepository;
    private final MemberRepository memberRepository;
    private final RepoRepository repoRepository;
    private final ObjectMapper objectMapper;
    private final ExecutorService executorService;
    private final MobileDetectorService mobileDetectorService;
    private final UserRepository userRepository;
    private final PullRequestRepository pullRequestRepository;
    private final ConfigurationRepository configurationRepository;
    private final SSEController sseController;
    private final DataEncryptionService dataEncryptionService;
    private static final Logger LOGGER = LoggerFactory.getLogger(GithubService.class);

    @Autowired
    public GithubService(JwtService jwtService,
                         OrganisationRepository organisationRepository,
                         MemberRepository memberRepository,
                         RepoRepository repoRepository,
                         ObjectMapper objectMapper,
                         MobileDetectorService mobileDetectorService,
                         UserRepository userRepository,
                         PullRequestRepository pullRequestRepository,
                         ConfigurationRepository configurationRepository,
                         SSEController sseController,
                         DataEncryptionService dataEncryptionService) {
        this.jwtService = jwtService;
        this.organisationRepository = organisationRepository;
        this.memberRepository = memberRepository;
        this.repoRepository = repoRepository;
        this.objectMapper = objectMapper;
        this.userRepository = userRepository;
        this.pullRequestRepository = pullRequestRepository;
        this.configurationRepository = configurationRepository;
        this.sseController = sseController;
        this.dataEncryptionService = dataEncryptionService;
        this.executorService = Executors.newFixedThreadPool(10);
        this.mobileDetectorService = mobileDetectorService;
    }

    public String getAuthorizationUrl() {
        return String.format("https://github.com/login/oauth/authorize?client_id=%s&scope=user,read:org,user:email",
                githubClientId);
    }

    public User getUserInfo(String accessToken) {
        validateAccessToken(accessToken);

        HttpHeaders headers = createHeaders(accessToken);
        HttpEntity<?> entity = new HttpEntity<>(headers);

        // Fetch basic user info
        Map<String, Object> basicUserInfo = fetchBasicUserInfo(entity);
        String username = (String) basicUserInfo.get("login");

        // Fetch primary email
        String email = fetchPrimaryEmail(entity);

        // Create and return user object
        return buildUser(username, email);
    }

    private void validateAccessToken(String accessToken) {
        if (accessToken == null || accessToken.trim().isEmpty()) {
            throw new IllegalArgumentException("Access token cannot be null or empty");
        }
    }

    private HttpHeaders createHeaders(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.set("Accept", MediaType.APPLICATION_JSON_VALUE);
        return headers;
    }

    private Map<String, Object> fetchBasicUserInfo(HttpEntity<?> entity) {
        try {
            ResponseEntity<Map<String, Object>> response = new RestTemplate().exchange(
                    "https://api.github.com/user",
                    HttpMethod.GET,
                    entity,
                    new ParameterizedTypeReference<Map<String, Object>>() {
                    }
            );
            if (response.getStatusCode().is2xxSuccessful()) {
                return response.getBody();
            }
            throw new RuntimeException("Failed to fetch user info from GitHub");
        } catch (HttpClientErrorException ex) {
            handleHttpClientError(ex);
        } catch (Exception ex) {
            throw new RuntimeException("Error fetching user info from GitHub: " + ex.getMessage(), ex);
        }
        return null;
    }

    private String fetchPrimaryEmail(HttpEntity<?> entity) {
        try {
            ResponseEntity<List<Map<String, Object>>> response = new RestTemplate().exchange(
                    "https://api.github.com/user/emails",
                    HttpMethod.GET,
                    entity,
                    new ParameterizedTypeReference<List<Map<String, Object>>>() {
                    }
            );
            if (response.getStatusCode().is2xxSuccessful()) {
                return extractPrimaryEmail(response.getBody());
            }
            throw new RuntimeException("Failed to fetch email info from GitHub");
        } catch (HttpClientErrorException ex) {
            handleHttpClientError(ex);
        } catch (Exception ex) {
            throw new RuntimeException("Error fetching user emails from GitHub: " + ex.getMessage(), ex);
        }
        return null;
    }

    private String extractPrimaryEmail(List<Map<String, Object>> emails) {
        if (emails != null) {
            for (Map<String, Object> emailEntry : emails) {
                Boolean primary = (Boolean) emailEntry.get("primary");
                Boolean verified = (Boolean) emailEntry.get("verified");
                if (primary != null && primary && verified != null && verified) {
                    return (String) emailEntry.get("email");
                }
            }
        }
        return null;
    }

    private void handleHttpClientError(HttpClientErrorException ex) {
        if (ex.getStatusCode().equals(HttpStatus.UNAUTHORIZED)) {
            throw new HttpClientErrorException(HttpStatus.UNAUTHORIZED, "Invalid or expired access token");
        } else {
            throw new RuntimeException("Error fetching data from GitHub: " + ex.getMessage(), ex);
        }
    }

    private User buildUser(String username, String email) {
        return User.builder()
                .username(username)
                .workEmail(email)
                .vcsProvider("github")
                .build();
    }

    @Override
    public List<Organisation> fetchAndUpdateUserOrganizations(User user) {
        return memberRepository.findByUser(user)
                .map(member -> {
                    List<Organisation> allOrgs = new ArrayList<>();

                    try {
                        Optional<Organisation> personalOrg = organisationRepository.findByOwnerAndType(user, OrganisationType.PERSONAL);
                        personalOrg.ifPresent(allOrgs::add);

                        String githubResponse = fetchGithubOrganizations(member.getVcsAccessToken());
                        List<String> githubOrgLogins = parseGithubResponse(githubResponse);

                        for (String login : githubOrgLogins) {
                            Organisation org = createOrUpdateOrganisation(user, login);
                            allOrgs.add(org);
                        }

                        updateMemberOrganizations(member, allOrgs);

                        return allOrgs;
                    } catch (Exception ex) {
                        throw new RuntimeException("Error processing organizations: " + ex.getMessage(), ex);
                    }
                })
                .orElseThrow(() -> new RuntimeException("Member not found for user"));
    }

    private Organisation createOrUpdateOrganisation(User user, String orgName) {
        validateUser(user);
        validateOrgData(orgName);

        Optional<Organisation> existingOrg = organisationRepository.findByNameAndVcsProvider(orgName, "github");
        Organisation organisation;

        organisation = existingOrg.orElseGet(() -> GithubOrganisation.builder()
                .name(orgName)
                .type(OrganisationType.WORK)
                .vcsProvider("github")
                .createdAt(LocalDateTime.now())
                .build());

        if (organisation.getOwner() == null) {
            organisation.setOwner(user);
        }

        return organisationRepository.save(organisation);
    }

    private void updateMemberOrganizations(Member member, List<Organisation> allOrgs) {
        Set<String> updatedOrgIds = new HashSet<>();

        for (Organisation org : allOrgs) {
            updatedOrgIds.add(org.getId());
        }

        member.setOrganisationIds(new ArrayList<>(updatedOrgIds));
        memberRepository.save(member);
    }

    private String fetchGithubOrganizations(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + accessToken);
        HttpEntity<String> entity = new HttpEntity<>(headers);

        ResponseEntity<String> response = new RestTemplate().exchange(
                "https://api.github.com/user/orgs",
                HttpMethod.GET,
                entity,
                String.class
        );

        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
            return response.getBody();
        } else {
            throw new RuntimeException("Failed to fetch organizations from GitHub");
        }
    }

    private List<String> parseGithubResponse(String responseBody) throws JsonProcessingException {
        JsonNode rootNode = objectMapper.readTree(responseBody);
        List<String> orgLogins = new ArrayList<>();

        if (rootNode.isArray()) {
            for (JsonNode orgNode : rootNode) {
                orgLogins.add(orgNode.path("login").asText());
            }
        } else {
            throw new RuntimeException("Unexpected response format from GitHub API");
        }

        return orgLogins;
    }

    private void validateUser(User user) {
        if (user == null || user.getId() == null) {
            throw new IllegalArgumentException("User cannot be null or must exist in the system.");
        }
    }

    private void validateOrgData(String orgName) {
        if (orgName == null || orgName.isEmpty()) {
            throw new IllegalArgumentException("Invalid organization data provided.");
        }
    }

    @Override
    public void createNewMember(User user, String orgId, Map<String, Object> userData) {
        List<String> orgIds = new ArrayList<>();
        orgIds.add(orgId);
        Member newMember = Member.builder()
                .user(user)
                .organisationIds(orgIds)
                .vcsAccessToken(userData.get("access_token").toString())
                .build();
        memberRepository.save(newMember);
    }

    @Override
    public void updateExistingMember(User savedUser, Map<String, Object> userData) {
        memberRepository.findByUser(savedUser).ifPresent(existingMember -> {
            existingMember.setVcsAccessToken(userData.get("access_token").toString());
            memberRepository.save(existingMember);
        });
    }

    @Override
    public Organisation createPersonalOrganisation(User owner) {
        GithubOrganisation personalOrg = GithubOrganisation.builder()
                .name(owner.getUsername())
                .type(OrganisationType.PERSONAL)
                .owner(owner)
                .vcsProvider("github")
                .createdAt(LocalDateTime.now())
                .build();

        organisationRepository.save(personalOrg);
        return personalOrg;
    }

    public String getInstallationUrl() {
        return githubInstallationUrl;
    }

    public Organisation getInstallationAccessToken(User user, String installationId) throws Exception {
        JwtResponseDTO jwtResponse = jwtService.generateJwt(githubAppId, privateKeyPEM);
        String jwt = jwtResponse.getToken();

        Map<String, Object> orgData = getInstallationOrganization(installationId, jwt);
        String orgName = (String) orgData.get("login");

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwt);
        headers.setAccept(List.of(MediaType.parseMediaType("application/vnd.github.v3+json")));

        HttpEntity<String> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<Map> response = new RestTemplate().exchange(
                    "https://api.github.com/app/installations/" + installationId + "/access_tokens",
                    HttpMethod.POST,
                    entity,
                    Map.class
            );

            if (response.getBody() != null && response.getBody().containsKey("token")) {
                String token = (String) response.getBody().get("token");

                String expiresAtStr = (String) response.getBody().get("expires_at");
                DateTimeFormatter formatter = DateTimeFormatter.ISO_DATE_TIME;
                LocalDateTime githubAccessTokenExpiry = LocalDateTime.parse(expiresAtStr, formatter);

                GithubOrganisation org = (GithubOrganisation) organisationRepository.findByNameAndVcsProvider(orgName, "github")
                        .orElseGet(() -> {
                            return GithubOrganisation.builder()
                                    .name(orgName)
                                    .type(OrganisationType.WORK)
                                    .vcsProvider("github")
                                    .createdAt(LocalDateTime.now())
                                    .build();
                        });

                if (org.getOwner() == null) {
                    org.setOwner(user);
                }

                org.setGithubInstallationId(installationId);
                org.setGithubInstallationToken(jwt);
                org.setGithubAccessToken(token);
                org.setGithubInstallationTokenExpiry(jwtResponse.getExpiry());
                org.setGithubAccessTokenExpiry(githubAccessTokenExpiry);

                organisationRepository.save(org);

                return org;
            } else {
                throw new RuntimeException("Unexpected response from GitHub API");
            }
        } catch (HttpClientErrorException e) {
            throw new RuntimeException("Error calling GitHub API: " + e.getMessage(), e);
        }
    }

    private Map<String, Object> getInstallationOrganization(String installationId, String token) throws Exception {
        String url = "https://api.github.com/app/installations/" + installationId;

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.set("Accept", "application/vnd.github.v3+json");

        HttpEntity<String> entity = new HttpEntity<>(headers);

        ResponseEntity<Map> response = new RestTemplate().exchange(url, HttpMethod.GET, entity, Map.class);
        Map<String, Object> installation = response.getBody();

        if (installation != null && installation.containsKey("account")) {
            return (Map<String, Object>) installation.get("account");
        }

        throw new Exception("Unable to fetch organization data");
    }

    @Override
    public List<Repo> fetchAndSaveRepositories(User user, String orgId) {
        GithubOrganisation org = (GithubOrganisation) organisationRepository.findById(orgId)
                .orElseThrow(() -> new RuntimeException("Organisation not found"));

        String accessToken = getValidAccessToken(org);
        List<Map<String, Object>> repoData = fetchReposFromGithub(accessToken);

        List<Repo> repositories = new ArrayList<>();
        Set<String> currentRepoNames = new HashSet<>();

        for (Map<String, Object> repo : repoData) {
            String repoName = (String) repo.get("name");
            currentRepoNames.add(repoName);

            Repo repository = repoRepository.findByNameAndOrganisation(repoName, org)
                    .orElse(Repo.builder().build());

            if (repository.getState() != RepoState.COMPATIBLE &&
                    repository.getState() != RepoState.INCOMPATIBLE) {

                updateRepoBasicInfo(repository, repo, org);
                repository.setState(RepoState.FETCHING);
                Repo savedRepo = repoRepository.save(repository);
                repositories.add(savedRepo);

                CompletableFuture.runAsync(() -> {
                    try {
                        processRepo(savedRepo, accessToken);
                    } catch (Exception e) {
                        LOGGER.error("Error processing repo: {}", repoName, e);
                        updateRepoState(savedRepo, RepoState.ERROR);
                    }
                }, executorService);
            } else {
                updateRepoBasicInfo(repository, repo, org);
                repositories.add(repoRepository.save(repository));
            }
        }

        if (!user.isOnboarded()) {
            user.setOnboarded(true);
            userRepository.save(user);
        }

        removeStaleRepos(org, currentRepoNames);
        return repositories;
    }

    @Override
    public List<String> fetchRepoBranches(User user, String orgId, String repoId) {
        GithubOrganisation organisation = (GithubOrganisation) organisationRepository.findById(orgId).orElseThrow(() -> new RuntimeException("Organisation not found"));

        Repo repo = repoRepository.findById(repoId).orElseThrow(() -> new RuntimeException("Repository not found"));

        return fetchBranches(organisation.getName(), repo.getName());
    }

    private void processRepo(Repo repo, String accessToken) {
        try {
            if (repo.getState() == RepoState.COMPATIBLE ||
                    repo.getState() == RepoState.INCOMPATIBLE) {
                return;
            }

            updateRepoState(repo, RepoState.ANALYZING);

            List<RepoFile> files = getAllRepositoryFiles(
                    repo.getOrganisation().getName(),
                    repo.getName(),
                    accessToken
            );

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

    private void updateRepoState(Repo repository, RepoState state) {
        repository.setState(state);
        repoRepository.save(repository);
    }


    private void removeStaleRepos(GithubOrganisation org, Set<String> currentRepoNames) {
        List<Repo> existingRepos = repoRepository.findAllByOrganisation(org)
                .orElse(Collections.emptyList());

        for (Repo existingRepo : existingRepos) {
            if (!currentRepoNames.contains(existingRepo.getName())) {
                repoRepository.delete(existingRepo);
            }
        }
    }

    private void updateRepoBasicInfo(Repo repository, Map<String, Object> repoData, GithubOrganisation org) {
        repository.setName((String) repoData.get("name"));
        repository.setPrivate((Boolean) repoData.get("private"));
        repository.setLanguage((String) repoData.get("language"));
        repository.setOrganisation(org);
        repository.setCreatedAt(LocalDateTime.parse((String) repoData.get("created_at"), DateTimeFormatter.ISO_DATE_TIME));
    }

    private List<Map<String, Object>> fetchReposFromGithub(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setAccept(List.of(MediaType.parseMediaType("application/vnd.github.v3+json")));

        HttpEntity<String> entity = new HttpEntity<>(headers);

        ResponseEntity<Map<String, Object>> response = new RestTemplate().exchange(
                "https://api.github.com/installation/repositories",
                HttpMethod.GET,
                entity,
                new ParameterizedTypeReference<Map<String, Object>>() {
                }
        );

        Map<String, Object> body = response.getBody();
        if (body != null && body.containsKey("repositories")) {
            return (List<Map<String, Object>>) body.get("repositories");
        } else {
            throw new RuntimeException("Unexpected response structure from GitHub API");
        }
    }

//    private void processRepoAsync(Repo repository, String accessToken) {
//        try {
//            updateAndNotify(repository, RepoState.FETCHING);
//
////            String defaultBranch = fetchDefaultBranch(accessToken, repository.getOrganisation().getName(), repository.getName());
////            repository.setBranch(defaultBranch);
//
//            updateAndNotify(repository, RepoState.ANALYZING);
//
//            List<RepoFile> files = getAllRepositoryFiles(repository.getOrganisation().getName(), repository.getName(), accessToken);
//            MobileProjectInfoDTO projectInfo = mobileDetectorService.analyzeMobileProject(files);
//
//            if (projectInfo != null) {
//                repository.setMobile(projectInfo.isMobile());
//                repository.setPlatform(projectInfo.getPlatform());
//                repository.setState(projectInfo.isMobile() ? RepoState.COMPATIBLE : RepoState.INCOMPATIBLE);
//            } else {
//                repository.setState(RepoState.ERROR);
//            }
//        } catch (Exception e) {
//            LOGGER.error("Error processing repo: {}", repository.getName(), e);
//            repository.setState(RepoState.ERROR);
//        }
//
//        updateAndNotify(repository, repository.getState());
//        repoRepository.save(repository);
//    }

//    private void updateAndNotify(Repo repository, RepoState state) {
//        repository.setState(state);
//        RepoUpdateDTO updateDTO = new RepoUpdateDTO(
//                repository.getId(),
//                repository.getName(),
//                state,
//                repository.isMobile(),
//                repository.getPlatform()
//        );
//        sseController.sendUpdate(repository.getOrganisation().getId(), updateDTO);
//    }

    private String fetchDefaultBranch(String accessToken, String orgName, String repoName) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setAccept(List.of(MediaType.parseMediaType("application/vnd.github.v3+json")));
        HttpEntity<String> entity = new HttpEntity<>(headers);

        String url = String.format("https://api.github.com/repos/%s/%s", orgName, repoName);
        ResponseEntity<Map<String, Object>> response = new RestTemplate().exchange(
                url,
                HttpMethod.GET,
                entity,
                new ParameterizedTypeReference<Map<String, Object>>() {
                }
        );

        Map<String, Object> body = response.getBody();
        if (body != null && body.containsKey("default_branch")) {
            return (String) body.get("default_branch");
        } else {
            throw new RuntimeException("Unable to fetch default branch for repository: " + repoName);
        }
    }

    public List<String> fetchBranches(String orgName, String repoName) {
        Optional<GithubOrganisation> optionalOrganisation = organisationRepository.findGithubOrganisationByNameAndVcsProvider(orgName, "github");

        GithubOrganisation organisation = optionalOrganisation.orElseThrow(() ->
                new RuntimeException("GitHub organisation not found for name: " + orgName)
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(getValidAccessToken(organisation));
        headers.setAccept(List.of(MediaType.parseMediaType("application/vnd.github.v3+json")));
        HttpEntity<String> entity = new HttpEntity<>(headers);

        String url = String.format("https://api.github.com/repos/%s/%s/branches", orgName, repoName);

        ResponseEntity<List<Map<String, Object>>> response = new RestTemplate().exchange(
                url,
                HttpMethod.GET,
                entity,
                new ParameterizedTypeReference<>() {
                }
        );

        List<Map<String, Object>> body = response.getBody();
        if (body != null) {
            // Extract branch names from the response
            return body.stream()
                    .map(branch -> (String) branch.get("name"))
                    .collect(Collectors.toList());
        } else {
            throw new RuntimeException("Unable to fetch branches for repository: " + repoName);
        }
    }


//    private Map<String, Long> fetchLanguages(String accessToken, String orgName, String repoName) {
//        HttpHeaders headers = new HttpHeaders();
//        headers.setBearerAuth(accessToken);
//        headers.setAccept(List.of(MediaType.parseMediaType("application/vnd.github.v3+json")));
//        HttpEntity<String> entity = new HttpEntity<>(headers);
//
//        String url = String.format("https://api.github.com/repos/%s/%s/languages", orgName, repoName);
//        ResponseEntity<Map<String, Long>> response = new RestTemplate().exchange(
//                url,
//                HttpMethod.GET,
//                entity,
//                new ParameterizedTypeReference<Map<String, Long>>() {
//                }
//        );
//
//        Map<String, Long> body = response.getBody();
//        if (body != null) {
//            return body;
//        } else {
//            return new HashMap<>(); // Return an empty map if no languages are found
//        }
//    }

//    private List<String> fetchBranches(String fullRepoName, String accessToken) {
//        HttpHeaders headers = new HttpHeaders();
//        headers.setBearerAuth(accessToken);
//        headers.setAccept(List.of(MediaType.parseMediaType("application/vnd.github.v3+json")));
//
//        HttpEntity<String> entity = new HttpEntity<>(headers);
//
//        ResponseEntity<List<Map<String, Object>>> response = new RestTemplate().exchange(
//                "https://api.github.com/repos/" + fullRepoName + "/branches",
//                HttpMethod.GET,
//                entity,
//                new ParameterizedTypeReference<List<Map<String, Object>>>() {}
//        );
//
//        List<Map<String, Object>> branches = response.getBody();
//        return branches != null ? branches.stream()
//                .map(branch -> (String) branch.get("name"))
//                .collect(Collectors.toList()) : Collections.emptyList();
//    }

    private List<RepoFile> getAllRepositoryFiles(String repoOwner, String repoName, String token) throws InterruptedException, ExecutionException {
        List<RepoFile> allFiles = new CopyOnWriteArrayList<>();
        Queue<String> directories = new ConcurrentLinkedQueue<>();
        directories.add(""); // Start with root directory

        while (!directories.isEmpty()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < Math.min(directories.size(), 10); i++) { // Process up to 10 directories concurrently
                String dir = directories.poll();
                futures.add(executorService.submit(() -> {
                    try {
                        List<RepoFile> files = getRepositoryContents(repoOwner, repoName, dir, token);
                        for (RepoFile file : files) {
                            if ("dir".equals(file.getType())) {
                                directories.add(file.getPath());
                            } else {
                                allFiles.add(file);
                            }
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }));
            }
            for (Future<?> future : futures) {
                future.get(); // Wait for all concurrent tasks to complete
            }
        }

        return allFiles;
    }

    private List<RepoFile> getRepositoryContents(String repoOwner, String repoName, String path, String token) throws IOException {
        String url = String.format("https://api.github.com/repos/%s/%s/contents/%s", repoOwner, repoName, path);

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + token);
        headers.set("Accept", "application/vnd.github.v3+json");

        HttpEntity<String> entity = new HttpEntity<>(headers);

        String response = new RestTemplate().exchange(url, HttpMethod.GET, entity, String.class).getBody();

        List<RepoFile> files = new ArrayList<>();
        JsonNode root = objectMapper.readTree(response);
        for (JsonNode node : root) {
            files.add(new RepoFile(
                    node.get("name").asText(),
                    node.get("path").asText(),
                    node.get("type").asText()
            ));
        }
        return files;
    }

//    public boolean isSignatureValid(String payload, String signature) {
//        try {
//            Mac sha256Hmac = Mac.getInstance("HmacSHA256");
//            SecretKeySpec secretKey = new SecretKeySpec(webhookSecret.getBytes(), "HmacSHA256");
//            sha256Hmac.init(secretKey);
//            String computedSignature = "sha256=" + Hex.encodeHexString(sha256Hmac.doFinal(payload.getBytes()));
//            return computedSignature.equals(signature);
//        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
//            e.printStackTrace();
//            return false;
//        }
//    }
//
//    public void addRepository(JsonNode repoNode, String orgName) {
//        GithubOrganisation org = organisationRepository.findGithubOrganisationByNameAndVcsProvider(orgName, "github")
//                .orElseThrow(() -> new RuntimeException("Organisation not found: " + orgName));
//
//        String repoName = repoNode.get("full_name").asText();
//        Repo repository = repoRepository.findByNameAndOrganisation(repoName, org)
//                .orElse(Repo.builder().build());
//
//        updateRepoBasicInfo(repository, repoNode, org);
//        repository.setState(RepoState.FETCHING);
//        Repo savedRepo = repoRepository.save(repository);
//
//        CompletableFuture.runAsync(() -> processRepo(savedRepo, getValidAccessToken(org)), executorService)
//                .exceptionally(throwable -> {
//                    LOGGER.error("Error processing repo: " + repoName, throwable);
//                    updateRepoState(savedRepo, RepoState.ERROR);
//                    return null;
//                });
//
//        LOGGER.info("Added/Updated repository: " + repoName);
//    }
//
//    private void updateRepoBasicInfo(Repo repository, JsonNode repoNode, GithubOrganisation org) {
//        repository.setName(repoNode.get("full_name").asText());
//        repository.setPrivate(repoNode.get("private").asBoolean());
//        repository.setOrganisation(org);
//        repository.setLanguage(repoNode.path("language").asText());
//        repository.setCreatedAt(LocalDateTime.now());
//    }
//
//    public void removeRepository(String repoName, String orgName) {
//        GithubOrganisation org = organisationRepository.findGithubOrganisationByNameAndVcsProvider(orgName, "github")
//                .orElseThrow(() -> new RuntimeException("Organisation not found: " + orgName));
//
//        repoRepository.findByNameAndOrganisation(repoName, org)
//                .ifPresent(repoRepository::delete);
//    }


    public String getValidAccessToken(GithubOrganisation org) {
        LocalDateTime tokenExpiry = org.getGithubAccessTokenExpiry();
        if (tokenExpiry == null || tokenExpiry.isBefore(LocalDateTime.now().plusMinutes(5))) {
            return refreshAccessToken(org);
        }
        return org.getGithubAccessToken();
    }

    private String refreshAccessToken(GithubOrganisation org) {
        HttpHeaders headers = new HttpHeaders();
        refreshInstallationTokenIfNeeded(org);
        headers.setBearerAuth(org.getGithubInstallationToken());
        headers.setAccept(List.of(MediaType.parseMediaType("application/vnd.github.v3+json")));
        HttpEntity<String> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<Map> response = new RestTemplate().exchange(
                    "https://api.github.com/app/installations/" + org.getGithubInstallationId() + "/access_tokens",
                    HttpMethod.POST,
                    entity,
                    Map.class
            );

            if (response.getBody() != null && response.getBody().containsKey("token")) {
                String newAccessToken = (String) response.getBody().get("token");
                String expiresAtStr = (String) response.getBody().get("expires_at");

                DateTimeFormatter formatter = DateTimeFormatter.ISO_DATE_TIME;
                LocalDateTime githubAccessTokenExpiry = LocalDateTime.parse(expiresAtStr, formatter);

                org.setGithubAccessToken(newAccessToken);
                org.setGithubAccessTokenExpiry(githubAccessTokenExpiry);
                organisationRepository.save(org);

                return newAccessToken;
            } else {
                throw new RuntimeException("Failed to obtain GitHub access token");
            }
        } catch (Exception e) {
            throw new RuntimeException("Error refreshing GitHub access token", e);
        }
    }

    private void refreshInstallationTokenIfNeeded(GithubOrganisation org) {
        LocalDateTime installationTokenExpiry = org.getGithubInstallationTokenExpiry();
        if (installationTokenExpiry == null || installationTokenExpiry.isBefore(LocalDateTime.now().plusMinutes(5))) {
            try {
                JwtResponseDTO jwtResponse = jwtService.generateJwt(githubAppId, privateKeyPEM);
                org.setGithubInstallationToken(jwtResponse.getToken());
                org.setGithubInstallationTokenExpiry(jwtResponse.getExpiry());
                organisationRepository.save(org);
            } catch (Exception e) {
                throw new RuntimeException("Error refreshing GitHub installation token", e);
            }
        }
    }

    // WEBHOOK PART

    @Override
    public ScanningResponseDTO startScanning(String repoId, User user) {
        try {
            Repo repo = repoRepository.findById(repoId)
                    .orElseThrow(() -> new RuntimeException("Repository not found"));

            if (!(repo.getOrganisation() instanceof GithubOrganisation)) {
                throw new RuntimeException("Unsupported organisation type");
            }
            String accessToken = getValidAccessToken((GithubOrganisation) repo.getOrganisation());

            Map<String, BranchScanningResponseDTO> branchResponses = new HashMap<>();

            String primaryBranch = repo.getPrimaryBranchDetails().getName();
            BranchScanningResponseDTO primaryBranchResponse = scanBranch(repo, user, accessToken, primaryBranch);
            branchResponses.put(primaryBranch, primaryBranchResponse);

            if (repo.getSecondaryBranchDetails() != null) {
                String secondaryBranch = repo.getSecondaryBranchDetails().getName();
                BranchScanningResponseDTO secondaryBranchResponse = scanBranch(repo, user, accessToken, secondaryBranch);
                branchResponses.put(secondaryBranch, secondaryBranchResponse);
            }

            return processScanningResponses(repo, branchResponses);
        } catch (Exception e) {
            LOGGER.error("Error during scanning initiation for repo: {}", repoId, e);
            throw new RuntimeException("Failed to initiate scanning: " + e.getMessage());
        }
    }

    private BranchScanningResponseDTO scanBranch(Repo repo, User user, String accessToken, String branch) {
        try {
            HttpEntity<Map<String, Object>> request = createScanningRequest(repo, user, accessToken, branch);
            ResponseEntity<String> response = new RestTemplate().postForEntity(scanningAnalysisUrl, request, String.class);

            JsonNode responseBody = new ObjectMapper().readTree(response.getBody());
            return BranchScanningResponseDTO.builder()
                    .status(responseBody.path("status").asText())
                    .analysisId(responseBody.path("analysis_id").asText())
                    .message(responseBody.path("message").asText())
                    .branch(branch)
                    .build();

        } catch (Exception e) {
            LOGGER.error("Error scanning branch {} for repo {}: {}", branch, repo.getName(), e.getMessage());
            return BranchScanningResponseDTO.builder()
                    .status("error")
                    .message("Failed to scan branch " + branch + ": " + e.getMessage())
                    .branch(branch)
                    .build();
        }
    }

    private HttpEntity<Map<String, Object>> createScanningRequest(Repo repo, User user, String accessToken, String branch) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + user.getAccessToken());

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("owner_name", repo.getOrganisation().getName());
        requestBody.put("repo_name", repo.getName());
        requestBody.put("repository_provider", user.getVcsProvider());
        requestBody.put("work_email", user.getWorkEmail());

        Map<String, Object> credentials = new HashMap<>();
        credentials.put("type", "token");
        credentials.put("value", dataEncryptionService.encrypt(accessToken));
        requestBody.put("credentials", credentials);

        Map<String, Object> analysisParameters = new HashMap<>();
        analysisParameters.put("target_branch", branch);
        requestBody.put("analysis_parameters", analysisParameters);

        requestBody.put("callback_url", scanningCallbackUrl);

        return new HttpEntity<>(requestBody, headers);
    }

    private ScanningResponseDTO processScanningResponses(Repo repo, Map<String, BranchScanningResponseDTO> branchResponses) {
        Map<String, String> analysisIds = new HashMap<>();
        List<String> errorMessages = new ArrayList<>();

        for (BranchScanningResponseDTO response : branchResponses.values()) {
            if ("started".equals(response.getStatus())) {
                LOGGER.info("Scanning started for branch {} in repo {}",
                        response.getBranch(), repo.getName());
                analysisIds.put(response.getBranch(), response.getAnalysisId());
                updateBranchDetails(repo, response);
            } else {
                LOGGER.error("Failed to scan branch {} in repo {}: {}",
                        response.getBranch(), repo.getName(), response.getMessage());
                errorMessages.add(String.format("Branch %s: %s",
                        response.getBranch(), response.getMessage()));
                markBranchError(repo, response.getBranch());
            }
        }

        if (!errorMessages.isEmpty()) {
            String errorMessage = String.join("; ", errorMessages);
            LOGGER.error("Scanning failed for one or more branches in repo {}: {}",
                    repo.getName(), errorMessage);

            repo.setState(RepoState.ERROR);
            repoRepository.save(repo);

            return ScanningResponseDTO.builder()
                    .status("error")
                    .message(errorMessage)
                    .analysisIds(analysisIds.isEmpty() ? null : analysisIds)
                    .build();
        }

        repo.setState(RepoState.SCANNING);
        repoRepository.save(repo);
        sendScanStartEvent(repo);

        return ScanningResponseDTO.builder()
                .status("started")
                .message("Scanning started for all branches")
                .analysisIds(analysisIds)
                .build();
    }

    private void updateBranchDetails(Repo repo, BranchScanningResponseDTO response) {
        BranchDetails branchDetails;
        if (repo.getPrimaryBranchDetails().getName().equals(response.getBranch())) {
            branchDetails = repo.getPrimaryBranchDetails();
        } else {
            branchDetails = repo.getSecondaryBranchDetails();
        }

        if (branchDetails != null) {
            branchDetails.setAnalysisId(response.getAnalysisId());
            branchDetails.setState(BranchAnalysisState.SCANNING);
            branchDetails.setLastAnalyzed(LocalDateTime.now());
        }
    }

    private void markBranchError(Repo repo, String branch) {
        BranchDetails branchDetails;
        if (repo.getPrimaryBranchDetails().getName().equals(branch)) {
            branchDetails = repo.getPrimaryBranchDetails();
        } else {
            branchDetails = repo.getSecondaryBranchDetails();
        }

        if (branchDetails != null) {
            branchDetails.setState(BranchAnalysisState.ERROR);
            branchDetails.setLastAnalyzed(LocalDateTime.now());
        }
    }

    private void sendScanStartEvent(Repo repo) {
        try {
            Map<String, Object> eventData = new HashMap<>();
            eventData.put("event", "scan_started");
            eventData.put("repo_id", repo.getId());
            eventData.put("repo_name", repo.getName());

            Map<String, Object> primaryBranch = new HashMap<>();
            primaryBranch.put("name", repo.getPrimaryBranchDetails().getName());
            primaryBranch.put("analysis_id", repo.getPrimaryBranchDetails().getAnalysisId());
            primaryBranch.put("state", repo.getPrimaryBranchDetails().getState());
            eventData.put("primary_branch", primaryBranch);

            if (repo.getSecondaryBranchDetails() != null) {
                Map<String, Object> secondaryBranch = new HashMap<>();
                secondaryBranch.put("name", repo.getSecondaryBranchDetails().getName());
                secondaryBranch.put("analysis_id", repo.getSecondaryBranchDetails().getAnalysisId());
                secondaryBranch.put("state", repo.getSecondaryBranchDetails().getState());
                eventData.put("secondary_branch", secondaryBranch);
            }

            eventData.put("status", "started");
            eventData.put("timestamp", LocalDateTime.now().toString());

            String eventDataJson = new ObjectMapper().writeValueAsString(eventData);
            sseController.sendEvent("repo_update", eventDataJson);

            LOGGER.debug("Sent scan start event for repo {}", repo.getName());
        } catch (JsonProcessingException e) {
            LOGGER.error("Error creating SSE event data for scan start. Repo: {}, Error: {}",
                    repo.getName(), e.getMessage());
        }
    }

    public boolean verifySignature(String payload, String signature) {
        try {
            Mac sha256Hmac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKey = new SecretKeySpec(webhookSecret.getBytes(), "HmacSHA256");
            sha256Hmac.init(secretKey);
            String hash = toHexString(sha256Hmac.doFinal(payload.getBytes()));
            return ("sha256=" + hash).equals(signature);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new RuntimeException("Failed to verify signature", e);
        }
    }

    private static String toHexString(byte[] bytes) {
        Formatter formatter = new Formatter();
        for (byte b : bytes) {
            formatter.format("%02x", b);
        }
        return formatter.toString();
    }

    public void processPullRequestEvent(String payload) {
        try {
            JsonNode rootNode = objectMapper.readTree(payload);
            String action = rootNode.path("action").asText();
            JsonNode prNode = rootNode.path("pull_request");
            String targetBranch = prNode.path("base").path("ref").asText();
            String orgName = rootNode.path("organization").path("login").asText();
            String repoName = rootNode.path("repository").path("name").asText();
            String prNumber = prNode.path("number").asText();

            if ("opened".equals(action)) {
                GithubOrganisation githubOrganisation = organisationRepository
                        .findGithubOrganisationByNameAndVcsProvider(orgName, "github")
                        .orElseThrow(() -> new RuntimeException("Organisation not found with name: " + orgName));

                Repo repo = repoRepository
                        .findByNameAndOrganisation(repoName, githubOrganisation)
                        .orElseThrow(() -> new RuntimeException("Repository not found with name: " + repoName));

                if (!isTargetBranchValid(repo, targetBranch)) {
                    LOGGER.info("Skipping PR analysis as target branch {} is not configured for analysis in repo {}",
                            targetBranch, repoName);
                    return;
                }

                PullRequest pullRequest = savePullRequest(prNode, repo);

                try {
                    requestPrAnalysis(pullRequest, targetBranch);
                    sendNewPrEvent(pullRequest);
                    LOGGER.info("Successfully initiated analysis for PR {} in repo {}",
                            prNumber, repoName);
                } catch (Exception e) {
                    LOGGER.error("Error initiating analysis for PR {} in repo {}: {}",
                            prNumber, repoName, e.getMessage(), e);
                    handlePrAnalysisError(pullRequest, e);
                }
            }
        } catch (Exception e) {
            LOGGER.error("Error processing PR webhook: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to process PR webhook", e);
        }
    }

    private boolean isTargetBranchValid(Repo repo, String targetBranch) {
        return targetBranch.equals(repo.getPrimaryBranchDetails().getName()) ||
                targetBranch.equals(repo.getSecondaryBranchDetails().getName());
    }

    private void handlePrAnalysisError(PullRequest pullRequest, Exception e) {
        pullRequest.setPrState(PullRequestState.ERROR);
        pullRequestRepository.save(pullRequest);

        try {
            Map<String, Object> eventData = new HashMap<>();
            eventData.put("event", "pr_error");
            eventData.put("pr_number", pullRequest.getPullRequestNumber());
            eventData.put("repo_name", pullRequest.getRepo().getName());
            eventData.put("error", e.getMessage());

            String eventDataJson = objectMapper.writeValueAsString(eventData);
            sseController.sendEvent("pr_update", eventDataJson);
        } catch (JsonProcessingException jsonException) {
            LOGGER.error("Error sending error event for PR {}: {}",
                    pullRequest.getPullRequestNumber(), jsonException.getMessage());
        }
    }

    private void sendNewPrEvent(PullRequest pullRequest) {
        try {
            Map<String, Object> eventData = new HashMap<>();
            eventData.put("event", "new_pr");
            eventData.put("pr_number", pullRequest.getPullRequestNumber());
            eventData.put("repo_name", pullRequest.getRepo().getName());
            eventData.put("title", pullRequest.getPullRequestTitle());
            eventData.put("author", pullRequest.getAuthorName());

            String eventDataJson = objectMapper.writeValueAsString(eventData);
            sseController.sendEvent("pr_update", eventDataJson);
        } catch (JsonProcessingException e) {
            LOGGER.error("Error creating SSE event data for new PR", e);
        }
    }

    private PullRequest savePullRequest(JsonNode prNode, Repo repo) {
        PullRequest pullRequest = PullRequest.builder()
                .repo(repo)
                .pullRequestTitle(prNode.path("title").asText())
                .sourceBranch(prNode.path("head").path("ref").asText())
                .pullRequestNumber(prNode.path("number").asText())
                .targetBranch(prNode.path("base").path("ref").asText())
                .authorName(prNode.path("user").path("login").asText())
                .createdAt(LocalDateTime.ofEpochSecond(prNode.path("created_at").asLong(), 0, ZoneOffset.UTC))
                .build();

        return pullRequestRepository.save(pullRequest);
    }

    public void requestPrAnalysis(PullRequest pullRequest, String targetBranch) {
        Optional<Configuration> config = configurationRepository.findByRepo(pullRequest.getRepo());
        if (config.isEmpty()) {
            throw new RuntimeException("Configuration not found for repo: " + pullRequest.getRepo().getName());
        }
        Configuration configuration = config.get();
        GithubPrDTO githubPrDTO = getTokens(pullRequest);
        String githubToken = githubPrDTO.getGithubAccessToken();
        String aiAnalysisToken = githubPrDTO.getOwnerAccessToken();
        String workEmail = githubPrDTO.getOwnerWorkEmail();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + aiAnalysisToken);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("owner_name", pullRequest.getRepo().getOrganisation().getName());
        requestBody.put("repo_name", pullRequest.getRepo().getName());
        requestBody.put("pull_request_number", pullRequest.getPullRequestNumber());
        requestBody.put("repository_provider", "github");
        requestBody.put("work_email", workEmail);

        Map<String, Object> credentials = new HashMap<>();
        credentials.put("type", "token");
        credentials.put("value", githubToken);
        requestBody.put("credentials", credentials);

        Map<String, Object> analysisParameters = new HashMap<>();
        analysisParameters.put("target_branch", targetBranch);
        analysisParameters.put("device", configuration.getDevice());
        analysisParameters.put("mediaAccess", configuration.isMediaAccess());
        analysisParameters.put("locationAccess", configuration.isLocationAccess());
        analysisParameters.put("cameraAccess", configuration.isCameraAccess());
        analysisParameters.put("microphoneAccess", configuration.isMicrophoneAccess());
        analysisParameters.put("filesAccess", configuration.isFilesAccess());
        analysisParameters.put("keys", configuration.getKeys());
        requestBody.put("analysis_parameters", analysisParameters);

        requestBody.put("callback_url", prCallbackUrl);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);

        new RestTemplate().postForEntity(prAnalysisUrl, request, String.class);
    }

    private GithubPrDTO getTokens(PullRequest pullRequest) {
        Organisation org = pullRequest.getRepo().getOrganisation();
        if (!(org instanceof GithubOrganisation githubOrg)) {
            throw new RuntimeException("Unsupported organisation type: " + org.getClass().getSimpleName());
        }

        String githubToken = getValidAccessToken(githubOrg);
        if (githubToken == null || githubToken.isEmpty()) {
            throw new RuntimeException("GitHub access token not found for organisation: " + org.getName());
        }

        User owner = githubOrg.getOwner();
        String accessToken = owner.getAccessToken();
        String workEmail = owner.getWorkEmail();

        return new GithubPrDTO(githubToken, accessToken, workEmail);
    }
}
