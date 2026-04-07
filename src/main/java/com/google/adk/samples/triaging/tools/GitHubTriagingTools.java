package com.google.adk.samples.triaging.tools;

import com.fasterxml.jackson.databind.JsonNode;
// Let your IDE resolve the correct import for @Schema here
import com.google.adk.samples.triaging.utils.GitHubHttpClient;
import com.google.adk.samples.triaging.utils.Settings;
import com.google.adk.tools.Annotations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class GitHubTriagingTools {

    private static final Logger logger = LoggerFactory.getLogger(GitHubTriagingTools.class);

    private static final Map<String, String> LABEL_TO_OWNER = Map.ofEntries(
        Map.entry("agent engine", "yeesian"),
        Map.entry("auth", "xuanyang15"),
        Map.entry("bq", "shobsi"),
        Map.entry("core", "Jacksunwei"),
        Map.entry("documentation", "joefernandez"),
        Map.entry("eval", "ankursharmas"),
        Map.entry("live", "wuliang229"),
        Map.entry("mcp", "wukath"),
        Map.entry("models", "xuanyang15"),
        Map.entry("services", "DeanChensj"),
        Map.entry("tools", "xuanyang15"),
        Map.entry("tracing", "jawoszek"),
        Map.entry("web", "wyf7107"),
        Map.entry("workflow", "DeanChensj")
    );

    @Annotations.Schema(description = "List open issues that need triaging. Returns issues without component labels or planned issues without assignees.")
    public Map<String, Object> listUntriagedIssues(
        @Annotations.Schema(description = "number of issues to return") int issueCount
    ) {
        String url = Settings.GITHUB_BASE_URL + "/search/issues";
        String query = String.format("repo:%s/%s is:open is:issue", Settings.OWNER, Settings.REPO);
        
        Map<String, String> params = Map.of(
            "q", query, "sort", "created", "order", "desc", "per_page", "100", "page", "1"
        );

        try {
            JsonNode response = GitHubHttpClient.getRequest(url, params);
            JsonNode items = response.path("items");
            
            List<Map<String, Object>> untriagedIssues = new ArrayList<>();
            Set<String> componentLabels = LABEL_TO_OWNER.keySet();

            for (JsonNode issueNode : items) {
                Set<String> issueLabels = new HashSet<>();
                for (JsonNode labelNode : issueNode.path("labels")) {
                    issueLabels.add(labelNode.path("name").asText());
                }

                boolean hasAssignees = !issueNode.path("assignees").isEmpty();
                Set<String> existingComponentLabels = new HashSet<>(issueLabels);
                existingComponentLabels.retainAll(componentLabels);
                
                boolean hasComponent = !existingComponentLabels.isEmpty();
                boolean hasPlanned = issueLabels.contains("planned");

                boolean needsComponentLabel = !hasComponent;
                boolean needsOwner = hasPlanned && !hasAssignees;

                if (needsComponentLabel || needsOwner) {
                    Map<String, Object> issueMap = new HashMap<>();
                    issueMap.put("number", issueNode.path("number").asInt());
                    issueMap.put("title", issueNode.path("title").asText());
                    issueMap.put("body", issueNode.path("body").asText());
                    issueMap.put("has_planned_label", hasPlanned);
                    issueMap.put("has_component_label", hasComponent);
                    issueMap.put("existing_component_label", hasComponent ? existingComponentLabels.iterator().next() : null);
                    issueMap.put("needs_component_label", needsComponentLabel);
                    issueMap.put("needs_owner", needsOwner);
                    
                    untriagedIssues.add(issueMap);
                    
                    if (untriagedIssues.size() >= issueCount) break;
                }
            }
            return Map.of("status", "success", "issues", untriagedIssues);
        } catch (Exception e) {
            logger.error("Failed to list untriaged issues", e);
            return Map.of("status", "error", "message", e.getMessage());
        }
    }

    @Annotations.Schema(description = "Add the specified component label to the given issue number.")
    public Map<String, Object> addLabelToIssue(
        @Annotations.Schema(description = "issue number of the GitHub issue") int issueNumber,
        @Annotations.Schema(description = "label to assign") String label
    ) {
        logger.info("Attempting to add label '{}' to issue #{}", label, issueNumber);
        
        if (!LABEL_TO_OWNER.containsKey(label)) {
            return Map.of("status", "error", "message", "Error: Label '" + label + "' is not an allowed label. Will not apply.");
        }

        String url = String.format("%s/repos/%s/%s/issues/%d/labels", Settings.GITHUB_BASE_URL, Settings.OWNER, Settings.REPO, issueNumber);
            
        try {
            JsonNode response = GitHubHttpClient.postRequest(url, List.of(label));
            return Map.of("status", "success", "message", response, "applied_label", label);
        } catch (Exception e) {
            return Map.of("status", "error", "message", e.getMessage());
        }
    }

    @Annotations.Schema(description = "Assign an owner to the issue based on the component label. Only for planned issues.")
    public Map<String, Object> addOwnerToIssue(
        @Annotations.Schema(description = "issue number of the GitHub issue") int issueNumber,
        @Annotations.Schema(description = "component label that determines the owner") String label
    ) {
        logger.info("Attempting to assign owner for label '{}' to issue #{}", label, issueNumber);
        
        if (!LABEL_TO_OWNER.containsKey(label)) {
            return Map.of("status", "error", "message", "Error: Label '" + label + "' is not a valid component label.");
        }

        String owner = LABEL_TO_OWNER.get(label);
        if (owner == null) {
            return Map.of("status", "warning", "message", "Label '" + label + "' does not have an owner. Will not assign.");
        }

        String url = String.format("%s/repos/%s/%s/issues/%d/assignees", Settings.GITHUB_BASE_URL, Settings.OWNER, Settings.REPO, issueNumber);
            
        try {
            JsonNode response = GitHubHttpClient.postRequest(url, Map.of("assignees", List.of(owner)));
            return Map.of("status", "success", "message", response, "assigned_owner", owner);
        } catch (Exception e) {
            return Map.of("status", "error", "message", e.getMessage());
        }
    }

    @Annotations.Schema(description = "Change the issue type of the given issue number.")
    public Map<String, Object> changeIssueType(
        @Annotations.Schema(description = "issue number of the GitHub issue") int issueNumber,
        @Annotations.Schema(description = "issue type to assign") String issueType
    ) {
        logger.info("Attempting to change issue type '{}' to issue #{}", issueType, issueNumber);
        String url = String.format("%s/repos/%s/%s/issues/%d", Settings.GITHUB_BASE_URL, Settings.OWNER, Settings.REPO, issueNumber);
            
        try {
            JsonNode response = GitHubHttpClient.patchRequest(url, Map.of("type", issueType));
            return Map.of("status", "success", "message", response, "issue_type", issueType);
        } catch (Exception e) {
            return Map.of("status", "error", "message", e.getMessage());
        }
    }
}