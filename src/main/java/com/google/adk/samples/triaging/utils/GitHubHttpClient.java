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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Utility client for handling GitHub API interactions.
 * Replaces the Python 'requests' module implementation.
 */
public class GitHubHttpClient {

    private static final Logger logger = LoggerFactory.getLogger(GitHubHttpClient.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    // Initialized eagerly. Equivalent to the Python 'headers' dict at the module level.
    private static final String GITHUB_TOKEN = System.getenv().getOrDefault("GITHUB_TOKEN", "");
    private static final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .build();

    /**
     * Executes a GET request with optional query parameters.
     */
    public static JsonNode getRequest(String url, Map<String, String> params) {
        String finalUrl = url;
        if (params != null && !params.isEmpty()) {
            String queryString = params.entrySet().stream()
                    .map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "=" +
                                  URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
                    .collect(Collectors.joining("&"));
            finalUrl = url + (url.contains("?") ? "&" : "?") + queryString;
        }

        HttpRequest request = buildBaseRequest(finalUrl).GET().build();
        return executeRequest(request);
    }

    /**
     * Executes a POST request with a JSON payload.
     */
    public static JsonNode postRequest(String url, Object payload) {
        HttpRequest request = buildBaseRequest(url)
                .POST(createBodyPublisher(payload))
                .build();
        return executeRequest(request);
    }

    /**
     * Executes a PATCH request with a JSON payload.
     */
    public static JsonNode patchRequest(String url, Object payload) {
        HttpRequest request = buildBaseRequest(url)
                // PATCH is not a top-level method in HttpRequest.Builder, so we use method()
                .method("PATCH", createBodyPublisher(payload)) 
                .build();
        return executeRequest(request);
    }

    /**
     * Generates a standard error response payload.
     * Replaces the Python error_response dict.
     */
    public static Map<String, String> errorResponse(String errorMessage) {
        return Map.of(
            "status", "error",
            "message", errorMessage != null ? errorMessage : "Unknown error"
        );
    }

    /**
     * Parses a number from the given string safely.
     * Replaces Python's try-except block with Java's try-catch.
     */
    public static int parseNumberString(String numberStr, int defaultValue) {
        try {
            if (numberStr == null || numberStr.isBlank()) {
                return defaultValue;
            }
            return Integer.parseInt(numberStr.trim());
        } catch (NumberFormatException e) {
            logger.warn("Warning: Invalid number string: '{}'. Defaulting to {}.", numberStr, defaultValue);
            return defaultValue;
        }
    }

    // --- Private Helper Methods ---

    private static HttpRequest.Builder buildBaseRequest(String url) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(TIMEOUT)
                .header("Authorization", "token " + GITHUB_TOKEN)
                .header("Accept", "application/vnd.github.v3+json")
                .header("Content-Type", "application/json");
    }

    private static HttpRequest.BodyPublisher createBodyPublisher(Object payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            return HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize request payload to JSON", e);
        }
    }

    private static JsonNode executeRequest(HttpRequest request) {
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            
            // Emulates Python's response.raise_for_status()
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new RuntimeException(String.format("HTTP Error %d: %s", response.statusCode(), response.body()));
            }
            
            return objectMapper.readTree(response.body());
        } catch (Exception e) {
            throw new RuntimeException("HTTP Request failed: " + request.uri(), e);
        }
    }
}