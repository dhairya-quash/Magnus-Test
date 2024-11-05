package com.quashbugs.magnus.controller;

import com.quashbugs.magnus.dto.ResponseDTO;
import com.quashbugs.magnus.model.User;
import com.quashbugs.magnus.service.GitlabService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;

@RestController
@RequestMapping("/api/gitlab")
@SecurityRequirement(name = "jwtAuth")
public class GitlabController {

    @Autowired
    private GitlabService gitlabService;

    @GetMapping("/get-repo")
    public ResponseEntity<ResponseDTO> getGroupRepos(Authentication authentication, @RequestParam String orgType,
                                                     @RequestParam(value = "groupId", required = false) String groupId) {
        try {
            User user = (User) authentication.getPrincipal();
            if (!orgType.equalsIgnoreCase("personal") && !orgType.equalsIgnoreCase("work")) {
                return ResponseEntity.badRequest().body(new ResponseDTO(false, "Invalid orgType. Must be 'personal' or 'work'.", null));
            }

            // Check if orgType is work, orgId must be present
            if (orgType.equalsIgnoreCase("work") && groupId == null) {
                return ResponseEntity.badRequest().body(new ResponseDTO(false, "orgId is required for work orgType.", null));
            }
            List<HashMap<String, Object>> repos = gitlabService.getGroupProjects(user, orgType, groupId);
            return ResponseEntity.ok(new ResponseDTO(true, "Fetched repo successfully", repos));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new ResponseDTO(false, "Invalid access token", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(new ResponseDTO(false, "Error fetching organizations", e.getMessage()));
        }
    }

    @PostMapping("/save-repo")
    public ResponseEntity<ResponseDTO> saveRepos(Authentication authentication, @RequestBody HashMap<String, Object> projectData) {
        try {
            User user = (User) authentication.getPrincipal();
            gitlabService.saveProjects(user,projectData);
            return ResponseEntity.ok(new ResponseDTO(true, "Saved repos successfully", null));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new ResponseDTO(false, "Invalid access token", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(new ResponseDTO(false, "Error fetching organizations", e.getMessage()));
        }
    }

//    @GetMapping("/fetch-branches")
//    public ResponseEntity<ResponseDTO> saveRepos(Authentication authentication, @RequestParam String projectId) {
//        try {
//            User user = (User) authentication.getPrincipal();
//            List<String> branches = gitlabService.fetchRepoBranches(user, projectId);
//            return ResponseEntity.ok(new ResponseDTO(true, "Fetched repository branches", branches));
//        } catch (IllegalArgumentException e) {
//            return ResponseEntity.badRequest().body(new ResponseDTO(false, "Invalid access token", e.getMessage()));
//        } catch (Exception e) {
//            return ResponseEntity.internalServerError().body(new ResponseDTO(false, "Error fetching organizations", e.getMessage()));
//        }
//    }

//    @GetMapping("/repo-data")
//    public ResponseEntity<ResponseDTO> getRepoData(Authentication authentication) throws ExecutionException, InterruptedException {
//        List<RepoFile> files = gitlabService.getAllRepositoryFiles("62512722","bc327fabe275c778f25e1075bbdb9e601e5f4fb0cec42bb49f367818fdbfdd59");
//        return  ResponseEntity.ok(new ResponseDTO(true,"Success",files));
//    }

}
