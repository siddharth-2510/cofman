package com.salescode.cofman.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.salescode.cofman.model.dto.ReconstructResult;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Cipher;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Getter
@Service
public class LOBService {

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${salescode.auth.loginId:admin@applicate.in}")
    private String loginId;

    @Value("${salescode.auth.password:@1234}")
    private String password;

    @Value("${salescode.auth.publicKey}")
    private String publicKey;

    @Value("${config.base-path:src/main/resources/configs}")
    private String basePath;


    @Value("${config.base.url:https://%s.salescode.ai}")
    private String baseUrl;


    private ConfigRepositoryService repository;

    private ConfigTransformService transformService;
    @Autowired
    private ConfigReaderService readerService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    public void init() {
        this.repository = new ConfigRepositoryService(basePath);
        this.transformService = new ConfigTransformService(repository);
    }
    /**
     * Generates JWT token using RSA encrypted password
     */
    public String getToken(String lob, String env) {
        try {

            long timestamp = System.currentTimeMillis();
            String rawPassword = timestamp + ":" + password;String encryptedPassword = encrypt(rawPassword, publicKey);

            ObjectNode body = mapper.createObjectNode();
            body.put("loginId", loginId);
            body.put("password", encryptedPassword);
            body.put("lob", lob+env);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<ObjectNode> request = new HttpEntity<>(body, headers);

            String url = String.format(baseUrl+"/signin", env);

            ResponseEntity<JsonNode> response =
                    restTemplate.postForEntity(url, request, JsonNode.class);

            log.info("Auth response: {}", response.getBody());
            return response.getBody().get("token").asText();

        } catch (Exception e) {
            log.error("Error while generating token", e);
            throw new RuntimeException("Authentication failed", e);
        }
    }

    /* ================= RSA Utils ================= */

    private String encrypt(String data, String base64PublicKey) throws Exception {
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, getPublicKey(base64PublicKey));
        return Base64.getEncoder()
                .encodeToString(cipher.doFinal(data.getBytes()));
    }

    private PublicKey getPublicKey(String base64PublicKey) throws Exception {
        byte[] decodedKey = Base64.getDecoder().decode(base64PublicKey);
        X509EncodedKeySpec keySpec = new X509EncodedKeySpec(decodedKey);
        return KeyFactory.getInstance("RSA").generatePublic(keySpec);
    }

    public boolean pushToEnv(String domainName,String domainType,String lob,String env){
        ReconstructResult results = transformService.reconstruct(lob,domainName,domainType,env);
        ObjectNode node = objectMapper.createObjectNode();
        node.put("domainName",domainName);
        node.put("domainType",domainType);
        node.put("domainValues",results.getJsonArray());
        List<ObjectNode> apiBody = List.of(node);
        String url = String.format("http://localhost:8081"+"/metadata", env);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("lob", lob+env);
        headers.add("Authorization", getToken(lob,env));
        HttpEntity<List<ObjectNode>> requestEntity = new HttpEntity<>(apiBody, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(url, requestEntity, String.class);
        if(response.getStatusCode().is2xxSuccessful())
            return true;
        else
            return false;
    }

    public Map<String, Boolean> pushToEnv(String env, String lob) {

        Map<String, List<String>> domainsAndTypes =
                readerService.getDomainsAndTypes(lob);

        Map<String, Boolean> result = new HashMap<>();

        domainsAndTypes.forEach((domainName, types) -> {
            for (String domainType : types) {
                try {
                    boolean success = pushToEnv(domainName, domainType, lob, env);

                    String key = domainName + ":" + domainType;
                    result.put(key, success);

                } catch (Exception e) {
                    log.error(
                            "Failed to push domain={} type={} lob={} env={}",
                            domainName, domainType, lob, env, e
                    );

                    result.put(domainName + ":" + domainType, false);
                }
            }
        });

        return result;
    }


}
