package com.sim.spriced.pahal.services;

import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class WfmGeneratorService {

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> generateWfmFromFsm(Map<String, Object> fsmPayload) {
        if (fsmPayload == null || !fsmPayload.containsKey("sections")) {
            return new ArrayList<>();
        }

        List<Map<String, Object>> sections = (List<Map<String, Object>>) fsmPayload.get("sections");
        List<String> workflowRawContent = new ArrayList<>();
        List<String> integrationRawContent = new ArrayList<>();

        for (Map<String, Object> section : sections) {
            String title = ((String) section.getOrDefault("section_title", "")).toLowerCase();
            List<String> rawContent = (List<String>) section.getOrDefault("raw_content", new ArrayList<>());

            if (title.contains("tbrd-02")) {
                workflowRawContent.addAll(rawContent);
            } else if (title.contains("tbrd-04")) {
                integrationRawContent.addAll(rawContent);
            }
        }

        return generateWorkflowMaps(workflowRawContent, integrationRawContent);
    }

    private List<Map<String, Object>> generateWorkflowMaps(List<String> wfLines, List<String> intLines) {
        if (wfLines.isEmpty() && intLines.isEmpty()) return new ArrayList<>();

        List<Map<String, Object>> maps = new ArrayList<>();
        Map<String, List<String>> wfBlocks = splitBlocks(wfLines, "workflow name", false);
        Map<String, List<String>> intBlocks = splitBlocks(intLines, "workflow name", true);

        for (Map.Entry<String, List<String>> entry : wfBlocks.entrySet()) {
            String wfName = entry.getKey();
            List<String> wfBlock = entry.getValue();
            List<String> intBlock = intBlocks.getOrDefault(wfName, new ArrayList<>());

            maps.add(buildWorkflowMap(wfName, wfBlock, intBlock));
        }
        return maps;
    }

    private Map<String, Object> buildWorkflowMap(String workflowName, List<String> wfBlock, List<String> intBlock) {
        Map<String, Object> intDetails = extractIntegrationDetails(intBlock);
        String entityName = extractEntityName(wfBlock);
        if (entityName.isEmpty()) entityName = extractEntityName(intBlock);

        List<Map<String, String>> services = extractServices(wfBlock);
        List<Map<String, Object>> sourceMeta = extractSourceMetadata(intBlock);
        List<Map<String, Object>> targetMeta = extractTargetMetadata(intBlock, sourceMeta);
        List<Map<String, Object>> filters = extractGlobalFilters(intBlock);

        String fileName = (String) intDetails.getOrDefault("fileName", "");
        String ttmKey = fileName.replaceAll("(?i)\\.(csv|xlsx|json)$", "");

        Map<String, Object> ttmData = new LinkedHashMap<>();
        ttmData.put("sourceMetadata", sourceMeta);
        ttmData.put("targetMetadata", targetMeta);
        ttmData.put("globalRecordFilter", filters);

        Map<String, Object> ttmNode = new LinkedHashMap<>();
        ttmNode.put(ttmKey, ttmData);

        Map<String, Object> subExec = new LinkedHashMap<>();
        subExec.put("ttm", ttmNode);
        subExec.put("crud", Map.of("operation", "insert", "businessKey", new ArrayList<>()));
        subExec.put("start_time", intDetails.get("start_time"));
        subExec.put("poll_time", intDetails.get("poll_time"));
        subExec.put("poll_interval", intDetails.get("poll_interval"));
        subExec.put("fileName", fileName);
        subExec.put("sourceType", intDetails.get("sourceType"));
        subExec.put("SourceConfig", intDetails.get("SourceConfig"));
        subExec.put("sinkType", "GRPC_SINK");
        subExec.put("sinkConfig", Map.of("api_url", "", "sinkPath", "", "encryptPublicKey", "", "encryptPassphrase", ""));
        subExec.put("entityName", entityName);
        subExec.put("referenceEntity", new ArrayList<>());
        subExec.put("serviceName", services);

        Map<String, Object> workflowMap = new LinkedHashMap<>();
        workflowMap.put("workflowName", workflowName);
        workflowMap.put("subWorkflowExecution", Collections.singletonList(subExec));

        return workflowMap;
    }

    private Map<String, List<String>> splitBlocks(List<String> lines, String keyword, boolean resetOnInbound) {
        Map<String, List<String>> blocks = new LinkedHashMap<>();
        String currentName = "";
        List<String> currentBlock = new ArrayList<>();

        for (String line : lines) {
            String lowerLine = line.toLowerCase().trim();

            if (resetOnInbound && lowerLine.contains("inbound/outbound")) {
                if (!currentName.isEmpty() && !currentBlock.isEmpty()) {
                    blocks.put(currentName, currentBlock);
                }
                currentBlock = new ArrayList<>();
                currentName = "";
            }

            currentBlock.add(line.trim());

            if (lowerLine.contains(keyword)) {
                String extracted = extractValueAfterKeyword(line, keyword);
                if (!extracted.isEmpty()) {
                    if (!resetOnInbound && !currentName.isEmpty() && !currentBlock.isEmpty()) {
                        blocks.put(currentName, new ArrayList<>(currentBlock.subList(0, currentBlock.size() - 1)));
                        currentBlock = new ArrayList<>(Collections.singletonList(line.trim()));
                    }
                    currentName = extracted;
                }
            }
        }
        if (!currentName.isEmpty() && !currentBlock.isEmpty()) {
            blocks.put(currentName, currentBlock);
        }
        return blocks;
    }

    private String extractEntityName(List<String> block) {
        for (String line : block) {
            if (line.toLowerCase().contains("target deam")) {
                return normalizeName(extractValueAfterKeyword(line, "target deam"));
            }
        }
        return "";
    }

    private List<Map<String, String>> extractServices(List<String> block) {
        List<Map<String, String>> services = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String funcName = "", version = "1.0";

        for (String line : block) {
            String lower = line.toLowerCase();
            if (lower.contains("function name")) funcName = extractValueAfterKeyword(line, "function name");
            if (lower.contains("version")) {
                version = extractValueAfterKeyword(line, "version");
                if (!funcName.isEmpty()) {
                    String key = funcName + "|" + version;
                    if (seen.add(key)) {
                        Map<String, String> srv = new LinkedHashMap<>();
                        srv.put("version", version);
                        srv.put("functionName", funcName);
                        services.add(srv);
                    }
                    funcName = ""; version = "1.0";
                }
            }
        }
        return services;
    }

    private Map<String, Object> extractIntegrationDetails(List<String> block) {
        Map<String, Object> details = new LinkedHashMap<>();

        boolean hasSftp = block.stream().anyMatch(l -> l.toLowerCase().contains("sftp"));
        boolean hasApi = block.stream().anyMatch(l -> l.toLowerCase().contains("api"));
        details.put("sourceType", hasSftp ? "SFTP_SOURCE" : (hasApi ? "API_SOURCE" : "LOCAL_SOURCE"));

        Map<String, String> sc = new LinkedHashMap<>();
        sc.put("port", ""); sc.put("hostname", ""); sc.put("username", ""); sc.put("splitSize", "");
        sc.put("destination", ""); sc.put("sftpEnabled", "false"); sc.put("localSourceDir", "");
        sc.put("sftpPassphrase", ""); sc.put("sftpPrivateKey", ""); sc.put("sftpRemotePath", "");
        sc.put("decryptPassphrase", ""); sc.put("decryptPrivateKey", "");

        details.put("fileName", ""); details.put("start_time", "");
        details.put("poll_time", ""); details.put("poll_interval", "");

        for (String line : block) {
            String lower = line.toLowerCase();
            if (lower.contains("file name")) details.put("fileName", extractValueAfterKeyword(line, "file name"));
            if (lower.contains("hostname")) sc.put("hostname", extractValueAfterKeyword(line, "hostname"));
            if (lower.contains("port")) sc.put("port", extractValueAfterKeyword(line, "port"));
            if (lower.contains("username")) sc.put("username", extractValueAfterKeyword(line, "username"));
            if (lower.contains("remote path")) sc.put("sftpRemotePath", extractValueAfterKeyword(line, "remote path"));
            if (lower.contains("local destination")) sc.put("destination", extractValueAfterKeyword(line, "local destination"));
            if (lower.contains("sftp enabled")) sc.put("sftpEnabled", lower.contains("yes") ? "true" : "false");
            if (lower.contains("start time")) details.put("start_time", extractValueAfterKeyword(line, "start time"));
            if (lower.contains("poll time")) details.put("poll_time", extractValueAfterKeyword(line, "poll time"));
            if (lower.contains("poll interval")) details.put("poll_interval", extractValueAfterKeyword(line, "poll interval"));
        }
        details.put("SourceConfig", sc);
        return details;
    }

    private List<Map<String, Object>> extractSourceMetadata(List<String> block) {
        List<Map<String, Object>> meta = new ArrayList<>();
        boolean capture = false;

        for (String line : block) {
            String lower = line.toLowerCase().trim();
            if (lower.contains("source fields")) { capture = true; continue; }
            if (capture && lower.contains("output field mapping")) break;
            if (!capture || !line.contains("—")) continue;

            String[] parts = line.split("—");
            if (parts.length < 2) continue;

            String src = parts[0].trim();
            String typeText = parts[1].trim().toLowerCase();
            String type = "string";
            String length = null;

            if (typeText.contains("varchar")) {
                Matcher m = Pattern.compile("\\((\\d+)\\)").matcher(typeText);
                if (m.find()) length = m.group(1);
            } else if (typeText.matches(".*(numeric|decimal|double).*")) {
                type = "numeric";
            } else if (typeText.contains("date")) {
                type = "datetime";
            }

            Map<String, Object> node = new LinkedHashMap<>();
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("length", length);
            props.put("dataType", type);
            node.put(src, props);
            meta.add(node);
        }
        return meta;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractTargetMetadata(List<String> block, List<Map<String, Object>> srcMeta) {
        List<Map<String, Object>> meta = new ArrayList<>();
        Map<String, String> typeMap = new HashMap<>();

        for (Map<String, Object> item : srcMeta) {
            item.forEach((k, v) -> typeMap.put(k, (String) ((Map<String, Object>) v).get("dataType")));
        }

        boolean capture = false;
        for (String line : block) {
            String lower = line.toLowerCase().trim();
            if (lower.contains("output field mapping")) { capture = true; continue; }
            if (capture && lower.contains(" format")) break;
            if (!capture || !line.contains("←")) continue;

            String[] parts = line.split("←");
            if (parts.length < 2) continue;

            String target = normalizeName(parts[0].trim());
            String src = parts[1].trim();

            Map<String, Object> mapping = new LinkedHashMap<>();
            mapping.put("mapTo", Collections.singletonList(target));
            mapping.put("dataType", typeMap.getOrDefault(src, "string"));
            mapping.put("attributes", Collections.singletonList(src));
            mapping.put("filtration", new HashMap<>());
            mapping.put("transformations", Map.of("operations", new ArrayList<>()));
            meta.add(mapping);
        }
        return meta;
    }

    private List<Map<String, Object>> extractGlobalFilters(List<String> block) {
        Map<String, Map<String, Object>> filters = new LinkedHashMap<>();
        boolean capture = false;

        for (String line : block) {
            String lower = line.toLowerCase().trim();
            if (lower.contains("global record filtering")) { capture = true; continue; }
            if (capture && lower.contains("attribute-level filtering")) break;
            if (!capture || lower.isEmpty()) continue;

            String op = "", attr = "";
            List<String> vals = new ArrayList<>();

            if (line.contains("!=")) { op = "NOT EQUAL"; String[] s = line.split("!="); attr = s[0]; for(String v: s[1].split(",")) vals.add(v.trim()); }
            else if (line.contains(">=")) { op = "GREATER THAN"; String[] s = line.split(">="); attr = s[0]; vals.add(s[1].trim()); }
            else if (line.contains(">")) { op = "GREATER THAN"; String[] s = line.split(">"); attr = s[0]; vals.add(s[1].trim()); }
            else if (line.contains("=")) { op = "EQUAL"; String[] s = line.split("="); attr = s[0]; for(String v: s[1].split(",")) vals.add(v.trim()); }
            else continue;

            attr = normalizeName(attr.trim());
            String key = attr + "|" + op;

            filters.putIfAbsent(key, new LinkedHashMap<>(Map.of("attribute", attr, "operator", op, "value", new ArrayList<String>())));

            @SuppressWarnings("unchecked")
            List<String> existingVals = (List<String>) filters.get(key).get("value");
            for (String v : vals) { if (!existingVals.contains(v)) existingVals.add(v); }
        }
        return new ArrayList<>(filters.values());
    }

    // --- UTILITIES ---

    private String normalizeName(String value) {
        if (value == null) return "";
        value = value.toLowerCase().trim();
        value = value.replaceAll("[/\\-–\\(\\),.:]", " ");
        value = value.replaceAll("\\s+", "_");
        value = value.replaceAll("[^a-z0-9_]", "");
        value = value.replaceAll("_+", "_");
        if (value.startsWith("_")) value = value.substring(1);
        if (value.endsWith("_")) value = value.substring(0, value.length() - 1);
        return value;
    }

    private String extractValueAfterKeyword(String line, String keyword) {
        String cleanedLine = line.trim().replaceFirst("^\\d+(\\.\\d+)*\\s*", "");
        Pattern p = Pattern.compile(Pattern.quote(keyword) + "\\s*[:\\-–]?\\s*(.*)", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(cleanedLine);
        if (m.find()) {
            String val = m.group(1).trim();
            while (val.startsWith(":") || val.startsWith("-") || val.startsWith("–")) {
                val = val.substring(1).trim();
            }
            return val;
        }
        return "";
    }
}