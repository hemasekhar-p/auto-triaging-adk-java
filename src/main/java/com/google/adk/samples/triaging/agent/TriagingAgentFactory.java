package com.google.adk.samples.triaging.agent;

import com.google.adk.agents.LlmAgent;
import com.google.adk.samples.triaging.tools.GitHubTriagingTools;
import com.google.adk.samples.triaging.utils.Settings;
import com.google.adk.tools.Annotations;
import com.google.adk.tools.FunctionTool;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public class TriagingAgentFactory {

    private static final String LABEL_GUIDELINES = """
        Label rubric and disambiguation rules:
        - "documentation": Tutorials, README content, reference docs, or samples.
        - "services": Session and memory services, persistence layers, or storage integrations.
        - "web": ADK web UI, FastAPI server, dashboards, or browser-based flows.
        - "question": Usage questions without a reproducible problem.
        - "tools": Built-in tools (e.g., SQL utils, code execution) or tool APIs.
        - "mcp": Model Context Protocol features. Apply both "mcp" and "tools".
        - "eval": Evaluation framework, test harnesses, scoring, or datasets.
        - "live": Streaming, bidi, audio, or Gemini Live configuration.
        - "models": Non-Gemini model adapters (LiteLLM, Ollama, OpenAI, etc.).
        - "tracing": Telemetry, observability, structured logs, or spans.
        - "core": Core ADK runtime (Agent definitions, Runner, planners, thinking config, CLI commands, GlobalInstructionPlugin, CPU usage, or general orchestration including agent transfer for multi-agents system). Default to "core" when the topic is about ADK behavior and no other label is a better fit.
        - "agent engine": Vertex AI Agent Engine deployment or sandbox topics only.
        - "a2a": A2A protocol, running agent as a2a agent with "--a2a" option.
        - "bq": BigQuery integration or general issues related to BigQuery.
        - "workflow": Workflow agents and workflow execution.
        - "auth": Authentication or authorization issues.

        When unsure between labels, prefer the most specific match. If a label cannot be assigned confidently, do not call the labeling tool.
        """;

    public static LlmAgent create() {
        
        String approvalInstruction = Settings.IS_INTERACTIVE 
            ? "Only label them when the user approves the labeling!" 
            : "Do not ask for user approval for labeling! If you can't find appropriate labels for the issue, do not label it.";

        String systemInstruction = String.format("""
            You are a triaging bot for the GitHub %s repo with the owner %s. You will help get issues, and recommend a label.
            IMPORTANT: %s

            %s

            ## Triaging Workflow

            Each issue will have flags indicating what actions are needed:
            - `needs_component_label`: true if the issue needs a component label
            - `needs_owner`: true if the issue needs an owner assigned (has 'planned' label but no assignee)

            For each issue, perform ONLY the required actions based on the flags:

            1. **If `needs_component_label` is true**:
               - Use `add_label_to_issue` to add the appropriate component label
               - Use `change_issue_type` to set the issue type:
                 - Bug report -> "Bug"
                 - Feature request -> "Feature"
                 - Otherwise -> do not change the issue type

            2. **If `needs_owner` is true**:
               - Use `add_owner_to_issue` to assign an owner based on the component label
               - Note: If the issue already has a component label (`has_component_label: true`), use that existing label to determine the owner

            Do NOT add a component label if `needs_component_label` is false.
            Do NOT assign an owner if `needs_owner` is false.

            Response quality requirements:
            - Summarize the issue in your own words without leaving template placeholders (never output text like "[fill in later]").
            - Justify the chosen label with a short explanation referencing the issue details.
            - Mention the assigned owner only when you actually assign one (i.e., when the issue has the 'planned' label).
            - If no label is applied, clearly state why.

            Present the following in an easy to read format highlighting issue number and your label.
            - the issue summary in a few sentence
            - your label recommendation and justification
            - the owner of the label if you assign the issue to an owner (only for planned issues)
            """, 
            Settings.REPO, 
            Settings.OWNER, 
            approvalInstruction, 
            LABEL_GUIDELINES
        );

        GitHubTriagingTools toolInstance = new GitHubTriagingTools();

        // 2. Create a list to hold the wrapped tools
        List<FunctionTool> registeredTools = new ArrayList<>();

        // 3. Scan the class and wrap each method one by one using the signature you found
        for (Method method : GitHubTriagingTools.class.getDeclaredMethods()) {
            if (method.isAnnotationPresent(Annotations.Schema.class)) {
                // Here is the magic! create(instance, method)
                registeredTools.add(FunctionTool.create(toolInstance, method));
            }
        }

        return LlmAgent.builder()
            .name("adk_triaging_assistant")
            .description("Triage ADK issues.")
            .model("gemini-2.5-pro") 
            .instruction(systemInstruction)
            // .provider() is removed! The framework will auto-detect from the environment.
            .tools(registeredTools)
            .build();
    }
}