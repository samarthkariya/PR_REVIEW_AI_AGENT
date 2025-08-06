package com.pr.review.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class PRReviewService {

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();
    @Value("${github.token}")
    private String githubToken;
    @Value("${llm.api.url}")
    private String llmApiUrl;
    @Value("${llm.api.key}")
    private String llmApiKey;

    public ResponseEntity<?> processWebhook(Map<String, Object> payload) {
        System.out.println("Received webhook: " + payload);

        String title = (String) payload.get("title");
        String body = (String) payload.get("body");

        List<String> diffs = extractPRDiffsFromWebhook(payload);

//        String prompt = buildPrompt(title, body, diffs);

//        String review = sendToLLM(prompt);
        String review = getReviewFromRAG(diffs);
        Map<String, Object> repository = (Map<String, Object>) payload.get("repository");
        String repoFullName = (String) repository.get("full_name"); // e.g. "owner/spring_boot"
        commentOnPullRequest(repoFullName, (Integer) payload.get("number"), review);
        System.out.println("AI Review:\n" + review);
        return new ResponseEntity<>(review, HttpStatus.OK);
    }

    public List<String> extractPRDiffsFromWebhook(Map<String, Object> payload) {
        try {
            Map<String, Object> pullRequest = (Map<String, Object>) payload.get("pull_request");
            Map<String, Object> repository = (Map<String, Object>) payload.get("repository");

            if (pullRequest == null || repository == null) {
                throw new IllegalArgumentException("Invalid GitHub webhook payload: missing PR or repository");
            }

            int prNumber = (Integer) pullRequest.get("number");
            String repoFullName = (String) repository.get("full_name"); // e.g. "owner/spring_boot"
            String filesUrl = "https://api.github.com/repos/" + repoFullName + "/pulls/" + prNumber + "/files";

            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(githubToken); // Your GitHub token
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));

            HttpEntity<Void> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(filesUrl, HttpMethod.GET, entity, String.class);

            List<Map<String, Object>> files = objectMapper.readValue(response.getBody(), List.class);
            List<String> diffs = new ArrayList<>();

            for (Map<String, Object> file : files) {
                String filename = (String) file.get("filename");
                String status = (String) file.get("status");
                String patch = (String) file.get("patch");

                StringBuilder sb = new StringBuilder();
                sb.append("File: ").append(filename).append(" (").append(status).append(")\n");

                if (patch != null) {
                    sb.append("Patch:\n").append(patch);
                } else {
                    sb.append("⚠️ No patch available (possibly binary or deleted file).");
                }

                diffs.add(sb.toString());
            }

            return diffs;

        } catch (Exception e) {
            e.printStackTrace();
            return List.of("❌ Error extracting PR diffs: " + e.getMessage());
        }
    }


    // This is the method to call RAG agent locally
    public String getReviewFromRAG(List<String> diffs) {
        try {
            // Combine all diffs into a single string
            StringBuilder diffBuilder = new StringBuilder();
            for (String diff : diffs) {
                diffBuilder.append(diff).append("\n");
            }
            String diffPayload = diffBuilder.toString();

            // Prepare payload as a Map
            Map<String, String> payload = new HashMap<>();
            payload.put("diff", diffPayload);

            // Convert to JSON
            String jsonPayload = objectMapper.writeValueAsString(payload);

            // Send HTTP POST request
            URL url = new URL("http://localhost:5000/review");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();

            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);

            // Send JSON body
            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = jsonPayload.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            // Read response
            StringBuilder response = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    response.append(line.trim());
                }
            }

            // Parse response JSON (assumes {"review": "..."})
            Map<String, Object> result = objectMapper.readValue(response.toString(), Map.class);
            return (String) result.getOrDefault("review", "No review found in response.");

        } catch (Exception e) {
            e.printStackTrace();
            return "Error communicating with RAG service: " + e.getMessage();
        }
    }


    // This below is the method to call GROQ agent with proper prompt and difference of PR
    private String buildPrompt(String title, String body, List<String> diffs) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are an expert code reviewer.\n")
                .append("Title: ").append(title).append("\n")
                .append("Description: ").append(body).append("\n\n")
                .append("Changes:\n");

        for (String diff : diffs) {
            sb.append(diff).append("\n");
        }

        sb.append("\nPlease provide review based on the below points in summaries form don't give all the points individually:\n")
                .append("- A summary of what this PR does and If it contains any silly things then mention them in one line\n")
                .append("- Check for Code quality issues\n")
                .append("- Suggestions for improvement If any in short\n")
                .append("- if its only text change then don't summaries it and return ALL OK as a response\n")
                .append("- Make sure if the code is more then 20 lines for specific files mention that human review is required\n")
                .append("- If total data is more then 150 lines then only mention that human review is required\n");

        return sb.toString();
    }

    public String sendToLLM(String prompt) {
        Map<String, Object> requestBody = Map.of(
//                "model", "llama-3.3-70b-versatile",
                "model", "meta-llama/llama-4-scout-17b-16e-instruct",
                "messages", List.of(
                        Map.of("role", "user", "content", prompt)
                )
        );

        var headers = new org.springframework.http.HttpHeaders();
        headers.set("Content-Type", "application/json");
        headers.set("Authorization", "Bearer " + llmApiKey);

        var entity = new org.springframework.http.HttpEntity<>(requestBody, headers);

        var response = restTemplate.postForEntity(llmApiUrl, entity, Map.class);

        Map<String, Object> body = response.getBody();
        if (body == null || !body.containsKey("choices")) return "No response from LLM";

        List<Map<String, Object>> choices = (List<Map<String, Object>>) body.get("choices");
        Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");

        return (String) message.get("content");
    }


    // This is the method to comment on PR
    public void commentOnPullRequest(String repoFullName, int prNumber, String commentText) {
        String url = "https://api.github.com/repos/" + repoFullName + "/issues/" + prNumber + "/comments";

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(githubToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        Map<String, String> body = Map.of("body", commentText);

        HttpEntity<Map<String, String>> entity = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);
            System.out.println("✅ PR comment posted: " + response.getStatusCode());
        } catch (Exception e) {
            System.out.println("❌ Failed to post PR comment: " + e.getMessage());
        }
    }
}
