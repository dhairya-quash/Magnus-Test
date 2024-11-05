package com.quashbugs.magnus.service;

import com.quashbugs.magnus.adapter.VcsAdapter;
import com.quashbugs.magnus.model.Organisation;
import com.quashbugs.magnus.model.User;
import com.quashbugs.magnus.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

@Service
public class UserService {

    private final JwtService jwtService;
    private final VcsProviderFactory vcsProviderFactory;
    private final UserRepository userRepository;

    private static final Logger LOGGER = LoggerFactory.getLogger(UserService.class);

    @Autowired
    public UserService(JwtService jwtService,
                       VcsProviderFactory vcsProviderFactory,
                       UserRepository userRepository) {
        this.jwtService = jwtService;
        this.vcsProviderFactory = vcsProviderFactory;
        this.userRepository = userRepository;
    }


    public User createOrUpdateUser(Map<String, Object> userData) {
        String workEmail = userData.get("workEmail").toString();
        String vcsProvider = userData.get("vcsProvider").toString();
        Optional<User> existingUser = userRepository.findByWorkEmailAndVcsProvider(workEmail, vcsProvider);
        VcsAdapter vcsAdapter = vcsProviderFactory.getVcsProvider(vcsProvider);

        return existingUser.map(user -> updateExistingUser(user,vcsAdapter, userData)).orElseGet(() -> createNewUser(userData, vcsProvider,vcsAdapter));
    }
    private User updateExistingUser(User user,VcsAdapter vcsAdapter ,Map<String, Object> userData) {
        updateUserTokens(user);
        User savedUser = userRepository.save(user);
        vcsAdapter.updateExistingMember(savedUser, userData);
        return savedUser;
    }

    private User createNewUser(Map<String, Object> userData, String vcsProvider, VcsAdapter vcsAdapter) {
        User newUser = User.builder()
                .username(userData.get("username").toString())
                .workEmail(userData.get("workEmail").toString())
                .vcsProvider(vcsProvider)
                .createdAt(LocalDateTime.now())
                .build();
        updateUserTokens(newUser);
        User savedUser = userRepository.save(newUser);
        handleNewUser(savedUser,vcsAdapter,userData);
        return  newUser;
    }

    private void updateUserTokens(User user) {
        String jwtAccessToken = jwtService.generateAccessToken(user.getWorkEmail(), user.getVcsProvider());
        String jwtRefreshToken = jwtService.generateRefreshToken(user.getWorkEmail(), user.getVcsProvider());

        user.setAccessToken(jwtAccessToken);
        user.setRefreshToken(jwtRefreshToken);
        user.setAccessTokenExpiry(LocalDateTime.now().plusDays(6));
        user.setRefreshTokenExpiry(LocalDateTime.now().plusDays(8));
    }

    private void handleNewUser(User savedUser, VcsAdapter vcsAdapter, Map<String, Object> userData) {
        try {
            if (savedUser.getVcsProvider().equalsIgnoreCase("bitbucket")) {
                vcsAdapter.createNewMember(savedUser, null, userData);
            } else {
                Organisation personalOrg = vcsAdapter.createPersonalOrganisation(savedUser);
                vcsAdapter.createNewMember(savedUser, personalOrg.getId(), userData);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to complete setup for new user: {}", savedUser.getWorkEmail(), e);
            throw new RuntimeException("Failed to complete user setup", e);
        }
    }

    public UserDetailsService userDetailsService() {
        return new UserDetailsService() {
            @Override
            public User loadUserByUsername(String username) throws UsernameNotFoundException {
                Optional<User> user = userRepository.findByUsername(username); // Implement this method in your UserRepository
                if (user.isEmpty()) {
                    throw new UsernameNotFoundException("User not found for username");
                }
                return user.get();
            }
        };
    }

}