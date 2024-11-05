package com.quashbugs.magnus.controller;

import com.quashbugs.magnus.dto.ResponseDTO;
import com.quashbugs.magnus.model.User;
import com.quashbugs.magnus.service.AuthenticationService;
import com.quashbugs.magnus.service.JwtService;
import com.quashbugs.magnus.service.UserService;
import io.jsonwebtoken.Claims;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/auth")
public class AuthenticationController {

    private final AuthenticationService authenticationService;
    private final UserService userService;
    private final JwtService jwtService;

    @Autowired
    public AuthenticationController(AuthenticationService authenticationService,
                                    UserService userService,
                                    JwtService jwtService) {
        this.authenticationService = authenticationService;
        this.userService = userService;
        this.jwtService = jwtService;
    }

    @PostMapping("/get-user")
    public ResponseEntity<ResponseDTO> login(@RequestBody Map<String, Object> userData) {
        try {
            User user = userService.createOrUpdateUser(userData);
            return ResponseEntity.ok(new ResponseDTO(true, "User created successfully", user));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new ResponseDTO(false, "Invalid VCS provider", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(new ResponseDTO(false, "An unexpected error occurred: ", e.getMessage()));
        }
    }

    @GetMapping("/get-refresh-token")
    public ResponseEntity<ResponseDTO> getRefreshToken(@RequestParam String refreshToken) {
        try {
            if (jwtService.isTokenExpired(refreshToken)) {
                return ResponseEntity.badRequest().body(new ResponseDTO(false, "Refresh token has expired", null));
            }

            Claims claims = jwtService.extractAllClaims(refreshToken);
            if (claims == null) {
                return ResponseEntity.badRequest().body(new ResponseDTO(false, "Invalid refresh token", null));
            }

            User updatedUser = authenticationService.updateTokens(claims);
            if (updatedUser == null) {
                return ResponseEntity.badRequest().body(new ResponseDTO(false, "Failed to generate new tokens", null));
            }
            return ResponseEntity.ok(new ResponseDTO(true, "New tokens generated", updatedUser));

        } catch (IllegalArgumentException | NoSuchElementException e) {
            return ResponseEntity.badRequest().body(new ResponseDTO(false, e.getMessage(), null));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ResponseDTO(false, "Error: " + e.getMessage(), null));
        }
    }
}