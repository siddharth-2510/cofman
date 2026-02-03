package com.salescode.cofman.slack;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salescode.cofman.controller.ConfigController;
import com.salescode.cofman.controller.ConfigUpdateController;
import com.salescode.cofman.services.ConfigUpdateService;
import com.salescode.cofman.services.LOBService;
import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.request.chat.ChatPostMessageRequest;
import com.slack.api.methods.response.chat.ChatPostMessageResponse;
import com.slack.api.model.block.LayoutBlock;
import com.slack.api.model.block.SectionBlock;
import com.slack.api.model.block.composition.MarkdownTextObject;
import com.slack.api.model.block.composition.PlainTextObject;
import com.slack.api.model.block.element.ButtonElement;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class SlackApprovalService {

    private final MethodsClient methodsClient;
    private final ObjectMapper objectMapper;
    private final LOBService lobService;
    private final ConfigUpdateService configUpdateService;

    @Value("${slack.channel.id}")
    private String channelId;

    /**
     * Send approval request for full env push (lob + env only)
     */
    public void sendFullEnvApprovalRequest(String lob, String env, String requestedBy,
                                           String oldConfig, String newConfig) {
        try {
            Map<String, String> data = new HashMap<>();
            data.put("lob", lob);
            data.put("env", env);
            data.put("type", "FULL_ENV");

            String dataJson = objectMapper.writeValueAsString(data);

            StringBuilder configChanges = getBuilder(oldConfig, newConfig);

            String text = String.format("*Full Environment Push Request*\n" +
                            "LOB: `%s`\n" +
                            "Environment: `%s`\n" +
                            "Requested by: %s\n\n%s",
                    lob, env, requestedBy, configChanges.toString());

            sendApprovalMessage(text, dataJson);

        } catch (Exception e) {
            log.error("Failed to send full env approval request", e);
            throw new RuntimeException("Failed to send Slack approval", e);
        }
    }

    /**
     * Send approval request for config update (for ConfigUpdateController)
     */
    public void sendConfigUpdateApprovalRequest(
            String lob, String env, String requestedBy,
            String oldConfig, String newConfig,
            Map<String, List<Map<String, Object>>> updateRequest) {

        try {
            Map<String, Object> data = new HashMap<>();
            data.put("lob", lob);
            data.put("env", env);
            data.put("type", "CONFIG_UPDATE");

            // Store the entire update request as JSON string
            String requestJson = objectMapper.writeValueAsString(updateRequest);
            data.put("updateRequest", requestJson);

            String dataJson = objectMapper.writeValueAsString(data);

            StringBuilder configChanges = getBuilder(oldConfig, newConfig);

            String text = String.format("*Config Update Request*\n" +
                            "LOB: `%s`\n" +
                            "Environment: `%s`\n" +
                            "Requested by: %s\n\n%s",
                    lob, env, requestedBy, configChanges.toString());

            sendApprovalMessage(text, dataJson);

        } catch (Exception e) {
            log.error("Failed to send config update approval request", e);
            throw new RuntimeException("Failed to send Slack approval", e);
        }
    }

    private static  StringBuilder getBuilder(String oldConfig, String newConfig) {
        StringBuilder configChanges = new StringBuilder();

        if (oldConfig != null && !oldConfig.isEmpty()) {
            configChanges.append("*Old Configuration:*\n");
            configChanges.append("```json\n");
            configChanges.append(oldConfig);
            configChanges.append("\n```\n\n");
        }

        configChanges.append("*New Configuration:*\n");
        configChanges.append("```json\n");
        configChanges.append(newConfig);
        configChanges.append("\n```\n");
        return configChanges;
    }

    /**
     * Send approval request for specific domain push (lob + env + domain + type)
     */
    public void sendDomainApprovalRequest(String lob, String env,
                                          String domainName, String domainType,
                                          String requestedBy,
                                          String oldConfig, String newConfig) {
        try {
            Map<String, String> data = new HashMap<>();
            data.put("lob", lob);
            data.put("env", env);
            data.put("domainName", domainName);
            data.put("domainType", domainType);
            data.put("type", "DOMAIN_SPECIFIC");

            String dataJson = objectMapper.writeValueAsString(data);

            StringBuilder configChanges = getBuilder(oldConfig, newConfig);

            String text = String.format("*Domain-Specific Push Request*\n" +
                            "LOB: `%s`\n" +
                            "Environment: `%s`\n" +
                            "Domain: `%s`\n" +
                            "Type: `%s`\n" +
                            "Requested by: %s\n\n%s",
                    lob, env, domainName, domainType, requestedBy, configChanges.toString());

            sendApprovalMessage(text, dataJson);

        } catch (Exception e) {
            log.error("Failed to send domain approval request", e);
            throw new RuntimeException("Failed to send Slack approval", e);
        }
    }

    /**
     * Common method to send message with approve/reject buttons
     */
    private void sendApprovalMessage(String text, String dataJson) {
        try {
            List<LayoutBlock> blocks = Arrays.asList(
                    SectionBlock.builder()
                            .text(MarkdownTextObject.builder().text(text).build())
                            .build(),

                    com.slack.api.model.block.ActionsBlock.builder()
                            .elements(Arrays.asList(
                                    ButtonElement.builder()
                                            .text(PlainTextObject.builder().text("Approve ✅").build())
                                            .style("primary")
                                            .value(dataJson)
                                            .actionId("approve_push")
                                            .build(),

                                    ButtonElement.builder()
                                            .text(PlainTextObject.builder().text("Reject ❌").build())
                                            .style("danger")
                                            .value(dataJson)
                                            .actionId("reject_push")
                                            .build()
                            ))
                            .build()
            );

            ChatPostMessageRequest request = ChatPostMessageRequest.builder()
                    .channel(channelId)
                    .text("Approval Request")
                    .blocks(blocks)
                    .build();

            ChatPostMessageResponse response = methodsClient.chatPostMessage(request);

            if (!response.isOk()) {
                log.error("Slack API error: {}", response.getError());
                throw new RuntimeException("Failed to post message to Slack: " + response.getError());
            }

            log.info("Approval message sent to Slack successfully");

        } catch (Exception e) {
            log.error("Error sending Slack message", e);
            throw new RuntimeException("Failed to send Slack message", e);
        }
    }

    public void handleApproval(String dataJson) {
        try {
            Map<String, String> data = objectMapper.readValue(dataJson, Map.class);
            String type = data.get("type");

            if ("FULL_ENV".equals(type)) {
                String lob = data.get("lob");
                String env = data.get("env");
                log.info("Approved: Full env push for lob={}, env={}", lob, env);
                lobService.pushToEnv(env, lob);

            } else if ("DOMAIN_SPECIFIC".equals(type)) {
                String lob = data.get("lob");
                String env = data.get("env");
                String domainName = data.get("domainName");
                String domainType = data.get("domainType");
                log.info("Approved: Domain push for lob={}, env={}, domain={}, type={}",
                        lob, env, domainName, domainType);
                lobService.pushToEnv(domainName, domainType, lob, env);

            } else if ("CONFIG_UPDATE".equals(type)) {
                String updateRequestJson = data.get("updateRequest");
                Map<String, List<Map<String, Object>>> updateRequest =
                        objectMapper.readValue(updateRequestJson, Map.class);

                log.info("Approved: Config update for lob={}, env={}",
                        data.get("lob"), data.get("env"));

                // Call service instead of controller
                configUpdateService.executeConfigUpdate(updateRequest);
            }

        } catch (Exception e) {
            log.error("Error handling approval", e);
            throw new RuntimeException("Failed to process approval", e);
        }
    }



    /**
     * Handle rejection action - called from webhook controller
     */
    public void handleRejection(String dataJson) {
        try {
            Map<String, String> data = objectMapper.readValue(dataJson, Map.class);
            log.info("Rejected push request: {}", data);

        } catch (Exception e) {
            log.error("Error handling rejection", e);
        }
    }
}