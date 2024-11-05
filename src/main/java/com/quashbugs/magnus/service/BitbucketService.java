package com.quashbugs.magnus.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quashbugs.magnus.adapter.VcsAdapter;
import com.quashbugs.magnus.dto.MobileProjectInfoDTO;
import com.quashbugs.magnus.dto.RepoFile;
import com.quashbugs.magnus.dto.ScanningResponseDTO;
import com.quashbugs.magnus.model.*;
import com.quashbugs.magnus.repository.BitbucketRepoRepository;
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
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.*;

@Service
public class BitbucketService implements VcsAdapter {
    @Value("${spring.bitbucket.key}")
    private String bitbucket_key;

    @Value("${spring.bitbucket.secret}")
    private String bitbucket_secret;

    RestTemplate restTemplate = new RestTemplate();
    private final ExecutorService executorService = Executors.newFixedThreadPool(10);
    //    private final ExecutorService executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() * 2);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final OrganisationRepository organisationRepository;
    private final MemberRepository memberRepository;
    private final BitbucketRepoRepository bitbucketRepoRepository;
    private final MobileDetectorService mobileDetectorService;
    private static final Logger LOGGER = LoggerFactory.getLogger(BitbucketService.class);

    @Autowired
    public BitbucketService(MemberRepository memberRepository,
                            OrganisationRepository organisationRepository,
                            BitbucketRepoRepository bitbucketRepoRepository,
                            MobileDetectorService mobileDetectorService) {
        this.memberRepository = memberRepository;
        this.organisationRepository = organisationRepository;
        this.bitbucketRepoRepository = bitbucketRepoRepository;
        this.mobileDetectorService = mobileDetectorService;
    }

