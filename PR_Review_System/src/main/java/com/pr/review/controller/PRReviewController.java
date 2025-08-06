package com.pr.review.controller;

import com.pr.review.service.PRReviewService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/prReview")
public class PRReviewController {

    @Autowired
    private PRReviewService prReviewService;

    @GetMapping
    public String testUrl() {
        return prReviewService.sendToLLM("Hello");
    }

    @PostMapping("/webhook")
    public ResponseEntity<?> getWebhook(@RequestBody Map<String, Object> payload, @RequestHeader("X-GitHub-Event") String eventType) {
        if ("pull_request".equals(eventType)) {
            String action = (String) payload.get("action");

            // ✅ Only act on new PRs
            if ("opened".equals(action)) {
                // Proceed with fetching PR diff and calling LLM
                // e.g., call: reviewService.processWebhook(payload);
                System.out.println("New PR created. Triggering AI review...");
                return prReviewService.processWebhook(payload);
            } else {
                System.out.println("Ignoring PR event: " + action);
            }
        }
        return ResponseEntity.ok().build();
    }

}
