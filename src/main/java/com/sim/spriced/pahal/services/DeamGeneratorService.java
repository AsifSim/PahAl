package com.sim.spriced.pahal.services;

import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class DeamGeneratorService {

    private static final Set<String> ALLOWED_TYPES = new HashSet<>(Arrays.asList(
            "string", "integer", "numeric", "boolean", "array", "datetime", "json", "long"
    ));

    private static final Set<String> DATETIME_ALIASES = new HashSet<>(Arrays.asList(
            "date", "timestamp", "time", "datetime", "datetime2", "datetimeoffset"
    ));

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> generateDeamsFromFsm(Map<String, Object> fsmPayload) {
        if (fsmPayload == null || !fsmPayload.containsKey("sections")) {
            return new ArrayList<>();
        }

        List<Map<String, Object>> sections = (List<Map<String, Object>>) fsmPayload.get("sections");
        List<String> deamRawContent = new ArrayList<>();

        for (Map<String, Object> section : sections) {
            String title = ((String) section.getOrDefault("section_title", "")).toLowerCase();
            List<String> rawContent = (List<String>) section.getOrDefault("raw_content", new ArrayList<>());

            if (title.contains("tbrd-01") && title.contains("business data entity") && title.contains("deam")) {
                deamRawContent.addAll(rawContent);
            }
        }

        if (deamRawContent.isEmpty()) {
            return new ArrayList<>();
        }

        return parseDeamContent(deamRawContent);
    }

    private List<Map<String, Object>> parseDeamContent(List<String> rawContent) {
        List<Map<String, Object>> deams = new ArrayList<>();
        String currentEntity = null;
        List<Map<String, Object>> currentAttributes = new ArrayList<>();
        List<String> currentBusinessKeys = new ArrayList<>();

        int i = 0;
        while (i < rawContent.size()) {
            String line = rawContent.get(i).trim();
            String lowerLine = line.toLowerCase();

            // DEAM Name
            if (lowerLine.contains("deam name")) {
                if (currentEntity != null) {
                    deams.add(createDeamObject(currentEntity, currentAttributes));
                }
                currentEntity = normalizeName(extractValueAfterKeyword(line, "deam name"));
                currentAttributes = new ArrayList<>();
                currentBusinessKeys = new ArrayList<>();
                i++;
                continue;
            }

            // Business Key
            if (lowerLine.contains("business key")) {
                i++;
                while (i < rawContent.size()) {
                    String bkLine = rawContent.get(i).trim();
                    if (bkLine.toLowerCase().contains("attribute specifications")) break;

                    if (bkLine.startsWith("·") || bkLine.startsWith("-")) {
                        String cleanedBk = normalizeName(bkLine.replace("·", "").replace("-", "").trim());
                        if (!cleanedBk.isEmpty()) currentBusinessKeys.add(cleanedBk);
                    }
                    i++;
                }
                continue;
            }

            // Attributes
            if (lowerLine.contains("attr") && lowerLine.contains("_name")) {
                String attrName = extractValueAfterKeyword(line, "_name");
                String dataTypeLine = (i + 1 < rawContent.size()) ? rawContent.get(i + 1).trim() : "";
                String nullableLine = (i + 2 < rawContent.size()) ? rawContent.get(i + 2).trim() : "";

                currentAttributes.add(createAttribute(attrName, dataTypeLine, nullableLine, currentBusinessKeys));
                i += 3;
                continue;
            }
            i++;
        }

        if (currentEntity != null) {
            deams.add(createDeamObject(currentEntity, currentAttributes));
        }
        return deams;
    }

    private Map<String, Object> createAttribute(String attrName, String dataTypeLine, String nullableLine, List<String> businessKeys) {
        String cleanAttrName = normalizeName(attrName);
        String rawTypeText = extractValueAfterKeyword(dataTypeLine, "data type");
        if (rawTypeText.isEmpty()) rawTypeText = "string";

        String rawTypeOnly = rawTypeText.split("\\(")[0].trim();
        String normalizedType = inferTypeFromAttributeName(cleanAttrName, normalizeType(rawTypeOnly));

        boolean isNullable = nullableLine.toLowerCase().contains("yes");
        boolean isMandatory = inferMandatory(cleanAttrName, !isNullable);

        Integer maxLength = (normalizedType.equals("string")) ? inferMaxLength(cleanAttrName, extractMaxLength(rawTypeText)) : null;
        Integer[] precisionScale = inferPrecisionScale(cleanAttrName, normalizedType);

        boolean isCodeField = cleanAttrName.contains("code");
        boolean isIndexed = isCodeField || businessKeys.contains(cleanAttrName);

        Map<String, Object> attribute = new LinkedHashMap<>();
        attribute.put("name", cleanAttrName);
        attribute.put("type", ALLOWED_TYPES.contains(normalizedType) ? normalizedType : "string");
        attribute.put("scale", precisionScale[1]);
        attribute.put("unique", isCodeField);
        attribute.put("indexed", isIndexed);
        attribute.put("mandatory", isMandatory);
        attribute.put("maxLength", maxLength);
        attribute.put("precision", precisionScale[0]);
        attribute.put("businessKey", isIndexed);
        attribute.put("displayName", formatDisplayName(cleanAttrName));
        attribute.put("validValues", new ArrayList<>());
        attribute.put("staticString", "");
        attribute.put("autoGenerated", false);
        attribute.put("enableAttribute", true);

        return attribute;
    }

    private Map<String, Object> createDeamObject(String entityName, List<Map<String, Object>> attributes) {
        List<Map<String, Object>> whereClause = new ArrayList<>();
        boolean uuidExists = false;

        for (Map<String, Object> attr : attributes) {
            String name = (String) attr.get("name");
            if (name == null || name.isEmpty()) continue;

            if (name.equalsIgnoreCase("uuid")) uuidExists = true;

            whereClause.add(createWhereObject(name, name.equalsIgnoreCase("uuid") ? "is not" : "="));
        }

        if (!uuidExists) {
            whereClause.add(createWhereObject("uuid", "is not"));
        }

        Map<String, Object> filter = new LinkedHashMap<>();
        filter.put("join", new HashMap<>());
        filter.put("limit", null);
        filter.put("where", whereClause);
        filter.put("offset", null);
        filter.put("orderBy", new HashMap<>());
        filter.put("attributeNames", Collections.singletonList("*"));

        Map<String, Object> deamRead = new LinkedHashMap<>();
        deamRead.put("filter", filter);
        deamRead.put("filterName", "dynamic_filter");
        deamRead.put("filterType", "dynamic");

        Map<String, Object> deam = new LinkedHashMap<>();
        deam.put("entity", entityName);
        deam.put("displayName", formatDisplayName(entityName));
        deam.put("attributesList", attributes);
        deam.put("primaryKeyType", "string");
        deam.put("primaryKey", "uuid");
        deam.put("businessRules", new ArrayList<>());
        deam.put("childEntities", new ArrayList<>());
        deam.put("deamReads", Collections.singletonList(deamRead));

        return deam;
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

    private String normalizeType(String rawType) {
        String lower = rawType.toLowerCase().trim();
        if (DATETIME_ALIASES.contains(lower)) return "datetime";
        if (lower.contains("int")) return "integer";
        if (Arrays.asList("float", "double", "decimal", "number", "numeric").contains(lower)) return "numeric";
        if (Arrays.asList("bool", "boolean").contains(lower)) return "boolean";
        if (Arrays.asList("json", "jsonb").contains(lower)) return "json";
        if (Arrays.asList("array", "list").contains(lower)) return "array";
        if (Arrays.asList("long", "bigint").contains(lower)) return "long";
        return "string";
    }

    private Integer extractMaxLength(String text) {
        Matcher m = Pattern.compile("\\((\\d+)\\)").matcher(text);
        return m.find() ? Integer.parseInt(m.group(1)) : null;
    }

    private String inferTypeFromAttributeName(String attrName, String currentType) {
        if (currentType != null && !currentType.isEmpty() && !currentType.equals("string")) return currentType;
        String lower = attrName.toLowerCase();
        if (lower.contains("price")) return "numeric";
        if (lower.contains("date") || lower.contains("time")) return "datetime";
        return "string";
    }

    private Integer inferMaxLength(String attrName, Integer currentMax) {
        String lower = attrName.toLowerCase();
        if (lower.contains("flag")) return 10;
        if (lower.contains("approval")) return 20;
        if (lower.contains("action")) return 50;
        if (lower.contains("group") || lower.contains("category")) return 200;
        return currentMax;
    }

    private Integer[] inferPrecisionScale(String attrName, String type) {
        if (attrName.toLowerCase().contains("price") && "numeric".equals(type)) return new Integer[]{10, 2};
        return new Integer[]{null, null};
    }

    private boolean inferMandatory(String attrName, boolean currentMandatory) {
        String lower = attrName.toLowerCase();
        if (lower.contains("approval") || lower.contains("publish") || lower.contains("future")) return false;
        return currentMandatory;
    }

    private String formatDisplayName(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        String[] words = raw.replace("_", " ").split(" ");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (!w.isEmpty()) {
                sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1).toLowerCase()).append(" ");
            }
        }
        return sb.toString().trim();
    }

    private Map<String, Object> createWhereObject(String attrName, String operator) {
        Map<String, Object> obj = new LinkedHashMap<>();
        obj.put("operator", operator);
        obj.put("attributeName", attrName);
        obj.put("attributeValue", new ArrayList<>());
        obj.put("logicalOperator", "AND");
        return obj;
    }
}