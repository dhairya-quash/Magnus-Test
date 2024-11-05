package com.quashbugs.magnus.service;

import com.quashbugs.magnus.model.User;
import com.quashbugs.magnus.repository.UserRepository;
import io.jsonwebtoken.Claims;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.NoSuchElementException;
import java.util.Optional;

@Service
public class AuthenticationService {

    private final UserRepository userRepository;
    private final JwtService jwtService;

    @Autowired
    public AuthenticationService(UserRepository userRepository,
                                 JwtService jwtService) {
        this.userRepository = userRepository;
        this.jwtService = jwtService;
    }

    public User updateTokens(Claims claims) {
        String email = claims.get("sub", String.class);
        String vcsProvider = claims.get("vcsProvider", String.class);

        if (email == null || vcsProvider == null) {
            throw new IllegalArgumentException("Invalid claims data: email or vcsProvider is missing");
        }

        Optional<User> optionalUser = userRepository.findByWorkEmailAndVcsProvider(email, vcsProvider);
        if (optionalUser.isEmpty()) {
            throw new NoSuchElementException("User not found for email: " + email + " and vcsProvider: " + vcsProvider);
        }

        User currentUser = optionalUser.get();
        String newAccessToken = jwtService.generateAccessToken(email, vcsProvider);
        String newRefreshToken = jwtService.generateRefreshToken(email, vcsProvider);

        currentUser.setAccessToken(newAccessToken);
        currentUser.setRefreshToken(newRefreshToken);

        userRepository.save(currentUser);

        return currentUser;
    }

}
