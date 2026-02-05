package com.salescode.cofman.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salescode.cofman.slack.SlackApprovalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConfigApprovalService {

    private final SlackApprovalService slackApprovalService;
    private final ConfigReaderService readerService;
    private final ObjectMapper objectMapper;

    /**
     * Send approval request for config update with old/new comparison
     * This replaces direct updates in ConfigUpdateController
     */
    public void requestConfigUpdateApproval(
            Map<String, List<Map<String, Object>>> updateRequest,
            String requestedBy) {

        try {
            List<Map<String, Object>> oldConfigs = updateRequest.getOrDefault("old", List.of());
            List<Map<String, Object>> newConfigs = updateRequest.getOrDefault("new", List.of());

            // Convert to pretty JSON strings for display
            String oldConfigJson = oldConfigs.isEmpty()
                    ? null
                    : objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(oldConfigs);

            String newConfigJson = newConfigs.isEmpty()
                    ? null
                    : objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(newConfigs);

            // Extract LOB and env from first config for metadata
            String lob = "unknown";
            String env = "unknown";

            if (!newConfigs.isEmpty()) {
                lob = getString(newConfigs.get(0), "lob", "default");
                env = getString(newConfigs.get(0), "env", "ALL");
            } else if (!oldConfigs.isEmpty()) {
                lob = getString(oldConfigs.get(0), "lob", "default");
                env = getString(oldConfigs.get(0), "env", "ALL");
            }

            // Send to Slack for approval
            slackApprovalService.sendConfigUpdateApprovalRequest(
                    lob,
                    env,
                    requestedBy,
                    oldConfigJson,
                    newConfigJson,
                    updateRequest  // Pass the entire request to store in button value
            );

        } catch (Exception e) {
            log.error("Failed to send config update approval request", e);
            throw new RuntimeException("Failed to send approval request", e);
        }
    }

    /**
     * Send approval request for LOB push to environment
     */
    public void requestLobPushApproval(String lob, String env, String requestedBy) {
        try {
            // Get current configs for this LOB/env
            List<Map<String, Object>> currentConfigs = readerService.getConfigsByLobAndEnv(lob, env);

            String oldConfigJson = currentConfigs.isEmpty()
                    ? null
                    : objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(currentConfigs);

            String newConfigJson = "Pushing current local configs to remote environment";

            slackApprovalService.sendFullEnvApprovalRequest(
                    lob,
                    env,
                    requestedBy,
                    oldConfigJson,
                    newConfigJson
            );

        } catch (Exception e) {
            log.error("Failed to send LOB push approval request", e);
            throw new RuntimeException("Failed to send approval request", e);
        }
    }

    /**
     * Send approval request for specific domain push
     */
    public void requestDomainPushApproval(
            String lob, String env,
            String domainName, String domainType,
            String requestedBy) {

        try {
            // Get current config for this specific domain
            List<Map<String, Object>> allConfigs = readerService.getConfigsByLobAndEnv(lob, env);

            List<Map<String, Object>> domainConfigs = allConfigs.stream()
                    .filter(c -> domainName.equals(c.get("domainName"))
                            && domainType.equals(c.get("domainType")))
                    .toList();

            String oldConfigJson = domainConfigs.isEmpty()
                    ? null
                    : objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(domainConfigs);

            String newConfigJson = "Pushing current local config to remote environment";

            slackApprovalService.sendDomainApprovalRequest(
                    lob,
                    env,
                    domainName,
                    domainType,
                    requestedBy,
                    oldConfigJson,
                    newConfigJson
            );

        } catch (Exception e) {
            log.error("Failed to send domain push approval request", e);
            throw new RuntimeException("Failed to send approval request", e);
        }
    }

    /**
     * Send approval request for LOB copy (entire LOB or specific env)
     */
    public void requestLobCopyApproval(
            String fromLob,
            String toLob,
            String env,
            String requestedBy) {
        try {
            Map<String, String> data = new HashMap<>();
            data.put("fromLob", fromLob);
            data.put("toLob", toLob);
            data.put("env", env != null ? env : "ALL");
            data.put("type", "LOB_COPY");

            String dataJson = objectMapper.writeValueAsString(data);

            String envText = env != null ? " (Environment: " + env + ")" : " (All environments)";
            String message = String.format(
                    "Copying entire LOB '%s' to '%s'%s",
                    fromLob, toLob, envText
            );

            slackApprovalService.sendLobCopyApprovalRequest(
                    fromLob,
                    toLob,
                    env,
                    requestedBy,
                    message,
                    dataJson
            );

        } catch (Exception e) {
            log.error("Failed to send LOB copy approval request", e);
            throw new RuntimeException("Failed to send approval request", e);
        }
    }

    /**
     * Send approval request for specific domain config copy
     */
    public void requestDomainCopyApproval(
            String fromLob,
            String toLob,
            String env,
            String domainName,
            String domainType,
            String requestedBy) {
        try {
            Map<String, String> data = new HashMap<>();
            data.put("fromLob", fromLob);
            data.put("toLob", toLob);
            data.put("env", env != null ? env : "ALL");
            data.put("domainName", domainName);
            data.put("domainType", domainType);
            data.put("type", "DOMAIN_COPY");

            String dataJson = objectMapper.writeValueAsString(data);

            String envText = env != null ? " (Environment: " + env + ")" : " (All environments)";
            String message = String.format(
                    "Copying domain config '%s/%s' from LOB '%s' to '%s'%s",
                    domainName, domainType, fromLob, toLob, envText
            );

            slackApprovalService.sendDomainCopyApprovalRequest(
                    fromLob,
                    toLob,
                    env,
                    domainName,
                    domainType,
                    requestedBy,
                    message,
                    dataJson
            );

        } catch (Exception e) {
            log.error("Failed to send domain copy approval request", e);
            throw new RuntimeException("Failed to send approval request", e);
        }
    }

    private String getString(Map<String, Object> map, String key, String defaultValue) {
        Object value = map.get(key);
        return value != null ? value.toString() : defaultValue;
    }
}