/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.adk.samples.triaging.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Global configuration and environment settings.
 * Replaces the Python settings.py module.
 */
public class Settings {

    private static final Logger logger = LoggerFactory.getLogger(Settings.class);

    public static final String GITHUB_BASE_URL = "https://api.github.com";
    
    // Required Variables
    public static final String GITHUB_TOKEN;
    
    // Optional Variables with Defaults
    public static final String OWNER;
    public static final String REPO;
    public static final String EVENT_NAME;
    public static final Integer ISSUE_NUMBER;
    public static final String ISSUE_TITLE;
    public static final String ISSUE_BODY;
    public static final Integer ISSUE_COUNT_TO_PROCESS;
    public static final boolean IS_INTERACTIVE;

    static {
        // 1. Validate Required Tokens immediately (Fail-fast)
        GITHUB_TOKEN = getEnvOrThrow("GITHUB_TOKEN");

        // 2. Load Optional Variables
        OWNER = getEnvOrDefault("OWNER", "google");
        REPO = getEnvOrDefault("REPO", "adk-java");
        EVENT_NAME = getEnvOrDefault("EVENT_NAME", null);
        ISSUE_TITLE = getEnvOrDefault("ISSUE_TITLE", null);
        ISSUE_BODY = getEnvOrDefault("ISSUE_BODY", null);
        
        // 3. Type-Safe Conversions
        ISSUE_NUMBER = parseIntegerOrNull(getEnvOrDefault("ISSUE_NUMBER", null));
        ISSUE_COUNT_TO_PROCESS = parseIntegerOrNull(getEnvOrDefault("ISSUE_COUNT_TO_PROCESS", null));

        String interactiveRaw = getEnvOrDefault("INTERACTIVE", "1").toLowerCase();
        IS_INTERACTIVE = "true".equals(interactiveRaw) || "1".equals(interactiveRaw);

        logger.info("Application settings loaded successfully. Target Repo: {}/{}", OWNER, REPO);
    }

    // --- Private Helper Methods ---

    private static String getEnvOrThrow(String key) {
        String value = System.getenv(key);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalStateException("Required environment variable not set: " + key);
        }
        return value.trim();
    }

    private static String getEnvOrDefault(String key, String defaultValue) {
        String value = System.getenv(key);
        return (value != null && !value.trim().isEmpty()) ? value.trim() : defaultValue;
    }

    private static Integer parseIntegerOrNull(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            logger.warn("Warning: Failed to parse integer from '{}'. Defaulting to null.", value);
            return null;
        }
    }
    
    // Prevent instantiation of this utility class
    private Settings() {}
}