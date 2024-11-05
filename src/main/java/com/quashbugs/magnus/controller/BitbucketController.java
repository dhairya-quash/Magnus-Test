package com.quashbugs.magnus.controller;

import com.quashbugs.magnus.dto.RepoFile;
import com.quashbugs.magnus.dto.ResponseDTO;
import com.quashbugs.magnus.model.User;
import com.quashbugs.magnus.service.BitbucketService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutionException;

@RestController
@RequestMapping("/api/bitbucket")
@SecurityRequirement(name = "jwtAuth")
public class BitbucketController {

    @Autowired
    private BitbucketService bitbucketService;

    @GetMapping("/get-repo")
    public ResponseEntity<ResponseDTO> getGroupRepos(Authentication authentication, @RequestParam String slug) {
        try {
            User user = (User) authentication.getPrincipal();
            List<HashMap<String, Object>> repos = bitbucketService.getWorkspaceRepos(user, slug);
            return ResponseEntity.ok(new ResponseDTO(true, "Fetched repo successfully", repos));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new ResponseDTO(false, "Invalid access token", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(new ResponseDTO(false, "Error fetching organizations", e.getMessage()));
        }
    }

    @PostMapping("/save-repo")
    public ResponseEntity<ResponseDTO> saveRepos(Authentication authentication, @RequestBody HashMap<String, Object> repoData) {
        try {
            User user = (User) authentication.getPrincipal();
            bitbucketService.saveRepos(user, repoData);
            return ResponseEntity.ok(new ResponseDTO(true, "Saved repos successfully", null));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new ResponseDTO(false, "Invalid access token", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(new ResponseDTO(false, "Error fetching organizations", e.getMessage()));
        }
    }

//    @GetMapping("/fetch-branches")
//    public ResponseEntity<ResponseDTO> saveRepos(Authentication authentication, @RequestParam String workspaceSlug, @RequestParam String repoSlug) {
//        try {
//            User user = (User) authentication.getPrincipal();
//            List<String> branches = bitbucketService.fetchRepoBranches(user, workspaceSlug, repoSlug);
//            return ResponseEntity.ok(new ResponseDTO(true, "Fetched repository branches", branches));
//        } catch (IllegalArgumentException e) {
//            return ResponseEntity.badRequest().body(new ResponseDTO(false, "Invalid access token", e.getMessage()));
//        } catch (Exception e) {
//            return ResponseEntity.internalServerError().body(new ResponseDTO(false, "Error fetching organizations", e.getMessage()));
//        }
//    }

    @GetMapping("/repo-data")
    public ResponseEntity<ResponseDTO> getRepoFiles() throws ExecutionException, InterruptedException {
        try {
            List<RepoFile> files = bitbucketService.getAllRepositoryFiles("quashh", "quash-sdk", "s29AFGqU2zaWjeEci4WzzT8A57R9ZvHHJ24Cb4-l3VapIgnBNUmiS3NLjDA7lmi_hM5wBRdpitZB49dqLpSoH9CE9j3GmgbBOEEwUlN4QqhjPDpJz4srlLWbJoENocm8JUZ7D9HXu3CZIEj5RTq4cVBzDPlA");
            System.out.println(files.size());
            return ResponseEntity.ok(new ResponseDTO(true, "Success", files));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(new ResponseDTO(false, "Failed", e));
        }
    }
}
