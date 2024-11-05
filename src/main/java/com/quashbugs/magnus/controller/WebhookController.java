package com.quashbugs.magnus.controller;

import com.quashbugs.magnus.dto.ResponseDTO;
import com.quashbugs.magnus.service.GithubService;
import com.quashbugs.magnus.service.WebhookService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/webhooks")
public class WebhookController {

    private final GithubService githubService;
    private final WebhookService webhookService;

    @Autowired
    public WebhookController(GithubService githubService,
                             WebhookService webhookService) {
        this.githubService = githubService;
        this.webhookService = webhookService;
    }

    @PostMapping("/github")
    public ResponseEntity<String> handleGitHubWebhook(
            @RequestBody String payload,
            @RequestHeader("X-GitHub-Event") String eventType,
            @RequestHeader("X-Hub-Signature-256") String signature) {

        if (!githubService.verifySignature(payload, signature)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid signature");
        }

        if ("pull_request".equals(eventType)) {
            githubService.processPullRequestEvent(payload);
        }

        return ResponseEntity.ok("Webhook processed successfully");
    }

    @PostMapping("/callback/scanning")
    @SecurityRequirement(name = "jwtAuth")
    public ResponseEntity<ResponseDTO> handleScanningCallback(@RequestBody String payload) {
        try {
            webhookService.handleScanningCallback(payload);
            return ResponseEntity.ok(new ResponseDTO(true, "Callback processed successfully", null));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest()
                    .body(new ResponseDTO(false, "Invalid callback data", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(new ResponseDTO(false, "Error processing callback", e.getMessage()));
        }
    }

    @PostMapping("/callback/pr")
    @SecurityRequirement(name = "jwtAuth")
    public ResponseEntity<ResponseDTO> handlePrCallback(@RequestBody String payload) {
        try {
            webhookService.handlePrCallback(payload);
            return ResponseEntity.ok(new ResponseDTO(
                    true,
                    "PR callback processed successfully",
                    null));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest()
                    .body(new ResponseDTO(
                            false,
                            "Invalid callback data",
                            e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(new ResponseDTO(
                            false,
                            "Error processing callback",
                            e.getMessage()));
        }
    }
}
