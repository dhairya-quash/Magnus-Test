package com.quashbugs.magnus.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

public class SwaggerAuthFilter extends BasicAuthenticationFilter {

    @Value("${spring.swagger.username}")
    private String SWAGGER_USERNAME; // Change as needed
    @Value("${spring.swagger.password}")
    private String SWAGGER_PASSWORD; // Change as needed

    public SwaggerAuthFilter(AuthenticationManager authenticationManager) {
        super(authenticationManager);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String authHeader = request.getHeader("Authorization");

        if (authHeader != null && authHeader.startsWith("Basic ")) {
            String base64Credentials = authHeader.substring("Basic ".length()).trim();
            String credentials = new String(Base64.getDecoder().decode(base64Credentials));
            // credentials = username:password
            final String[] values = credentials.split(":", 2);
            String username = values[0];
            String password = values[1];

            if (SWAGGER_USERNAME.equals(username) && SWAGGER_PASSWORD.equals(password)) {
                List<GrantedAuthority> authorities = new ArrayList<>(); // or add roles if needed
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(username, null, authorities);
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        }
        chain.doFilter(request, response);
    }
}