    public HashMap<String, Object> getRefreshedTokens(String refreshToken) {
        String newTokenUrl = "https://bitbucket.org/site/oauth2/access_token";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.setBasicAuth(bitbucket_key, bitbucket_secret);

        MultiValueMap<String, String> requestBody = new LinkedMultiValueMap<>();
        requestBody.add("grant_type", "refresh_token");
        requestBody.add("refresh_token", refreshToken);

        try {
            HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(requestBody, headers);

            ResponseEntity<HashMap> response = restTemplate.exchange(newTokenUrl, HttpMethod.POST, request, HashMap.class);

            return response.getBody();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String newTokens(Member member) {
        HashMap<String, Object> tokenData = getRefreshedTokens(member.getVcsRefreshToken());

        long expiresIn = ((Number) tokenData.get("expires_in")).longValue();
        LocalDateTime tokenExpiry = LocalDateTime.now().plusSeconds(expiresIn);

        member.setVcsAccessToken(tokenData.get("access_token").toString());
        member.setVcsRefreshToken(tokenData.get("refresh_token").toString());
        member.setVcsTokenExpiry(tokenExpiry);
        Member updatedMember = memberRepository.save(member);
        return updatedMember.getVcsAccessToken();
    }


    @Override
    public Organisation createPersonalOrganisation(User user) {
        return null;
    }

    @Override
    public void createNewMember(User user, String orgId, Map<String, Object> userData) {
        long expiresAt = ((Number) userData.get("expires_at")).longValue();
        LocalDateTime tokenExpiry = LocalDateTime.ofInstant(Instant.ofEpochSecond(expiresAt), ZoneId.systemDefault());
        Member newMember = Member.builder()
                .user(user)
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

        return bitbucketRepoRepository.findByOrganisation(organisation).stream()
                .map(repo -> (Repo) repo)
                .toList();
    }

    @Override
    public List<Organisation> fetchAndUpdateUserOrganizations(User user) {
        try {
            Optional<Member> memberOptional = memberRepository.findByUser(user);
            Member member = memberOptional.get();
            String accessToken;
            if (LocalDateTime.now().isAfter(member.getVcsTokenExpiry())) {
                accessToken = newTokens(member);
            } else {
                accessToken = member.getVcsAccessToken();
            }
            List<HashMap<String, Object>> usersWorkspaces = getUsersWorkspaces(accessToken);
            List<Organisation> savedWorkspaces = saveUsersWorkspaces(user, usersWorkspaces);
            updateMemberOrganizations(memberOptional.get(), savedWorkspaces);
            return savedWorkspaces;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public List<String> fetchRepoBranches(User user, String orgId, String repoId) {
        try {
            Member member = memberRepository.findByUser(user).orElseThrow(() -> new RuntimeException("Member not found"));
            String accessToken;
            if (LocalDateTime.now().isAfter(member.getVcsTokenExpiry())) {
                accessToken = newTokens(member);
            } else {
                accessToken = member.getVcsAccessToken();
            }
            BitbucketOrganisation organisation = (BitbucketOrganisation) organisationRepository.findById(orgId).orElseThrow(() -> new RuntimeException("Organisation not found"));
            BitbucketRepo repo = bitbucketRepoRepository.findById(repoId).orElseThrow(() -> new RuntimeException("Repository not found"));
            return fetchBranches(organisation.getSlug(), repo.getSlug(), accessToken);
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage());
        }
    }

    @Override
    public ScanningResponseDTO startScanning(String repoId, User user) {
        return null;
    }

    public List<String> fetchBranches(String workspaceSlug, String repoSlug, String accessToken) throws JsonProcessingException {
        try {
            String url = String.format("https://api.bitbucket.org/2.0/repositories/%s/%s/refs/branches", workspaceSlug, repoSlug);

            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(accessToken);

            HttpEntity<String> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

            ObjectMapper mapper = new ObjectMapper();
            JsonNode branchList = mapper.readTree(response.getBody());

            List<String> repoBranches = new ArrayList<>();

            JsonNode branches = branchList.path("values");

            for (JsonNode branch : branches) {
                repoBranches.add(branch.get("name").asText());
            }
            return repoBranches;
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage());
        }
    }

    private List<HashMap<String, Object>> getUsersWorkspaces(String accessToken) throws JsonProcessingException {
        String url = "https://api.bitbucket.org/2.0/workspaces";

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);

        HttpEntity<String> entity = new HttpEntity<>(headers);

        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

        List<HashMap<String, Object>> usersWorkspaces = new ArrayList<>();
//      Parse the response to get the repositories
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode workspaceList = mapper.readTree(response.getBody());

//      Process the list of repositories as needed
            JsonNode workspaces = workspaceList.path("values");
            for (JsonNode workspace : workspaces) {
                HashMap<String, Object> workspaceData = new HashMap<>();
                workspaceData.put("name", workspace.get("name").asText());
                workspaceData.put("slug", workspace.get("slug").asText());
                usersWorkspaces.add(workspaceData);
            }
            return usersWorkspaces;
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private List<Organisation> saveUsersWorkspaces(User user, List<HashMap<String, Object>> workspaces) {
        List<Organisation> savedUsersWorkspaces = new ArrayList<>();
        for (HashMap<String, Object> workspace : workspaces) {
            Optional<BitbucketOrganisation> existingWorkspaceOptional = organisationRepository.findByVcsProviderAndSlug("bitbucket", workspace.get("slug").toString());

            if (existingWorkspaceOptional.isPresent()) {
                savedUsersWorkspaces.add(existingWorkspaceOptional.get());
            } else {
                BitbucketOrganisation newWorkspace = BitbucketOrganisation.builder()
                        .name(workspace.get("name").toString())
                        .slug(workspace.get("slug").toString())
                        .owner(user)
                        .vcsProvider("bitbucket")
                        .type(OrganisationType.WORK)
                        .createdAt(LocalDateTime.now())
                        .build();
                BitbucketOrganisation savedWorkspace = organisationRepository.save(newWorkspace);
                savedUsersWorkspaces.add(savedWorkspace);
            }
        }
        return savedUsersWorkspaces;
    }

    private void updateMemberOrganizations(Member member, List<Organisation> allWorkspaces) {
        Set<String> updatedWorkspaceIds = new HashSet<>();

        for (Organisation workspace : allWorkspaces) {
            updatedWorkspaceIds.add(workspace.getId());
        }

        member.setOrganisationIds(new ArrayList<>(updatedWorkspaceIds));
        memberRepository.save(member);
    }

    public List<HashMap<String, Object>> getWorkspaceRepos(User user, String slug) throws JsonProcessingException {
        Optional<Member> memberOptional = memberRepository.findByUser(user);
        if (memberOptional.isPresent()) {
            Member member = memberOptional.get();
            String accessToken;
            if (LocalDateTime.now().isAfter(member.getVcsTokenExpiry())) {
                accessToken = newTokens(member);
            } else {
                accessToken = member.getVcsAccessToken();
            }
            return getReposInWorkspaces(accessToken, slug);
        }
        return null;
    }

    private List<HashMap<String, Object>> getReposInWorkspaces(String accessToken, String slug) throws JsonProcessingException {
        String url = "https://api.bitbucket.org/2.0/repositories/" + slug;

        // Set up headers for authorization
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);

        HttpEntity<String> entity = new HttpEntity<>(headers);

        // Make the API call
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

        // Parse the JSON response
        ObjectMapper mapper = new ObjectMapper();
        JsonNode reposList = mapper.readTree(response.getBody());

        // Create a map to store name and slug of repositories
        List<HashMap<String, Object>> workspaceRepos = new ArrayList<>();

        // Get the "values" array from the response
        JsonNode repos = reposList.path("values");

        // Loop through each repository and extract "name" and "slug"

        for (JsonNode repo : repos) {
            HashMap<String, Object> repoData = new HashMap<>();
            repoData.put("name", repo.get("name").asText());
            repoData.put("slug", repo.get("slug").asText());
            repoData.put("language", repo.get("language"));
            repoData.put("isPrivate", repo.get("is_private"));
            workspaceRepos.add(repoData);
        }
        return workspaceRepos;
    }

    public void saveRepos(User user, HashMap<String, Object> repoData) {
        try {
            Member member = memberRepository.findByUser(user).orElseThrow(() -> new RuntimeException("Member not found"));
            String accessToken;
            if (LocalDateTime.now().isAfter(member.getVcsTokenExpiry())) {
                accessToken = newTokens(member);
            } else {
                accessToken = member.getVcsAccessToken();
            }

            List<BitbucketRepo> newBitbucketRepos = new ArrayList<>();
            List<HashMap<String, Object>> repos = (List<HashMap<String, Object>>) repoData.get("repos");

            String orgId = repoData.get("orgId").toString();

            BitbucketOrganisation organisation = (BitbucketOrganisation) organisationRepository.findById(orgId).orElseThrow(() -> new RuntimeException("Organisation not found"));

            for (HashMap<String, Object> repo : repos) {

                BitbucketRepo repository = bitbucketRepoRepository.findBySlug(repo.get("slug").toString()).orElse(BitbucketRepo.builder().build());
                if (repository.getState() != RepoState.COMPATIBLE && repository.getState() != RepoState.INCOMPATIBLE) {

                    updateRepoBasicInfo(repository, repo, organisation);
                    repository.setState(RepoState.FETCHING);
                    BitbucketRepo savedRepo = bitbucketRepoRepository.save(repository);
                    newBitbucketRepos.add(savedRepo);

                    CompletableFuture.runAsync(() -> {
                        try {
                            processRepo(organisation.getSlug(), savedRepo, accessToken);
                        } catch (Exception e) {
                            LOGGER.error("Error processing repo: {}", repository.getName(), e);
                            updateRepoState(savedRepo, RepoState.ERROR);
                        }
                    }, executorService);
                }
            }
            bitbucketRepoRepository.saveAll(newBitbucketRepos);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void updateRepoBasicInfo(BitbucketRepo repository, Map<String, Object> repoData, BitbucketOrganisation org) {
        repository.setName(repoData.get("name").toString());
        repository.setSlug(repoData.get("slug").toString());
        repository.setPrivate((Boolean) repoData.get("isPrivate"));
        repository.setLanguage(repoData.get("language").toString());
        repository.setOrganisation(org);
        repository.setCreatedAt(LocalDateTime.now());
    }

    private void processRepo(String workspace, BitbucketRepo repo, String accessToken) {
        try {
            if (repo.getState() == RepoState.COMPATIBLE ||
                    repo.getState() == RepoState.INCOMPATIBLE) {
                return;
            }

            updateRepoState(repo, RepoState.ANALYZING);

            List<RepoFile> files = getAllRepositoryFiles(workspace, repo.getSlug(), accessToken);

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

    private void updateRepoState(BitbucketRepo repository, RepoState state) {
        repository.setState(state);
        bitbucketRepoRepository.save(repository);
    }


    public List<RepoFile> getAllRepositoryFiles(String workspaceId, String repoSlug, String token) throws IOException, InterruptedException, ExecutionException {
        String defaultBranch = getDefaultBranch(workspaceId, repoSlug, token);
        if (defaultBranch == null) {
            throw new RuntimeException("Unable to determine the default branch for the repository.");
        }
        String initialUrl = String.format("https://api.bitbucket.org/2.0/repositories/%s/%s/src/%s/?pagelen=100", workspaceId, repoSlug, defaultBranch);
        return traverseFiles(initialUrl, token);
    }

    private List<RepoFile> traverseFiles(String initialUrl, String token) throws InterruptedException, ExecutionException {
        Queue<String> urlsToProcess = new ConcurrentLinkedQueue<>();
        urlsToProcess.offer(initialUrl);

        List<RepoFile> allFiles = new CopyOnWriteArrayList<>();

        while (!urlsToProcess.isEmpty()) {
            List<CompletableFuture<Void>> futures = new ArrayList<>();

            for (int i = 0; i < 10 && !urlsToProcess.isEmpty(); i++) {
                String url = urlsToProcess.poll();
                futures.add(CompletableFuture.runAsync(() -> processUrl(url, token, allFiles, urlsToProcess), executorService));
            }

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        }

        return allFiles;
    }

    private void processUrl(String url, String token, List<RepoFile> allFiles, Queue<String> urlsToProcess) {
        try {
            JsonNode response = makeApiCall(url, token);
            JsonNode values = response.get("values");

            for (JsonNode node : values) {
                String type = node.get("type").asText();
                if ("commit_file".equals(type)) {
                    String path = node.get("path").asText();
                    String name = Paths.get(path).getFileName().toString();
                    allFiles.add(new RepoFile(name, path, type));
                } else if ("commit_directory".equals(type)) {
                    String nextUrl = node.get("links").get("self").get("href").asText() + "?pagelen=100";
                    urlsToProcess.offer(nextUrl);
                }
            }

            JsonNode next = response.path("next");
            if (!next.isMissingNode() && !next.isNull()) {
                urlsToProcess.offer(next.asText());
            }
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage());
        }
    }

    private JsonNode makeApiCall(String url, String token) throws IOException {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + token);
        headers.set("Accept", "application/json");

        String response = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class).getBody();
        return objectMapper.readTree(response);
    }

    private String getDefaultBranch(String workspaceId, String repoSlug, String token) throws IOException {
        String url = String.format("https://api.bitbucket.org/2.0/repositories/%s/%s", workspaceId, repoSlug);
        JsonNode response = makeApiCall(url, token);
        return response.path("mainbranch").path("name").asText();
    }


//    public Boolean setBranchesInRepo(String primaryBranch, String secondaryBranch, String repoId) {
//        Optional<BitbucketRepo> repoOptional = bitbucketRepoRepository.findById(repoId);
//        if (repoOptional.isEmpty()) {
//            throw new RuntimeException("Repo not found");
//        }
//        try {
//            BitbucketRepo repo = repoOptional.get();
//            repo.setPrimaryBranch(primaryBranch);
//            repo.setSecondaryBranch(secondaryBranch);
//            bitbucketRepoRepository.save(repo);
//            return true;
//        } catch (Exception e) {
//            throw new RuntimeException(e.getMessage());
//        }
//    }
}