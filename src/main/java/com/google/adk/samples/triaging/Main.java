package com.google.adk.samples.triaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.adk.agents.RunConfig;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.events.Event;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.google.adk.samples.triaging.agent.TriagingAgentFactory;
import com.google.adk.samples.triaging.utils.GitHubHttpClient;
import com.google.adk.samples.triaging.utils.Settings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class Main {

    private static final Logger logger = LoggerFactory.getLogger(Main.class);
    private static final String APP_NAME = "adk_triage_app";
    private static final String USER_ID = "adk_triage_user";
    
    // Maps directly to the LABEL_TO_OWNER keys from the Python agent
    private static final Set<String> COMPONENT_LABELS = Set.of(
        "agent engine", "auth", "bq", "core", "documentation", "eval", "live",
        "mcp", "models", "services", "tools", "tracing", "web", "workflow"
    );

    public static void main(String[] args) {
        Instant start = Instant.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("UTC"));
        
        System.out.println("Start triaging " + Settings.OWNER + "/" + Settings.REPO + " issues at " + formatter.format(start));
        System.out.println("-".repeat(80));

        try {
            runTriagingWorkflow();
        } catch (Exception e) {
            logger.error("Triaging workflow encountered a fatal error.", e);
        }

        System.out.println("-".repeat(80));
        Instant end = Instant.now();
        System.out.println("Triaging finished at " + formatter.format(end));
        System.out.printf("Total script execution time: %.2f seconds%n", Duration.between(start, end).toMillis() / 1000.0);
    }

    private static void runTriagingWorkflow() throws Exception {
        // 1. Initialize the InMemoryRunner (Specific to google-adk:1.0.0)
        InMemoryRunner runner = new InMemoryRunner(TriagingAgentFactory.create());
        String sessionId = ""+Settings.ISSUE_NUMBER; //UUID.randomUUID().toString();
        String prompt;

        // 2. Determine Event Mode (Single Issue vs Batch)
        if ("issues".equals(Settings.EVENT_NAME) && Settings.ISSUE_NUMBER != null) {
            logger.info("EVENT: Processing specific issue due to '{}' event.", Settings.EVENT_NAME);
            
            Map<String, Object> specificIssue = fetchSpecificIssueDetails(Settings.ISSUE_NUMBER);
            if (specificIssue == null) {
                logger.info("No issue details found for #{} that needs triaging, or an error occurred. Skipping agent interaction.", Settings.ISSUE_NUMBER);
                return;
            }

            String issueTitle = Settings.ISSUE_TITLE != null ? Settings.ISSUE_TITLE : (String) specificIssue.get("title");
            String issueBody = Settings.ISSUE_BODY != null ? Settings.ISSUE_BODY : (String) specificIssue.get("body");
            
            boolean needsComponentLabel = (Boolean) specificIssue.getOrDefault("needs_component_label", true);
            boolean needsOwner = (Boolean) specificIssue.getOrDefault("needs_owner", false);
            String existingComponentLabel = (String) specificIssue.get("existing_component_label");

            prompt = String.format("""
                Triage GitHub issue #%d.

                Title: "%s"
                Body: "%s"

                Issue state: needs_component_label=%b, needs_owner=%b, existing_component_label=%s
                """, 
                Settings.ISSUE_NUMBER, issueTitle, issueBody, 
                needsComponentLabel, needsOwner, existingComponentLabel
            );

        } else {
            logger.info("EVENT: Processing batch of issues (event: {}).", Settings.EVENT_NAME);
            int issueCount = Settings.ISSUE_COUNT_TO_PROCESS != null ? Settings.ISSUE_COUNT_TO_PROCESS : 3;
            
            prompt = String.format(
                "Please use 'list_untriaged_issues' to find %d issues that need triaging, then triage each one according to your instructions.", 
                issueCount
            );
        }

        // 3. Convert Prompt to GenAI Content Object
        Content userMessage = Content.builder()
            .role("user")
            .parts(List.of(Part.builder().text(prompt).build()))
            .build();

        logger.info("Sending prompt to ADK Agent...");
        StringBuilder finalResponseText = new StringBuilder();
        RunConfig config = RunConfig.builder()
                .autoCreateSession(true)
                .build();

        // 4. Execute Async Event Loop (Blocking for CLI execution)
        runner.runAsync(USER_ID, sessionId, userMessage, config)
            .blockingForEach(event -> {
                // Ensure content and parts exist before extracting text
                if (event.content() != null && event.content().get().parts() != null && !event.content().get().parts().isEmpty()) {
                    String text = event.content().get().parts().get().get(0).text().get();
                    if (text != null && !text.isBlank()) {
                        System.out.println("** " + event.author() + " (ADK): " + text);
                        
                        // Accumulate final output from the root agent only
                        if ("adk_triaging_assistant".equals(event.author())) {
                            finalResponseText.append(text);
                        }
                    }
                }
            });
        
        System.out.println("\n<<<< Agent Final Output: \n" + finalResponseText.toString() + "\n");
    }

    /**
     * Fetches details for a single issue to determine if it needs triaging.
     */
    private static Map<String, Object> fetchSpecificIssueDetails(int issueNumber) {
        String url = String.format("%s/repos/%s/%s/issues/%d", Settings.GITHUB_BASE_URL, Settings.OWNER, Settings.REPO, issueNumber);
        logger.info("Fetching details for specific issue: {}", url);

        try {
            JsonNode issueData = GitHubHttpClient.getRequest(url, null);
            
            Set<String> labelNames = new HashSet<>();
            for (JsonNode label : issueData.path("labels")) {
                labelNames.add(label.path("name").asText());
            }

            boolean hasPlanned = labelNames.contains("planned");
            boolean hasAssignee = !issueData.path("assignees").isEmpty();

            Set<String> existingComponentLabels = new HashSet<>(labelNames);
            existingComponentLabels.retainAll(COMPONENT_LABELS);
            
            boolean hasComponent = !existingComponentLabels.isEmpty();
            boolean needsComponentLabel = !hasComponent;
            boolean needsOwner = hasPlanned && !hasAssignee;

            if (needsComponentLabel || needsOwner) {
                logger.info("Issue #{} needs triaging. needs_component_label={}, needs_owner={}", 
                    issueNumber, needsComponentLabel, needsOwner);
                
                Map<String, Object> details = new HashMap<>();
                details.put("number", issueData.path("number").asInt());
                details.put("title", issueData.path("title").asText());
                details.put("body", issueData.path("body").asText());
                details.put("has_planned_label", hasPlanned);
                details.put("has_component_label", hasComponent);
                details.put("existing_component_label", hasComponent ? existingComponentLabels.iterator().next() : null);
                details.put("needs_component_label", needsComponentLabel);
                details.put("needs_owner", needsOwner);
                return details;
            } else {
                logger.info("Issue #{} is already fully triaged. Skipping.", issueNumber);
                return null;
            }
        } catch (Exception e) {
            logger.error("Error fetching issue #{}: {}", issueNumber, e.getMessage());
            return null;
        }
    }
}