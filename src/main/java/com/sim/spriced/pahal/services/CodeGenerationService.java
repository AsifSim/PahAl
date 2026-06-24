package com.sim.spriced.pahal.services;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class CodeGenerationService {

    private static final Logger log = LoggerFactory.getLogger(CodeGenerationService.class);
    private final ChatClient chatClient;
    private static final String REPO_URL = "https://github.com/AsifSim/application-interface";

    @FunctionalInterface
    public interface ProgressCallback {
        void onProgress(int percentage, String stageMessage);
    }

    @Autowired
    public CodeGenerationService(ChatClient.Builder chatClientBuilder) {
        log.info("Entering CodeGenerationService constructor");
        this.chatClient = chatClientBuilder.build();
        log.info("Exiting CodeGenerationService constructor");
    }

    public List<String> getRemoteBranches() throws GitAPIException {
        log.info("Entering getRemoteBranches");
        Collection<Ref> refs = Git.lsRemoteRepository()
                .setRemote(REPO_URL)
                .setHeads(true)
                .call();

        List<String> branches = new ArrayList<>();
        for (Ref ref : refs) {
            String branchName = ref.getName().replace("refs/heads/", "");
            branches.add(branchName);
        }
        return branches;
    }

    /**
     * Clones repo, triggers LLM compiling, extracts logic from @Override to last }
     * and structures directories using templates under data-entity-services module.
     * Offers real-time feedback loop parameter to update UI progress components.
     */
    public String generateCodeAndCommit(String baseBranch, String newBranchName, String workflowJson, ProgressCallback progress) throws Exception {
        log.info("Entering generateCodeAndCommit");

        if (progress != null) progress.onProgress(5, "Initializing code compilation environment...");

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String targetFolderName = newBranchName + "_" + timestamp;

        Path workspacePath = Path.of(System.getProperty("user.dir"), "cloned_repositories", targetFolderName);
        File localRepoDir = workspacePath.toFile();

        if (progress != null) progress.onProgress(15, "Cloning repository branch target matching: " + baseBranch);

        try (Git git = Git.cloneRepository()
                .setURI(REPO_URL)
                .setDirectory(localRepoDir)
                .setCloneAllBranches(true)
                .setBranch(baseBranch)
                .call()) {

            if (progress != null) progress.onProgress(30, "Checking out operational workspace branch...");
            git.branchCreate().setName(newBranchName).call();
            git.checkout().setName(newBranchName).call();

            // folder creation logic similar to previous Python system code generator under data-entity-services submodule
            if (progress != null) progress.onProgress(45, "Assembling service package module directory structures...");

            String serviceName = newBranchName; // Mapping serviceName identifier directly from target workflow branch
            Path submodulePath = workspacePath.resolve(Path.of("data-entity-services", serviceName));
            Path packageDirPath = submodulePath.resolve(Path.of("src", "main", "java", "com", "sim", "spriced", "application", "service"));
            Path resourceDirPath = submodulePath.resolve(Path.of("src", "main", "resources"));

            Files.createDirectories(packageDirPath);
            Files.createDirectories(resourceDirPath);

            if (progress != null) progress.onProgress(60, "Invoking AI Engine Model Prompt Spec translation runtime...");
            String systemInstructionPrompt = getSystemPrompt();
            String rawLlmResponse = chatClient.prompt()
                    .system(systemInstructionPrompt)
                    .user("Translate this workflow_json layout spec into clean Java:\n" + workflowJson)
                    .call()
                    .content();

            if (progress != null) progress.onProgress(80, "Extracting isolated executable method blocks...");
            // Regex Extraction logic: Fetch starting from @Override to the very last } bracket
            String processedMethodBody = extractExecutableBlock(rawLlmResponse);

            if (progress != null) progress.onProgress(90, "Applying configuration specifications templates onto workspace...");

            // 1. Populate Service Template File
            String fullClassContent = getServiceTemplate(serviceName, processedMethodBody);
            Files.writeString(packageDirPath.resolve(serviceName + ".java"), fullClassContent);

            // 2. Populate application.properties Template
            String propertiesContent = "spring.application.name=" + serviceName + "\n";
            Files.writeString(resourceDirPath.resolve("application.properties"), propertiesContent);

            // 3. Populate POM Module Template
            String pomContent = getPomTemplate(serviceName);
            Files.writeString(submodulePath.resolve("pom.xml"), pomContent);

            if (progress != null) progress.onProgress(100, "Module generation completely accomplished!");

            log.info("Exiting generateCodeAndCommit");
            return processedMethodBody;
        }
    }

    /**
     * Extracts text starting from @Override down to the final closing bracket }
     */
    private String extractExecutableBlock(String rawContent) {
        if (rawContent == null || !rawContent.contains("@Override")) {
            return rawContent;
        }
        Pattern pattern = Pattern.compile("(@Override.*})", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(rawContent);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return rawContent;
    }

    private String getServiceTemplate(String serviceName, String executeMethod) {
        return "package com.sim.spriced.application.service;\n\n" +
                "import com.google.common.collect.ArrayListMultimap;\n" +
                "import com.sim.spriced.platform.BusinessInterface.ApplicationInterface;\n" +
                "import com.sim.spriced.platform.BusinessInterface.PlatformContext;\n" +
                "import com.sim.spriced.platform.BusinessInterface.TransactionData;\n" +
                "import com.sim.spriced.platform.commons_management_layer.Enums.ValidationStatus;\n" +
                "import org.slf4j.Logger;\n" +
                "import org.slf4j.LoggerFactory;\n" +
                "import org.springframework.stereotype.Service;\n" +
                "import java.util.*;\n" +
                "import java.util.stream.*;\n" +
                "import java.time.LocalDate;\n" +
                "import java.util.List;\n" +
                "import java.util.Map;\n\n" +
                "@Service(\"" + serviceName + "/1.0\")\n" +
                "public class " + serviceName + " implements ApplicationInterface {\n\n" +
                "    private static final Logger logger = LoggerFactory.getLogger(" + serviceName + ".class);\n\n" +
                "    " + executeMethod + "\n\n" +
                "    @Override\n" +
                "    public ValidationStatus validate(TransactionData message) {\n" +
                "        logger.info(\"Entering validate\");\n" +
                "        ValidationStatus status = ValidationStatus.VALIDATION_SUCCESS;\n" +
                "        logger.debug(\"Validation status set to VALIDATION_SUCCESS\");\n" +
                "        logger.info(\"Exiting validate\");\n" +
                "        return status;\n" +
                "    }\n\n" +
                "    @Override\n" +
                "    public TransactionData transform(TransactionData requestData) {\n" +
                "        logger.info(\"Entering transform\");\n" +
                "        logger.info(\"Exiting transform\");\n" +
                "        return requestData;\n" +
                "    }\n" +
                "}";
    }

    private String getPomTemplate(String artifactName) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"\n" +
                "         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n" +
                "         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd\">\n\n" +
                "    <modelVersion>4.0.0</modelVersion>\n\n" +
                "    <parent>\n" +
                "        <groupId>com.sim.spriced.application</groupId>\n" +
                "        <artifactId>data-entity-services</artifactId>\n" +
                "        <version>2.0.101-SNAPSHOT</version>\n" +
                "        <relativePath>../pom.xml</relativePath>\n" +
                "    </parent>\n\n" +
                "    <groupId>com.sim.spriced.application</groupId>\n" +
                "    <artifactId>" + artifactName + "</artifactId>\n" +
                "    <version>3.0.0-SNAPSHOT</version>\n\n" +
                "    <name>" + artifactName + "</name>\n" +
                "    <description>" + artifactName + "</description>\n\n" +
                "    <properties>\n" +
                "        <java.version>21</java.version>\n" +
                "    </properties>\n\n" +
                "    <dependencies>\n" +
                "        <dependency>\n" +
                "            <groupId>org.springframework.boot</groupId>\n" +
                "            <artifactId>spring-boot-starter-test</artifactId>\n" +
                "            <scope>test</scope>\n" +
                "        </dependency>\n" +
                "    </dependencies>\n\n" +
                "    <build>\n" +
                "        <plugins>\n" +
                "            <plugin>\n" +
                "                <groupId>org.springframework.boot</groupId>\n" +
                "                <artifactId>spring-boot-maven-plugin</artifactId>\n" +
                "                <executions>\n" +
                "                    <execution>\n" +
                "                        <id>repackage</id>\n" +
                "                        <phase>none</phase>\n" +
                "                    </execution>\n" +
                "                </executions>\n" +
                "            </plugin>\n" +
                "        </plugins>\n" +
                "    </build>\n" +
                "</project>";
    }

    private String getSystemPrompt() {
        return """
        You are a Java code generator. You generate code using the business logic provided as the workflow_json in the user prompt.
        Use the workflow_json and translate it into java code.
        Dont fetch the value of any key of the provided workflow_json in the generated code.
        The JSON is a specification for code generation only; it WILL NOT be available at runtime.
        Never generate ObjectMapper, Gson, Jackson, JsonNode, or any dynamic JSON parsing logic.
        If any generated code accesses workflow metadata at runtime, the output is invalid.
        You are STRICTLY FORBIDDEN from using Collections.singletonMap, Collections.emptyMap(), or Map.of().

        ## Core Structure & Formatting Rules
        - Generate exactly this starting method structure:
          @Override
          public TransactionData execute(PlatformContext context, TransactionData data) {
              logger.info("The execute method has been invoked");
              // Code...
              return data;
          }
        - **Strict Logging Rule**: Every single operational line of code (variable assignments, DB fetches, microservice calls, framework interactions, conditional checks, java stream contents, clear statements) MUST be immediately followed by a logger statement using the pre-configured `logger` variable (e.g., `logger.info(...)` or `logger.debug(...)`).
        - **Hard 15-Line Splitting Rule**: You MUST strictly count every line inside a single method body (including logger statements, comments, and brackets). If the logic requires more than 15 lines, you MUST break it up by writing sequential helper methods named `execute1(...)`, `execute2(...)`, etc.
        - **Java Formatting**: Keep statements compact and readable on a single line where possible. Generate sequentially in the exact execution order they appear within the JSON array. Upstream dependencies must be completely compiled before downstream variables are resolved.

        ## Platform Context API Interface Definitions
        The execute method receives PlatformContext context and TransactionData data. Use these exact signatures:
        - `List<Map<String,Object>> fetchDataFromDB(String entityName, String filterName, Multimap<String, Object> args);`
        - `TransactionData executeFunction(TransactionData data, FunctionIdentifier identifier);`
        - `void publishEvent(String workflowName);`
        - `void streamDataFromDB(String entityName, String filterName, Multimap<String, Object> args, Consumer<Record> recordsConsumer);`
        - `void streamDataFromDB(String entityName, String filterName, Multimap<String, Object> args, Consumer<Record> recordsConsumer, int batchSize);`
        - `void streamDataFromDB(DynamicJoinModel dynamicJoinModel, Consumer<Record> recordsConsumer, int batchSize);`

        *Note*: `executeFunction` can be used to trigger any microservice by passing the service name and version via `FunctionIdentifier.withFunctionIdentifier(...)`. It returns `TransactionData`, NOT a boolean.

        ## Anti-Invention & Variable Integrity Guardrails
        - **No Invented API Methods**: You are STRICTLY FORBIDDEN from inventing or calling methods like `.isEligible()` or treating `executeFunction` as a boolean checker. 
        - **No Invented Conditions or Variables**: You are STRICTLY FORBIDDEN from inventing variable lookup fields or guessing JSON paths directly within checks (e.g., `record1.get("StepName.attribute_name")`). All logic checks MUST evaluate exact local Java variables explicitly resolved and compiled during upstream extraction steps.
        - **Strict Variable Source Tracking**: Persistence properties and conditional statements must use values directly mapped from actual upstream runtime variables. You are STRICTLY FORBIDDEN from substituting placeholder/invented string literals (e.g., `"MockValue"`) if the workflow target config specifies a variable path source (e.g., `TableName.field_name`).
        - **No Invented Exceptions**: Never insert synthetic guardrail exceptions (like `throw new IllegalStateException("Condition 1 failed");`) unless the workflow JSON explicitly demands an exception at that structural step.

        ## Data Extraction & Parameter Rules
        - **Request Data Extraction**:
          Map<String, Object> requestData = data.getRequestData();
          logger.info("Request data extracted = {}",requestData);
          Object fieldName = requestData.get("fieldName");
          logger.info("fieldName = {}",fieldName);
          The output of requestData.get("fieldName"); will always be of Object data type.
        - **Isolated Parameter Instances**: Every single database interaction (`fetchDataFromDB` or `streamDataFromDB`) MUST instantiate its own completely unique variable instance name (e.g., `params1`, `params2`, `params3`, etc.) compiled exclusively from that explicit step's `where_clause_parameters`. You are STRICTLY FORBIDDEN from clearing or reusing a single parameter map across multiple lookups, or passing parent/unrelated variables into downstream data operations.
        - **Strict Microservice Versioning**: Every microservice invocation via `FunctionIdentifier.withFunctionIdentifier(...)` MUST include an explicit version string (e.g., `"ServiceName/1.0"`). If the version is not explicitly declared in the JSON step, default to append `/1.0`. Never pass a bare name.
        - **Filter Name Selection**: The filterName must be obtained ONLY from the first entry of the provided data read map within the JSON step. Never derive or invent it.

        ## DB Streaming & Advanced Processing
        - **Standard DB Stream**: Use when explicitly stated in the workflow JSON or when data is too large to hold in a list:
          context.streamDataFromDB("entityName", "filterName", params1, record -> { ... });
          logger.info("DB stream processing completed.");
        - **Dynamic Join Stream**: If a dbCall contains a dynamicJoinModel, instantiate and populate attributes using the schema (anchorTable, attributesList, joinObjects, anchorTableFilters):
          DynamicJoinModel djm = new DynamicJoinModel();
          logger.info("DJM initialized.");
          djm.setAnchorTable("table");
          logger.info("Anchor = {}",djm);
          context.streamDataFromDB(djm, record -> { ... }, 100);
          logger.info("Dynamic join stream completed.");

        ## Collection Rules & Anti-0th Element Guardrails
        - **Mandatory Collection Verification**: Never pull `.get(0)` blindly. You MUST write explicit validation size assertions on EVERY query output collection list (including downstream steps like `results3` or `results4`) before extracting values:
          * *"Single/Exactly one record exists"*:
            if (results1 == null || results1.size() != 1) { throw new IllegalStateException("Expected single record"); }
            logger.info("Single record validated.");
            Map<String, Object> record1 = results1.get(0);
            logger.info("Target record extracted = {}",record1);
          * *"One or more records"*: Check `results != null && !results.isEmpty()` without looping.
          * *"Multiple records"*: Check `results != null && results.size() > 1` without looping.
        - **No Traditional Loops**: `for`, `while`, or iterative syntax loops are completely forbidden. Use exclusive Java Streams (`results.stream().forEach(...)`).

        ## Conditionals, Calculations & Mapping Logic
        - **Explicit Step-by-Step Mapping Rule**: The moment you pull a record from a list of results, you MUST immediately pull every required piece of information out of that record and save it into its own individual named variable. You are STRICTLY FORBIDDEN from calling extraction methods on data records inside a conditional statement block. All checks must evaluate local variables only.
          * *Correct*:
            Object config2 = record3.get("config2");
            logger.info("config2 extracted = {}", config2);
        - **Strict Null Evaluation**: If a condition says something "is null", you MUST check your saved local variable using exactly `== null`. You are STRICTLY FORBIDDEN from using key-presence checks, object equality methods against null, or flipping the rule using inversion operators.
          * *Correct translation for 'IS NULL'*: `if (variableName == null) {`
        - **Independent Conditional Blocks**: Every single conditional item from the specification must be evaluated as its own completely independent conditional block. 
        You are STRICTLY FORBIDDEN from linking multiple checks using alternative branch chains (`else if`) or nested branches. Write them as completely standalone, sequentially isolated `if` statement blocks.
        - **Matching Business Rules and Descriptions**: If a routing step describes a rule using everyday descriptive words (such as "If values are adjacent" or "If names match"), you MUST identify the corresponding individual local variables you already saved from previous steps and compare them directly. 
        When checking if two variables match, represent the same value, or are equal, use safe content equivalence methods (such as checking equality via null-safe string or value matching utilities, or evaluating properties safely). Never skip a rule block.
        - **Strict Branch Logging**: Any line of code inside a rule block MUST be immediately followed by its own explicit logging line right before the closing bracket.

        ## Persistence Protocol
        - **Absolute Field Retention Rule**: You MUST include every single target field specified in the data configuration step. You are STRICTLY FORBIDDEN from omitting, dropping, or truncating fields from the final mapping payload. Every field present in the source definition must be explicitly written out.
        - **Persistence Execution Requirement**: Data mapping and storage persistence must happen via the following precise consecutive statements. You are forbidden from replacing this sequence with alternative event publication systems:
          `responseMap.put("data", dataMap);`
          `logger.info("Response map data attached.");`
          `data.setResponse("entityName", responseMap);`
          `logger.info("Response payload set.");`
        - **Payload Structure**: The `responseMap` payload variable MUST strictly contain exactly two root entries: `"operation"` (mapping directly to the specified action string) and `"data"` (mapping directly to a secondary `dataMap`).
        - **Data Mapping Content**: The secondary `dataMap` MUST contain only the key-value pairs defined in the incoming fields specification, mapped directly to their resolved runtime local variables. Never substitute hardcoded string value alternatives or placeholder text. Never generate `responseList.add(dataMap);`. Do not skip or bypass the execution statements.
        """;
    }
}