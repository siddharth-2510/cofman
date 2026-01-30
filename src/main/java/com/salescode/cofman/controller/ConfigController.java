package com.salescode.cofman.controller;

import com.salescode.cofman.model.Merge;
import com.salescode.cofman.model.dto.DomainConfig;
import com.salescode.cofman.model.dto.ReconstructResult;
import com.salescode.cofman.repository.MergeRepository;
import com.salescode.cofman.services.CSVImportService;
import com.salescode.cofman.services.ConfigReaderService;
import com.salescode.cofman.services.MergeService;
import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
@CrossOrigin("*")
public class ConfigController {

    @Autowired
    private ConfigReaderService configReaderService;

    @Autowired
    private MergeService mergeService;

    @Autowired
    private MergeRepository mergeRepository;

    /**
     * GET /api/configs
     * Returns list of all domain configs with elements
     *
     * Response: [{ domainName, domainType, elements: ["name1", "name2"] }]
     */
    @GetMapping("/configs")
    public ResponseEntity<List<Map<String, Object>>> getAllConfigs() {
        List<Map<String, Object>> configs = configReaderService.getAllConfigSummaries();
        return ResponseEntity.ok(configs);
    }

    /**
     * GET /api/configs/{domainName}/{domainType}
     * Returns all LOBs with reconstructed config including lob and env information
     *
     * Response: Map of LOB -> ReconstructResult { lob, domainName, domainType, env, jsonArray, elementCount, ... }
     */
    @GetMapping("/configs/{domainName}/{domainType}")
    public ResponseEntity<Map<String, List<ReconstructResult>>> getConfigsByDomain(
            @PathVariable String domainName,
            @PathVariable String domainType) {

        // Get all available LOBs for this domain
        Map<String, Object> detail = configReaderService.getDomainDetail(domainName, domainType);
        if (detail == null || detail.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        // Extract LOB names and reconstruct each
        Map<String, Object> lobs = (Map<String, Object>) detail.get("lobs");
        if (lobs == null || lobs.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        // Build reconstructed results for each LOB
        Map<String, List<ReconstructResult>> results = new LinkedHashMap<>();
        for (String lob : lobs.keySet()) {
            List<ReconstructResult> reconstructed = mergeService.getReconstructedConfig(lob, domainName, domainType);
            results.put(lob, reconstructed);
        }

        return ResponseEntity.ok(results);
    }

    /**
     * GET /api/{lob}/{domainName}/{domainType}
     * Returns reconstructed config for a specific LOB
     */
    @GetMapping("/{lob}/{domainName}/{domainType}")
    public ResponseEntity<List<ReconstructResult>> getReconstructedConfig(
            @PathVariable String lob,
            @PathVariable String domainName,
            @PathVariable String domainType) {
        return ResponseEntity.ok(mergeService.getReconstructedConfig(lob, domainName, domainType));
    }

    @GetMapping("/configs/lob/{lob}/env/{env}")
    public ResponseEntity<List<Map<String, Object>>> getReconstructedConfig(
            @PathVariable String lob,
            @PathVariable String env) {
        return ResponseEntity.ok(configReaderService.getConfigsByLobAndEnv(lob,env));
    }

    /**
     * GET /api/configs/{domainName}/{domainType}/{name}
     * Returns specific element value, optionally filtered by lob and env
     *
     * Query params: lob (optional), env (optional)
     */
    @GetMapping("/configs/{domainName}/{domainType}/{name}")
    public ResponseEntity<Object> getConfigValue(
            @PathVariable String domainName,
            @PathVariable String domainType,
            @PathVariable String name,
            @RequestParam(required = false) String lob,
            @RequestParam(required = false) String env) {
        Object value = configReaderService.getElementValue(domainName, domainType, name, lob, env);
        if (value == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(value);
    }

    /**
     * POST /api/merge
     * Submit a new merge request
     *
     * Request body: { fromBranch, toBranch, requester, lead, repoUrl, domainConfigs: [...] }
     */
    @PostMapping("/merge")
    public ResponseEntity<Map<String, Object>> submitMerge(@RequestBody MergeRequest request) {
        try {
            String id = UUID.randomUUID().toString();

            List<DomainConfig> domainConfigs = request.getDomainConfigs();
            Merge merge = Merge.builder()
                    .id(id)
                    .fromBranch(request.getFromBranch())
                    .toBranch(request.getToBranch())
                    .requester(request.getRequester())
                    .merger(request.getLead())
                    .repoUrl(request.getRepoUrl())
                    .domainConfigs(domainConfigs)
                    .approved(false)
                    .merged(false)
                    .build();

            mergeRepository.save(merge);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "id", id,
                    "message", "Merge request created successfully"
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * POST /api/configs/copy
     * Copy all configs from source LOB to target LOB
     *
     * Query params: fromLob (required), toLob (required), env (optional)
     */
    @PostMapping("/configs/copy")
    public ResponseEntity<Map<String, Object>> copyLobConfigs(
            @RequestParam String fromLob,
            @RequestParam String toLob,
            @RequestParam(required = false) String env) {
        try {
            List<String> copiedConfigs = configReaderService.copyLob(fromLob, toLob, env);

            String message = String.format(
                    "Successfully copied %d configs from %s to %s",
                    copiedConfigs.size(),
                    fromLob,
                    toLob
            );

            if (env != null) {
                message += " (environment: " + env + ")";
            }

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "copiedConfigs", copiedConfigs,
                    "count", copiedConfigs.size(),
                    "message", message
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", e.getMessage()
            ));
        }
    }

    @GetMapping("/merge/{user}")
    public ResponseEntity<List<Merge>> getAllMerge(@PathVariable String user) {
        List<Merge> merges = mergeRepository.findByRequester(user);
        merges.addAll(mergeRepository.findByMerger(user));
        return ResponseEntity.ok(merges);
    }

    /**
     * POST /api/merge/{id}
     * Initiate an approved merge
     */
    @PostMapping("/merge/{id}")
    public ResponseEntity<Map<String, Object>> initiateMerge(@PathVariable String id) {
        try {
            mergeService.initiateMerge(id);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Merge initiated successfully"
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * Request DTO for merge submission
     */
    @Getter
    @Setter
    public static class MergeRequest {
        private String fromBranch;
        private String toBranch;
        private String requester;
        private String lead;
        private String repoUrl;
        private List<DomainConfig> domainConfigs;
    }

    @GetMapping("/csv")
    public void csv() {
        CSVImportService csvImportService = new CSVImportService("src/main/resources");
        csvImportService.importCsv("src/main/resources/niine_uat.csv");
    }
}