package com.quashbugs.magnus.controller;

import com.quashbugs.magnus.adapter.VcsAdapter;
import com.quashbugs.magnus.dto.ResponseDTO;
import com.quashbugs.magnus.model.Organisation;
import com.quashbugs.magnus.model.User;
import com.quashbugs.magnus.service.VcsProviderFactory;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/user")
@SecurityRequirement(name = "jwtAuth")
public class UserController {

    private final VcsProviderFactory vcsProviderFactory;

    @Autowired
    public UserController(VcsProviderFactory vcsProviderFactory) {
        this.vcsProviderFactory = vcsProviderFactory;
    }

    @GetMapping("/get-organizations")
    public ResponseEntity<ResponseDTO> getUserOrganizations(Authentication authentication) {
        try {
            User user = (User) authentication.getPrincipal();
            VcsAdapter vcsAdapter = vcsProviderFactory.getVcsProvider(user.getVcsProvider());
            List<Organisation> organizations = vcsAdapter.fetchAndUpdateUserOrganizations(user);
            return ResponseEntity.ok(new ResponseDTO(true, "Organizations fetched successfully", organizations));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new ResponseDTO(false, "Invalid access token", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(new ResponseDTO(false, "Error fetching organizations", e.getMessage()));
        }
    }
}
