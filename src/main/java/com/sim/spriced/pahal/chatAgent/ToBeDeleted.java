//import com.google.common.collect.ArrayListMultimap;
//import com.sim.spriced.platform.BusinessInterface.PlatformContext;
//import com.sim.spriced.platform.BusinessInterface.TransactionData;
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//
//import java.util.*;
//import java.util.stream.Collectors;
//
//public class FractalEntityProcessor {
//
//    private static final Logger log = LoggerFactory.getLogger(FractalEntityProcessor.class);
//
//    // ==========================================
//    // 1. UPSERT & FRACTAL BUILDER
//    // ==========================================
//    public TransactionData upsert(PlatformContext context, TransactionData data, String entity, String pk, List<String> fractalCols) {
//        log.info("Entering upsert() - entity={}, pk={}", entity, pk);
//        log.debug("context={}", context);
//        log.debug("data={}", data);
//        log.debug("fractalCols={}", fractalCols);
//        Map<String, Object> req = data.getRequestData();
//        log.debug("Request data={}", req);
//        if (req == null || req.get(pk) == null) {
//            log.warn("Request is null or primary key '{}' not found. Returning original TransactionData.", pk);
//            log.info("Exiting upsert()");
//            return data;
//        }
//        ArrayListMultimap<String, Object> params = ArrayListMultimap.create();
//        log.debug("Created params={}", params);
//        params.put(pk, req.get(pk));
//        log.debug("Added primary key filter. params={}", params);
//        log.debug("Fetching existing record from DB. entity={}, params={}", entity, params);
//        List<Map<String, Object>> dbList = context.fetchDataFromDB(entity, "dynamic_filter", params);
//        log.debug("DB response={}", dbList);
//        Map<String, Object> record = (dbList == null || dbList.isEmpty())? new HashMap<>(req): new HashMap<>(dbList.get(0));
//        log.debug("Initial record={}", record);
//        log.debug("Starting recursive fractal build");
//        buildFractal(record, req, fractalCols, 0, "");
//        log.debug("Fractal build completed. record={}", record);
//        String operation = (dbList == null || dbList.isEmpty()) ? "insert" : "update";
//        log.debug("Determined operation={}", operation);
//        data.setResponse(entity, List.of(Map.of("operation", operation, "data", record)));
//        log.debug("Response set for entity={}", entity);
//        log.info("Exiting upsert()");
//
//        return data;
//    }
//
//    // Recursively builds paths: DSN1 -> DSN1:R1 -> DSN1_R1:C1 -> DSN1_R1_C1:430
//    private void buildFractal(Map<String, Object> record, Map<String, Object> req, List<String> cols, int idx, String prefix) {
//        log.info("Entering buildFractal()");
//        log.debug("record={}", record);
//        log.debug("req={}", req);
//        log.debug("cols={}", cols);
//        log.debug("idx={}", idx);
//        log.debug("prefix={}", prefix);
//        if (idx >= cols.size() || req.get(cols.get(idx)) == null) {
//            log.debug("Recursion termination condition met.");
//            log.info("Exiting buildFractal()");
//            return;
//        }
//        String col = cols.get(idx);
//        log.debug("Current column={}", col);
//        String rawVal = String.valueOf(req.get(col));
//        log.debug("rawVal={}", rawVal);
//        String fractalVal = prefix.isEmpty() ? rawVal : prefix + ":" + rawVal;
//        log.debug("fractalVal={}", fractalVal);
//        Set<String> jvmSet = Arrays.stream(String.valueOf(record.getOrDefault(col, "{}")).replaceAll("[{}\"]", "").split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
//        log.debug("Existing JVM set={}", jvmSet);
//        jvmSet.add(fractalVal);
//        log.debug("Updated JVM set={}", jvmSet);
//        String serializedValue = jvmSet.stream().map(v -> "\"" + v + "\"").collect(Collectors.joining(",", "{", "}"));
//        log.debug("Serialized fractal value={}", serializedValue);
//        record.put(col, serializedValue);
//        log.debug("Updated record={}", record);
//        String nextPrefix = fractalVal.replace(":", "_");
//        log.debug("Next recursion prefix={}", nextPrefix);
//        buildFractal(record, req, cols, idx + 1, nextPrefix);
//        log.info("Exiting buildFractal()");
//    }
//
//    // ==========================================
//    // 2. STREAM FILTER & RECURSIVE KEY POPPER
//    // ==========================================
//    public List<Map<String, Object>> filterAndPopKeys(List<Map<String, Object>> dbRows, String searchCol, String searchVal) {
//        log.info("Entering filterAndPopKeys()");
//        log.debug("dbRows={}", dbRows);
//        log.debug("searchCol={}", searchCol);
//        log.debug("searchVal={}", searchVal);
//        List<Map<String, Object>> result = dbRows.parallelStream()
//                        .filter(row -> {
//                            log.debug("Evaluating row={}", row);
//                            String arrayStr = String.valueOf(row.getOrDefault(searchCol, "{}"));
//                            log.debug("arrayStr={}", arrayStr);
//                            boolean matched = Arrays.stream(arrayStr.replaceAll("[{}\"]", "").split(","))
//                                            .anyMatch(item -> {
//                                                boolean match = item.endsWith(":" + searchVal) || item.equals(searchVal);
//                                                log.debug("Checking item={} match={}", item, match);
//                                                return match;});
//                            log.debug("Row matched={}", matched);
//                            return matched;
//                        }).map(row -> {
//                            log.debug("Processing matched row={}", row);
//                            String arrayStr = String.valueOf(row.getOrDefault(searchCol, "{}"));
//                            log.debug("Matched arrayStr={}", arrayStr);
//                            Arrays.stream(arrayStr.replaceAll("[{}\"]", "").split(","))
//                                    .filter(item -> item.endsWith(":" + searchVal) || item.equals(searchVal))
//                                    .findFirst().ifPresent(match -> {
//                                        log.debug("Matching fractal path={}", match);
//                                        List<String> poppedKeys = new LinkedList<>();
//                                        log.debug("Created poppedKeys list");
//                                        popKeysRecursive(match, poppedKeys);
//                                        log.debug("Popped identifiers={}", poppedKeys);
//                                        row.put("fractal_identifiers", poppedKeys);
//                                        log.debug("Updated row={}", row);});
//                            return row;
//                        }).collect(Collectors.toList());
//        log.debug("Filtered result={}", result);
//        log.info("Exiting filterAndPopKeys()");
//        return result;
//    }
//
//    // Recursively pops keys backwards from a fractal string (e.g., DSN1_R1_C1:450)
//    private void popKeysRecursive(String path, List<String> result) {
//        log.info("Entering popKeysRecursive()");
//        log.debug("path={}", path);
//        log.debug("result={}", result);
//        int delimiterIdx = Math.max(path.lastIndexOf('_'), path.lastIndexOf(':'));
//        log.debug("delimiterIdx={}", delimiterIdx);
//        if (delimiterIdx == -1) {
//            result.add(0, path);
//            log.debug("Base case reached. result={}", result);
//            log.info("Exiting popKeysRecursive()");
//            return;
//        }
//        String tail = path.substring(delimiterIdx + 1);
//        log.debug("tail={}", tail);
//        result.add(0, tail);
//        log.debug("Updated result={}", result);
//        String remainingPath = path.substring(0, delimiterIdx);
//        log.debug("remainingPath={}", remainingPath);
//        popKeysRecursive(remainingPath, result);
//        log.info("Exiting popKeysRecursive()");
//    }
//}