package com.salescode.cofman.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salescode.cofman.slack.SlackApprovalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

@Slf4j
@RestController
@RequestMapping("/api/slack")
@RequiredArgsConstructor
public class WebhookController {

    private final SlackApprovalService slackApprovalService;
    private final ObjectMapper objectMapper;

    @Value("${slack.signing.secret}")
    private String signingSecret;

    @PostMapping(value = "/interactions", consumes = "application/x-www-form-urlencoded")
    public ResponseEntity<String> handleInteraction(
            InputStream inputStream,
            @RequestHeader("X-Slack-Request-Timestamp") String timestamp,
            @RequestHeader("X-Slack-Signature") String signature) {

        try {
            // Read raw bytes BEFORE any Spring processing
            byte[] rawBytes = inputStream.readAllBytes();
            String rawPayload = new String(rawBytes, StandardCharsets.UTF_8);

            log.info("Received Slack interaction");
            log.debug("Raw payload: {}", rawPayload);

            // Verify signature using the RAW payload (URL-encoded form data)
            if (!verifySlackRequest(rawPayload, timestamp, signature)) {
                log.error("Invalid Slack signature");
                return ResponseEntity.status(401).body("Invalid signature");
            }

            // Now decode the payload
            String jsonPayload = rawPayload;
            if (rawPayload.startsWith("payload=")) {
                jsonPayload = URLDecoder.decode(
                        rawPayload.substring(8),
                        StandardCharsets.UTF_8
                );
            }

            log.info("Decoded payload preview: {}",
                    jsonPayload.substring(0, Math.min(200, jsonPayload.length())));

            JsonNode payloadNode = objectMapper.readTree(jsonPayload);

            // **EXTRACT MESSAGE TIMESTAMP AND CHANNEL**
            String messageTs = payloadNode.get("message").get("ts").asText();
            String channelId = payloadNode.get("channel").get("id").asText();
            String userId = payloadNode.path("user").path("id").asText();
            String userName = payloadNode.path("user").path("name").asText();

            log.info("Message TS: {}, Channel: {}, User: {}", messageTs, channelId, userName);

            // Extract action details
            JsonNode actions = payloadNode.get("actions");
            if (actions != null && actions.isArray() && actions.size() > 0) {
                JsonNode action = actions.get(0);
                String actionId = action.get("action_id").asText();
                String value = action.get("value").asText();

                log.info("Action: {}", actionId);

                if ("approve_push".equals(actionId)) {
                    slackApprovalService.handleApproval(value);
                    // **UPDATE MESSAGE TO SHOW APPROVAL**
                    slackApprovalService.updateMessageStatus(
                            channelId, messageTs, "APPROVED", userName, value);
                    return ResponseEntity.ok("✅ Approved and processed successfully!");

                } else if ("reject_push".equals(actionId)) {
                    slackApprovalService.handleRejection(value);
                    // **UPDATE MESSAGE TO SHOW REJECTION**
                    slackApprovalService.updateMessageStatus(
                            channelId, messageTs, "REJECTED", userName, value);
                    return ResponseEntity.ok("❌ Rejected");
                }
            }

            return ResponseEntity.ok("OK");

        } catch (Exception e) {
            log.error("Error handling Slack interaction", e);
            return ResponseEntity.status(500).body("Error: " + e.getMessage());
        }
    }

    private boolean verifySlackRequest(String rawPayload, String timestamp, String signature) {
        try {
            // Check timestamp (within 5 minutes)
            long requestTime = Long.parseLong(timestamp);
            long currentTime = System.currentTimeMillis() / 1000;

            if (Math.abs(currentTime - requestTime) > 300) {
                log.warn("Request timestamp too old");
                return false;
            }

            // Build base string: v0:timestamp:raw_body
            String baseString = "v0:" + timestamp + ":" + rawPayload;

            // Compute HMAC-SHA256
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKey = new SecretKeySpec(
                    signingSecret.getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"
            );
            mac.init(secretKey);

            byte[] hash = mac.doFinal(baseString.getBytes(StandardCharsets.UTF_8));

            // Convert to hex
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }

            String computedSignature = "v0=" + hexString.toString();

            boolean isValid = computedSignature.equals(signature);

            if (!isValid) {
                log.error("Signature mismatch!");
                log.error("Expected: {}", signature);
                log.error("Computed: {}", computedSignature);
            }

            return isValid;

        } catch (Exception e) {
            log.error("Error verifying signature", e);
            return false;
        }
    }
}