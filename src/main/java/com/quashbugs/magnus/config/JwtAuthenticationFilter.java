package com.quashbugs.magnus.config;

import com.quashbugs.magnus.model.User;
import com.quashbugs.magnus.repository.UserRepository;
import com.quashbugs.magnus.service.JwtService;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.security.SignatureException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserRepository userRepository;
    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain)
            throws ServletException, IOException {

        try {
            final String authHeader = request.getHeader("Authorization");
            final String jwt;
            final String email;
            final String vcsProvider;

            if (ObjectUtils.isEmpty(authHeader) || !StringUtils.startsWithIgnoreCase(authHeader, "Bearer ")) {
                filterChain.doFilter(request, response);
                return;
            }

            jwt = authHeader.substring(7);
            email = jwtService.extractEmail(jwt);
            vcsProvider = jwtService.extractVcsProvider(jwt);

            if (StringUtils.hasText(email) && SecurityContextHolder.getContext().getAuthentication() == null) {
                Optional<User> userOptional = userRepository.findByWorkEmailAndVcsProvider(email, vcsProvider);
                if (userOptional.isPresent()) {
                    User currentUser = userOptional.get();
                    if (jwtService.validateToken(jwt, email)) {
                        SecurityContext context = SecurityContextHolder.createEmptyContext();
                        UsernamePasswordAuthenticationToken authToken =
                                new UsernamePasswordAuthenticationToken(currentUser, null, currentUser.getAuthorities());
                        authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                        context.setAuthentication(authToken);
                        SecurityContextHolder.setContext(context);
                    }
                }
            }

            filterChain.doFilter(request, response);

        } catch (ExpiredJwtException e) {
            handleAuthenticationException(response, "JWT token has expired", HttpServletResponse.SC_UNAUTHORIZED);
        } catch (MalformedJwtException e) {
            handleAuthenticationException(response, "Invalid JWT token", HttpServletResponse.SC_UNAUTHORIZED);
        } catch (SignatureException e) {
            handleAuthenticationException(response, "Invalid JWT signature", HttpServletResponse.SC_UNAUTHORIZED);
        } catch (Exception e) {
            handleAuthenticationException(response, "An error occurred processing the JWT token", HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }

    private void handleAuthenticationException(HttpServletResponse response, String message, int statusCode) throws IOException {
        response.setStatus(statusCode);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\": \"" + message + "\"}");
        response.getWriter().flush();
    }
}
