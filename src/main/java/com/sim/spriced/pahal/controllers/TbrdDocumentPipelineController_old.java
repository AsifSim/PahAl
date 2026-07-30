//package com.sim.spriced.pahal.controllers;
//
//import com.fasterxml.jackson.databind.ObjectMapper;
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//import org.springframework.http.MediaType;
//import org.springframework.http.ResponseEntity;
//import org.springframework.web.bind.annotation.*;
//import org.springframework.web.multipart.MultipartFile;
//
//import java.io.IOException;
//import java.nio.file.Files;
//import java.nio.file.Path;
//import java.nio.file.Paths;
//import java.util.*;
//import java.util.regex.Matcher;
//import java.util.regex.Pattern;
//import java.util.zip.ZipEntry;
//import java.util.zip.ZipInputStream;
//
//@RestController
//@RequestMapping("/api/tbrd")
//public class TbrdDocumentPipelineController_old {
//
//    private static final Logger log = LoggerFactory.getLogger(TbrdDocumentPipelineController_old.class);
//    private final ObjectMapper objectMapper = new ObjectMapper();
//
//    @PostMapping(value = "/process-docx", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
//    public ResponseEntity<Map<String, Object>> processTbrdDocument(
//            @RequestParam("file") MultipartFile file) {
//
//        log.info("Entered processTbrdDocument()");
//        Map<String, Object> response = new LinkedHashMap<>();
//
//        try {
//            // STEP 1: Extract clean text without XML artifacts
//            String extractedText = extractCleanTextFromDocx(file);
//
//            // STEP 2: Build FSM JSON structure
//            Map<String, Object> fsm = buildFsmJson(file.getOriginalFilename(), extractedText);
//            @SuppressWarnings("unchecked")
//            List<Map<String, Object>> sections = (List<Map<String, Object>>) fsm.get("sections");
//
//            // STEP 3: Extract lines directly from FSM sections
//            List<String> deamLines = new ArrayList<>();
//            List<String> workflowLines = new ArrayList<>();
//            List<String> integrationLines = new ArrayList<>();
//
//            for (Map<String, Object> section : sections) {
//                String title = ((String) section.get("section_title")).toLowerCase();
//                @SuppressWarnings("unchecked")
//                List<String> rawContent = (List<String>) section.get("raw_content");
//
//                if (title.contains("tbrd-01")) {
//                    deamLines.addAll(rawContent);
//                } else if (title.contains("tbrd-02")) {
//                    workflowLines.addAll(rawContent);
//                } else if (title.contains("tbrd-04")) {
//                    integrationLines.addAll(rawContent);
//                }
//            }
//
//            // STEP 4: Parse DEAM entities using FSM lines
//            List<Map<String, Object>> deams = parseDeamEntities(deamLines);
//
//            // STEP 5: Parse Workflow Maps using FSM lines
//            List<Map<String, Object>> workflowMaps = buildWorkflowMaps(workflowLines, integrationLines);
//
//            // Build final output
//            Map<String, Object> fullOutput = new LinkedHashMap<>();
//            fullOutput.put("FSM", fsm);
//            fullOutput.put("deam_count", deams.size());
//            fullOutput.put("DEAMs", deams);
//            fullOutput.put("wfm_count", workflowMaps.size());
//            fullOutput.put("WFMs", workflowMaps);
//
//            // Save JSON to local build folder
//            Path generatedDir = Paths.get(System.getProperty("user.dir"), "generated");
//            Files.createDirectories(generatedDir);
//            String baseFileName = file.getOriginalFilename() != null ?
//                    file.getOriginalFilename().replace(".docx", "") : "tbrd_output";
//
//            Path jsonPath = generatedDir.resolve(baseFileName + "_output.json");
//            objectMapper.writerWithDefaultPrettyPrinter().writeValue(jsonPath.toFile(), fullOutput);
//
//            return ResponseEntity.ok(fullOutput);
//
//        } catch (Exception e) {
//            log.error("TBRD processing failed", e);
//            response.put("status", "ERROR");
//            response.put("message", "Processing failed: " + e.getMessage());
//            return ResponseEntity.internalServerError().body(response);
//        }
//    }
//
//    // ============================================
//    // CLEAN DOCX EXTRACTION (Strips all XML tags & separates cells)
//    // ============================================
//    private String extractCleanTextFromDocx(MultipartFile file) throws IOException {
//        StringBuilder rawXml = new StringBuilder();
//        try (ZipInputStream zipStream = new ZipInputStream(file.getInputStream())) {
//            ZipEntry entry;
//            while ((entry = zipStream.getNextEntry()) != null) {
//                if ("word/document.xml".equals(entry.getName())) {
//                    rawXml.append(new String(zipStream.readAllBytes()));
//                }
//                zipStream.closeEntry();
//            }
//        }
//
//        String xml = rawXml.toString();
//
//        // 1. Turn structural XML markers into line breaks
//        xml = xml.replaceAll("</w:p>", "\n");
//        xml = xml.replaceAll("</w:tr>", "\n");
//        xml = xml.replaceAll("</w:tc>", "\n"); // Cell separation
//        xml = xml.replaceAll("<w:br[^>]*/>", "\n");
//
//        // 2. Complete XML tag removal (eliminates w:pPr, w:rPr, w:left, w:sz, etc.)
//        String cleanText = xml.replaceAll("<[^>]+>", "");
//
//        // 3. Decode HTML/XML entities
//        cleanText = cleanText.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
//                .replace("&quot;", "\"").replace("&apos;", "'").replace("&nbsp;", " ");
//
//        // 4. Inject line breaks on inline key labels in case formatting merged them
//        cleanText = cleanText.replaceAll("(?i)(DEAM Name\\s*[:-])", "\n$1");
//        cleanText = cleanText.replaceAll("(?i)(Workflow Name\\s*[–:-])", "\n$1");
//        cleanText = cleanText.replaceAll("(?i)(Attr\\d+_name\\s*:)", "\n$1");
//        cleanText = cleanText.replaceAll("(?i)(Data type\\s*:)", "\n$1");
//        cleanText = cleanText.replaceAll("(?i)(Nullable\\s*:)", "\n$1");
//        cleanText = cleanText.replaceAll("(?i)(Database Engine)", "\n$1");
//        cleanText = cleanText.replaceAll("(?i)(Table / Collection)", "\n$1");
//        cleanText = cleanText.replaceAll("(?i)(Primary Key)", "\n$1");
//        cleanText = cleanText.replaceAll("(?i)(Business Key)", "\n$1");
//        cleanText = cleanText.replaceAll("(?i)(Attribute Specifications)", "\n$1");
//
//        return cleanText.trim();
//    }
//
//    // ============================================
//    // FSM GENERATION
//    // ============================================
//    private Map<String, Object> buildFsmJson(String documentName, String text) {
//        Map<String, Object> fsm = new LinkedHashMap<>();
//        fsm.put("model", "NA");
//
//        String nameWithoutExt = documentName;
//        if (documentName != null && documentName.contains(".")) {
//            nameWithoutExt = documentName.substring(0, documentName.lastIndexOf('.'));
//        }
//        fsm.put("document_name", nameWithoutExt);
//
//        List<Map<String, Object>> sections = new ArrayList<>();
//        Pattern markerPattern = Pattern.compile("TBRD\\s*-\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
//        Matcher markerMatcher = markerPattern.matcher(text);
//
//        List<int[]> markers = new ArrayList<>();
//        while (markerMatcher.find()) {
//            int sectionNum = Integer.parseInt(markerMatcher.group(1));
//            boolean exists = false;
//            for (int[] m : markers) {
//                if (m[1] == sectionNum) { exists = true; break; }
//            }
//            if (!exists) {
//                markers.add(new int[]{markerMatcher.start(), sectionNum});
//            }
//        }
//
//        markers.sort(Comparator.comparingInt(m -> m[0]));
//
//        for (int i = 0; i < markers.size(); i++) {
//            int[] current = markers.get(i);
//            int sectionStart = current[0];
//            int sectionEnd = (i + 1 < markers.size()) ? markers.get(i + 1)[0] : text.length();
//            int sectionNum = current[1];
//
//            String sectionContent = text.substring(sectionStart, sectionEnd).trim();
//            List<String> rawContent = new ArrayList<>();
//            for (String line : sectionContent.split("\n")) {
//                String trimmed = line.trim();
//                if (!trimmed.isEmpty()) {
//                    rawContent.add(trimmed);
//                }
//            }
//
//            if (!rawContent.isEmpty()) {
//                Map<String, Object> section = new LinkedHashMap<>();
//                section.put("section_title", getSectionTitle(sectionNum));
//                section.put("raw_content", rawContent);
//                sections.add(section);
//            }
//        }
//
//        fsm.put("sections", sections);
//        return fsm;
//    }
//
//    private String getSectionTitle(int sectionNum) {
//        switch (sectionNum) {
//            case 1:  return "TBRD-01: Business Data Entity (BDE) & Schema (DEAMs)";
//            case 2:  return "TBRD-02: Workflows & Orchestration";
//            case 3:  return "TBRD-03: Microservices & Business Rules (API)";
//            case 4:  return "TBRD-04: Inbound / Outbound / Dashboard / Excel Integration";
//            default: return String.format("TBRD-%02d", sectionNum);
//        }
//    }
//
//    // ============================================
//    // DEAM PARSING
//    // ============================================
//    private List<Map<String, Object>> parseDeamEntities(List<String> lines) {
//        List<Map<String, Object>> deams = new ArrayList<>();
//        String currentEntity = null;
//        List<Map<String, Object>> currentAttributes = new ArrayList<>();
//        List<String> currentBusinessKeys = new ArrayList<>();
//
//        for (int i = 0; i < lines.size(); i++) {
//            String line = lines.get(i).trim();
//            if (line.isEmpty()) continue;
//            String lowerLine = line.toLowerCase();
//
//            if (lowerLine.contains("deam name")) {
//                if (currentEntity != null && !currentAttributes.isEmpty()) {
//                    deams.add(createDeamObject(currentEntity, currentAttributes));
//                }
//                currentEntity = normalizeName(extractValue(line, "DEAM Name"));
//                currentAttributes = new ArrayList<>();
//                currentBusinessKeys = new ArrayList<>();
//                continue;
//            }
//
//            if (lowerLine.contains("business key")) {
//                i++;
//                while (i < lines.size()) {
//                    String bkLine = lines.get(i).trim();
//                    String lowerBk = bkLine.toLowerCase();
//                    if (lowerBk.contains("attribute spec") || lowerBk.contains("attr")) break;
//
//                    String cleaned = bkLine.replaceAll("[☒☐·•\\-]", "").trim();
//                    String normalized = normalizeName(cleaned);
//                    if (!normalized.isEmpty() && !normalized.contains("single") && !normalized.contains("composite")) {
//                        currentBusinessKeys.add(normalized);
//                    }
//                    i++;
//                }
//                continue;
//            }
//
//            if (lowerLine.matches(".*attr\\d+_name\\s*:.*") || (lowerLine.contains("attr") && lowerLine.contains("_name"))) {
//                String attrName = extractValue(line, "_name");
//                if (attrName.isEmpty()) {
//                    attrName = extractValue(line, "name");
//                }
//
//                String dataTypeLine = "";
//                String nullableLine = "";
//
//                if (i + 1 < lines.size() && lines.get(i + 1).toLowerCase().contains("data type")) {
//                    dataTypeLine = lines.get(i + 1).trim();
//                }
//                if (i + 2 < lines.size() && lines.get(i + 2).toLowerCase().contains("nullable")) {
//                    nullableLine = lines.get(i + 2).trim();
//                }
//
//                Map<String, Object> attr = createAttribute(attrName, dataTypeLine, nullableLine, currentBusinessKeys);
//                currentAttributes.add(attr);
//            }
//        }
//
//        if (currentEntity != null && !currentAttributes.isEmpty()) {
//            deams.add(createDeamObject(currentEntity, currentAttributes));
//        }
//
//        return deams;
//    }
//
//    private Map<String, Object> createDeamObject(String entityName, List<Map<String, Object>> attributes) {
//        Map<String, Object> deam = new LinkedHashMap<>();
//        deam.put("entity", entityName);
//
//        String displayName = entityName.replace("_", " ");
//        if (!displayName.isEmpty()) {
//            displayName = displayName.substring(0, 1).toUpperCase() + displayName.substring(1);
//        }
//        deam.put("displayName", displayName);
//        deam.put("attributesList", attributes);
//        deam.put("primaryKeyType", "string");
//        deam.put("primaryKey", "uuid");
//        deam.put("businessRules", new ArrayList<>());
//        deam.put("childEntities", new ArrayList<>());
//        deam.put("deamReads", Collections.singletonList(createDynamicFilter(attributes)));
//        return deam;
//    }
//
//    private Map<String, Object> createDynamicFilter(List<Map<String, Object>> attributes) {
//        List<Map<String, Object>> whereClause = new ArrayList<>();
//        boolean uuidExists = false;
//
//        for (Map<String, Object> attr : attributes) {
//            String name = (String) attr.get("name");
//            if (name == null || name.isEmpty()) continue;
//
//            if (name.equalsIgnoreCase("uuid")) uuidExists = true;
//
//            Map<String, Object> w = new LinkedHashMap<>();
//            w.put("operator", name.equalsIgnoreCase("uuid") ? "is not" : "=");
//            w.put("attributeName", name);
//            w.put("attributeValue", new ArrayList<>());
//            w.put("logicalOperator", "AND");
//            whereClause.add(w);
//        }
//
//        if (!uuidExists) {
//            Map<String, Object> w = new LinkedHashMap<>();
//            w.put("operator", "is not");
//            w.put("attributeName", "uuid");
//            w.put("attributeValue", new ArrayList<>());
//            w.put("logicalOperator", "AND");
//            whereClause.add(w);
//        }
//
//        Map<String, Object> filterBody = new LinkedHashMap<>();
//        filterBody.put("join", new HashMap<>());
//        filterBody.put("limit", null);
//        filterBody.put("where", whereClause);
//        filterBody.put("offset", null);
//        filterBody.put("orderBy", new HashMap<>());
//        filterBody.put("attributeNames", Collections.singletonList("*"));
//
//        Map<String, Object> df = new LinkedHashMap<>();
//        df.put("filter", filterBody);
//        df.put("filterName", "dynamic_filter");
//        df.put("filterType", "dynamic");
//        return df;
//    }
//
//    private Map<String, Object> createAttribute(String attrName, String dataTypeLine,
//                                                String nullableLine, List<String> businessKeys) {
//        String cleanName = normalizeName(attrName);
//
//        String rawType = extractValue(dataTypeLine, "data type");
//        if (rawType.isEmpty()) rawType = "string";
//        String rawTypeOnly = rawType.split("\\(")[0].trim();
//        String normalizedType = normalizeType(rawTypeOnly);
//
//        boolean nullable = nullableLine.toLowerCase().contains("yes");
//        boolean mandatory = !nullable;
//
//        Integer maxLength = extractMaxLength(rawType);
//        if (!"string".equals(normalizedType)) {
//            maxLength = null;
//        }
//
//        boolean isCodeField = cleanName.contains("code");
//        boolean isBusinessKey = businessKeys.contains(cleanName) || isCodeField;
//
//        Map<String, Object> attr = new LinkedHashMap<>();
//        attr.put("name", cleanName);
//        attr.put("type", normalizedType);
//        attr.put("scale", null);
//        attr.put("unique", isCodeField);
//        attr.put("indexed", isBusinessKey);
//        attr.put("mandatory", mandatory);
//        attr.put("maxLength", maxLength);
//        attr.put("precision", null);
//        attr.put("businessKey", isBusinessKey);
//
//        String displayName = cleanName.replace("_", " ");
//        if (!displayName.isEmpty()) {
//            displayName = displayName.substring(0, 1).toUpperCase() + displayName.substring(1);
//        }
//        attr.put("displayName", displayName);
//
//        attr.put("validValues", new ArrayList<>());
//        attr.put("staticString", "");
//        attr.put("autoGenerated", false);
//        attr.put("enableAttribute", true);
//        return attr;
//    }
//
//    // ============================================
//    // WORKFLOW PARSING
//    // ============================================
//    private List<Map<String, Object>> buildWorkflowMaps(List<String> workflowLines, List<String> integrationLines) {
//        List<Map<String, Object>> maps = new ArrayList<>();
//        Map<String, List<String>> wfBlocks = splitByWorkflowName(workflowLines);
//        Map<String, List<String>> intBlocks = splitIntegrationBlocks(integrationLines);
//
//        for (Map.Entry<String, List<String>> entry : wfBlocks.entrySet()) {
//            String wfName = entry.getKey();
//            List<String> wfBlock = entry.getValue();
//            List<String> intBlock = findMatchingIntegrationBlock(wfName, intBlocks);
//
//            Map<String, Object> wfMap = new LinkedHashMap<>();
//            wfMap.put("workflowName", wfName);
//            wfMap.put("subWorkflowExecution", Collections.singletonList(
//                    buildSubWorkflow(wfName, wfBlock, intBlock)
//            ));
//            maps.add(wfMap);
//        }
//        return maps;
//    }
//
//    private Map<String, List<String>> splitByWorkflowName(List<String> lines) {
//        Map<String, List<String>> blocks = new LinkedHashMap<>();
//        String currentName = "";
//        List<String> currentBlock = new ArrayList<>();
//
//        for (String line : lines) {
//            String trimmed = line.trim();
//            String lower = trimmed.toLowerCase();
//
//            if (lower.contains("workflow name")) {
//                if (!currentName.isEmpty() && !currentBlock.isEmpty()) {
//                    blocks.put(currentName, new ArrayList<>(currentBlock));
//                }
//                currentName = extractValue(trimmed, "workflow name");
//                currentBlock = new ArrayList<>();
//            }
//
//            if (!currentName.isEmpty()) {
//                currentBlock.add(trimmed);
//            }
//        }
//
//        if (!currentName.isEmpty() && !currentBlock.isEmpty()) {
//            blocks.put(currentName, currentBlock);
//        }
//        return blocks;
//    }
//
//    private Map<String, List<String>> splitIntegrationBlocks(List<String> lines) {
//        Map<String, List<String>> blocks = new LinkedHashMap<>();
//        String currentName = "";
//        List<String> currentBlock = new ArrayList<>();
//
//        for (String line : lines) {
//            String trimmed = line.trim();
//            String lower = trimmed.toLowerCase();
//
//            if (lower.contains("data source") || lower.matches(".*\\d+\\.\\d+\\.\\d+.*")) {
//                if (!currentName.isEmpty() && !currentBlock.isEmpty()) {
//                    blocks.put(currentName, new ArrayList<>(currentBlock));
//                }
//                currentBlock = new ArrayList<>();
//                currentName = "";
//            }
//
//            currentBlock.add(trimmed);
//
//            if (lower.contains("workflow name")) {
//                currentName = extractValue(trimmed, "workflow name");
//            }
//        }
//
//        if (!currentName.isEmpty() && !currentBlock.isEmpty()) {
//            blocks.put(currentName, currentBlock);
//        }
//        return blocks;
//    }
//
//    private List<String> findMatchingIntegrationBlock(String wfName, Map<String, List<String>> intBlocks) {
//        if (intBlocks.containsKey(wfName)) return intBlocks.get(wfName);
//        for (Map.Entry<String, List<String>> entry : intBlocks.entrySet()) {
//            if (entry.getKey().equalsIgnoreCase(wfName)) return entry.getValue();
//        }
//        return new ArrayList<>();
//    }
//
//    private Map<String, Object> buildSubWorkflow(String wfName, List<String> wfBlock, List<String> intBlock) {
//        Map<String, Object> sub = new LinkedHashMap<>();
//
//        String entityName = extractTargetDeam(intBlock.isEmpty() ? wfBlock : intBlock);
//        String sourceType = detectSourceType(intBlock);
//        String fileName = extractFieldFromBlocks(wfBlock, intBlock, "file name");
//        String startTime = extractFieldFromBlocks(wfBlock, intBlock, "start time");
//        String pollTime = extractFieldFromBlocks(wfBlock, intBlock, "poll time");
//        String pollInterval = extractFieldFromBlocks(wfBlock, intBlock, "poll interval");
//
//        Map<String, Object> sourceConfig = extractSourceConfig(intBlock);
//        List<Map<String, Object>> sourceMetadata = extractSourceMetadata(intBlock);
//        List<Map<String, Object>> targetMetadata = extractTargetMetadata(intBlock, sourceMetadata);
//        List<Map<String, Object>> services = extractServices(wfBlock);
//
//        String ttmKey = fileName.replaceAll("(?i)\\.(csv|xlsx|json|xml)$", "").trim();
//        if (ttmKey.isEmpty()) ttmKey = "default";
//
//        Map<String, Object> ttm = new LinkedHashMap<>();
//        Map<String, Object> ttmInner = new LinkedHashMap<>();
//        ttmInner.put("sourceMetadata", sourceMetadata);
//        ttmInner.put("targetMetadata", targetMetadata);
//        ttmInner.put("globalRecordFilter", new ArrayList<>());
//        ttm.put(ttmKey, ttmInner);
//
//        sub.put("ttm", ttm);
//        sub.put("crud", Map.of("operation", "insert", "businessKey", new ArrayList<>()));
//        sub.put("start_time", startTime);
//        sub.put("poll_time", pollTime);
//        sub.put("poll_interval", pollInterval);
//        sub.put("fileName", fileName);
//        sub.put("sourceType", sourceType);
//        sub.put("SourceConfig", sourceConfig);
//        sub.put("sinkType", "GRPC_SINK");
//        sub.put("sinkConfig", Map.of("api_url", "", "sinkPath", "", "encryptPublicKey", "", "encryptPassphrase", ""));
//        sub.put("entityName", entityName);
//        sub.put("referenceEntity", new ArrayList<>());
//        sub.put("serviceName", services);
//
//        return sub;
//    }
//
//    private String extractFieldFromBlocks(List<String> wfBlock, List<String> intBlock, String keyword) {
//        String val = extractValue(intBlock, keyword);
//        if (!val.isEmpty()) return val;
//        return extractValue(wfBlock, keyword);
//    }
//
//    private List<Map<String, Object>> extractServices(List<String> lines) {
//        List<Map<String, Object>> services = new ArrayList<>();
//        String currentFunction = "";
//        String currentVersion = "1.0";
//
//        for (String line : lines) {
//            String lower = line.toLowerCase().trim();
//
//            if (lower.contains("function name")) {
//                currentFunction = extractValue(line, "function name");
//            }
//
//            if (lower.contains("version")) {
//                String version = extractValue(line, "version");
//                if (!version.isEmpty()) currentVersion = version;
//
//                if (!currentFunction.isEmpty()) {
//                    services.add(Map.of("functionName", currentFunction, "version", currentVersion));
//                    currentFunction = "";
//                    currentVersion = "1.0";
//                }
//            }
//        }
//        return services;
//    }
//
//    // ============================================
//    // STRING CLEANING & HELPERS
//    // ============================================
//    private String extractValue(String line, String keyword) {
//        if (line == null) return "";
//        String cleaned = line.replaceAll("^\\d+(\\.\\d+)*\\s*", "").trim();
//
//        Pattern p = Pattern.compile(Pattern.quote(keyword) + "\\s*[:\\-–]?\\s*(.*)", Pattern.CASE_INSENSITIVE);
//        Matcher m = p.matcher(cleaned);
//        if (m.find()) {
//            String val = m.group(1).trim();
//
//            // Truncate at common inline trailing stop-words
//            val = val.replaceAll("(?i)\\s*(Data Type|Nullable|Primary Key|Business Key|Database Engine|Table / Collection|Attribute Specifications|Workflow Name|Status transition|Orchestration|Service Names|File Name).*", "").trim();
//            return val;
//        }
//        return "";
//    }
//
//    private String extractValue(List<String> lines, String keyword) {
//        for (String line : lines) {
//            String val = extractValue(line, keyword);
//            if (!val.isEmpty()) return val;
//        }
//        return "";
//    }
//
//    private String normalizeName(String value) {
//        if (value == null) return "";
//        value = value.toLowerCase().trim();
//
//        // Stop at any residual XML tag markers if encountered
//        if (value.contains("w_")) {
//            value = value.split("w_")[0];
//        }
//
//        for (String r : new String[]{"/", "-", "–", "(", ")", ",", ".", ":", ";", "\""}) {
//            value = value.replace(r, " ");
//        }
//
//        value = value.replaceAll("\\s+", "_");
//        value = value.replaceAll("[^a-z0-9_]", "");
//        value = value.replaceAll("_+", "_");
//        return value.replaceAll("^_|_$", "");
//    }
//
//    private String normalizeType(String raw) {
//        raw = raw.toLowerCase().trim();
//        if (raw.contains("date") || raw.contains("time")) return "datetime";
//        if (raw.contains("int")) return "integer";
//        if (raw.matches(".*(decimal|numeric|float|double|number).*")) return "numeric";
//        if (raw.contains("bool")) return "boolean";
//        return "string";
//    }
//
//    private Integer extractMaxLength(String typeText) {
//        Matcher m = Pattern.compile("\\((\\d+)\\)").matcher(typeText);
//        return m.find() ? Integer.parseInt(m.group(1)) : null;
//    }
//
//    private String extractTargetDeam(List<String> lines) {
//        return normalizeName(extractValue(lines, "target deam"));
//    }
//
//    private String detectSourceType(List<String> lines) {
//        String joined = String.join(" ", lines).toLowerCase();
//        if (joined.contains("sftp")) return "SFTP_SOURCE";
//        if (joined.contains("api") || joined.contains("rest")) return "API_SOURCE";
//        return "LOCAL_SOURCE";
//    }
//
//    private Map<String, Object> extractSourceConfig(List<String> lines) {
//        Map<String, Object> config = new LinkedHashMap<>();
//        config.put("hostname", extractValue(lines, "hostname"));
//        config.put("port", extractValue(lines, "port"));
//        config.put("username", extractValue(lines, "username"));
//        config.put("splitSize", "");
//        config.put("sftpRemotePath", extractValue(lines, "remote path"));
//        config.put("destination", extractValue(lines, "local destination"));
//        config.put("sftpPassphrase", "");
//        config.put("sftpPrivateKey", "");
//        config.put("decryptPassphrase", "");
//        config.put("decryptPrivateKey", "");
//        config.put("localSourceDir", "");
//
//        boolean isSftp = false;
//        for (String line : lines) {
//            if (line.toLowerCase().contains("sftp enabled") && line.toLowerCase().contains("yes")) {
//                isSftp = true;
//                break;
//            }
//        }
//        config.put("sftpEnabled", isSftp);
//
//        return config;
//    }
//
//    private List<Map<String, Object>> extractSourceMetadata(List<String> lines) {
//        List<Map<String, Object>> metadata = new ArrayList<>();
//        boolean capture = false;
//        for (String line : lines) {
//            String lower = line.toLowerCase().trim();
//            if (lower.contains("source fields")) { capture = true; continue; }
//            if (capture && lower.contains("output field mapping")) break;
//            if (!capture || (!line.contains("—") && !line.contains("-"))) continue;
//
//            String[] parts = line.split("[—\\-]");
//            if (parts.length < 2) continue;
//            String field = normalizeName(parts[0].trim());
//            String typeText = parts[1].trim().toLowerCase();
//
//            String dataType = "string";
//            String length = "";
//            if (typeText.contains("varchar")) {
//                dataType = "string";
//                Matcher m = Pattern.compile("\\((\\d+)\\)").matcher(typeText);
//                if (m.find()) length = m.group(1);
//            } else if (typeText.matches(".*(numeric|decimal|double).*")) {
//                dataType = "numeric";
//            } else if (typeText.contains("date")) {
//                dataType = "datetime";
//            }
//            metadata.add(Map.of(field, Map.of("dataType", dataType, "length", length)));
//        }
//        return metadata;
//    }
//
//    private List<Map<String, Object>> extractTargetMetadata(List<String> lines, List<Map<String, Object>> sourceMetadata) {
//        List<Map<String, Object>> metadata = new ArrayList<>();
//        Map<String, Map<String, Object>> typeMap = new LinkedHashMap<>();
//        for (Map<String, Object> item : sourceMetadata) {
//            item.forEach((k, v) -> typeMap.put(k, (Map<String, Object>) v));
//        }
//        boolean capture = false;
//        for (String line : lines) {
//            String lower = line.toLowerCase().trim();
//            if (lower.contains("output field mapping")) { capture = true; continue; }
//            if (capture && lower.contains("format")) break;
//            if (!capture || !line.contains("←")) continue;
//
//            String[] parts = line.split("←");
//            if (parts.length < 2) continue;
//            String target = normalizeName(parts[0].trim());
//            String source = normalizeName(parts[1].trim());
//
//            Map<String, Object> st = typeMap.getOrDefault(source, Map.of("dataType", "string"));
//
//            metadata.add(Map.of(
//                    "mapTo", Collections.singletonList(target),
//                    "dataType", st.getOrDefault("dataType", "string"),
//                    "attributes", Collections.singletonList(source),
//                    "filtration", new HashMap<>(),
//                    "transformations", Map.of("operations", new ArrayList<>())
//            ));
//        }
//        return metadata;
//    }
//}