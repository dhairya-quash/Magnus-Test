package com.quashbugs.magnus.service;

import com.quashbugs.magnus.dto.JwtResponseDTO;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.FileReader;
import java.security.Key;
import java.security.PrivateKey;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

@Service
public class JwtService {

    @Value("${token.signing.key}")
    private String secret;

    @Value("${jwt.accessToken.expiration}")
    private long accessTokenExpiration;

    @Value("${jwt.refreshToken.expiration}")
    private long refreshTokenExpiration;

    private static final Logger LOGGER = LoggerFactory.getLogger(JwtService.class);

    private Key getSigningKey() {
        byte[] keyBytes = secret.getBytes();
        return Keys.hmacShaKeyFor(keyBytes);
    }

    public String generateAccessToken(String email, String vcsProvider) {
        return generateToken(email, vcsProvider, accessTokenExpiration);
    }

    public String generateRefreshToken(String email, String vcsProvider) {
        return generateToken(email, vcsProvider, refreshTokenExpiration);
    }

    private String generateToken(String email, String vcsProvider, long expiration) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("vcsProvider", vcsProvider);
        return Jwts.builder()
                .setClaims(claims)
                .setSubject(email)
                .setIssuedAt(new Date(System.currentTimeMillis()))
                .setExpiration(new Date(System.currentTimeMillis() + expiration))
                .signWith(getSigningKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    public String extractEmail(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public String extractVcsProvider(String token) {
        return extractClaim(token, claims -> claims.get("vcsProvider", String.class));
    }

    public Date extractExpiration(String token) {
        return extractClaim(token, Claims::getExpiration);
    }

    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }

    public Claims extractAllClaims(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(getSigningKey())
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    public Boolean isTokenExpired(String token) {
        return extractExpiration(token).before(new Date());
    }

    public Boolean validateToken(String token, String email) {
        final String extractedEmail = extractEmail(token);
        return (extractedEmail.equals(email) && !isTokenExpired(token));
    }

    public JwtResponseDTO generateJwt(String appId, String pemFilePath) throws Exception {
        PrivateKey privateKey;

        try (FileReader keyReader = new FileReader(pemFilePath);
             PEMParser pemParser = new PEMParser(keyReader)) {

            JcaPEMKeyConverter converter = new JcaPEMKeyConverter();
            Object keyPair = pemParser.readObject();

            if (keyPair instanceof PEMKeyPair) {
                privateKey = converter.getPrivateKey(((PEMKeyPair) keyPair).getPrivateKeyInfo());
            } else {
                privateKey = converter.getPrivateKey((PrivateKeyInfo) keyPair);
            }
        }

        long now = Instant.now().getEpochSecond();

        // Convert appId to integer
        int appIdInt;
        try {
            appIdInt = Integer.parseInt(appId);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("App ID must be a valid integer", e);
        }

        String jwt = Jwts.builder()
                .setIssuedAt(Date.from(Instant.ofEpochSecond(now)))
                .setExpiration(Date.from(Instant.ofEpochSecond(now + 600))) // 10 minutes expiration
                .setIssuer(Integer.toString(appIdInt)) // Set as string representation of integer
                .signWith(SignatureAlgorithm.RS256, privateKey)
                .compact();

        return new JwtResponseDTO(jwt, LocalDateTime.ofInstant(Instant.ofEpochSecond(now + 600), ZoneId.systemDefault()));    }
}