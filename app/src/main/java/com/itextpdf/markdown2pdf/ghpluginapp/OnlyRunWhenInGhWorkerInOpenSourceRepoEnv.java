/*
 * Copyright (c) 2026 Apryse Group NV
 * SPDX-License-Identifier: MIT
 * See LICENSE in the repository root.
 */
package com.itextpdf.markdown2pdf.ghpluginapp;

import java.io.IOException;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Allows the conversion to run only when executed on a GitHub Actions runner for a public (open source) repository.
 * <p>
 * The repository visibility is resolved through the GitHub REST API using the token the workflow exposes to the
 * runner. That token is scoped to the repository the workflow runs in, so it cannot be used to claim a different
 * repository, which makes this check considerably harder to spoof than reading environment variables only.
 */
public class OnlyRunWhenInGhWorkerInOpenSourceRepoEnv implements IPreconditionToRun {
    private static final String ENV_GITHUB_ACTIONS = "GITHUB_ACTIONS";
    private static final String ENV_GITHUB_REPOSITORY = "GITHUB_REPOSITORY";
    private static final String ENV_GITHUB_API_URL = "GITHUB_API_URL";
    private static final String DEFAULT_API_URL = "https://api.github.com";

    private static final String[] TOKEN_ENV_NAMES = {"GITHUB_TOKEN", "INPUT_GITHUB_TOKEN", "GH_TOKEN"};

    private static final Pattern VISIBILITY_PATTERN = Pattern.compile("\"visibility\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern PRIVATE_PATTERN = Pattern.compile("\"private\"\\s*:\\s*(true|false)");

    private final Map<String, String> environment;
    private final JdkGithubApiClient githubApiClient;

    private String messageWhenNotAllowed = "This tool can only run on a GitHub Actions runner in a public repository.";

    public OnlyRunWhenInGhWorkerInOpenSourceRepoEnv() {
        this(System.getenv());
    }

    public OnlyRunWhenInGhWorkerInOpenSourceRepoEnv(Map<String, String> environment) {
        this.environment = environment;
        this.githubApiClient = new JdkGithubApiClient();
    }

    @Override
    public boolean allowedToRun() {
        if (!"true".equalsIgnoreCase(trimmed(environment.get(ENV_GITHUB_ACTIONS)))) {
            messageWhenNotAllowed = "Not running on a GitHub Actions runner: " + ENV_GITHUB_ACTIONS
                    + " is not set to 'true'.";
            return false;
        }

        String repository = trimmed(environment.get(ENV_GITHUB_REPOSITORY));
        if (repository.isEmpty()) {
            messageWhenNotAllowed = "Not running on a GitHub Actions runner: " + ENV_GITHUB_REPOSITORY
                    + " is not available.";
            return false;
        }

        String token = resolveToken();
        if (token.isEmpty()) {
            messageWhenNotAllowed = "No GitHub Actions token available. Pass it to the step, for example with "
                    + "'env: GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}'.";
            return false;
        }

        return isPublicRepository(repository, token);
    }

    @Override
    public String getMessageWhenNotAllowed() {
        return messageWhenNotAllowed;
    }

    private boolean isPublicRepository(String repository, String token) {
        String url = apiBaseUrl() + "/repos/" + repository;

        String body;
        try {
            body = githubApiClient.get(url, token);
        } catch (IOException exception) {
            messageWhenNotAllowed = "Could not verify the repository visibility through the GitHub API: "
                    + exception.getMessage();
            return false;
        }

        Boolean isPublic = parseIsPublic(body);
        if (isPublic == null) {
            messageWhenNotAllowed = "Could not determine the visibility of '" + repository
                    + "' from the GitHub API response.";
            return false;
        }
        if (!isPublic) {
            messageWhenNotAllowed = "Repository '" + repository
                    + "' is not public, so it does not qualify as an open source repository.";
            return false;
        }

        return true;
    }

    private static Boolean parseIsPublic(String body) {
        if (body == null) {
            return null;
        }

        Matcher visibilityMatcher = VISIBILITY_PATTERN.matcher(body);
        if (visibilityMatcher.find()) {
            return Boolean.valueOf("public".equalsIgnoreCase(visibilityMatcher.group(1)));
        }

        Matcher privateMatcher = PRIVATE_PATTERN.matcher(body);
        if (privateMatcher.find()) {
            return Boolean.valueOf(!Boolean.parseBoolean(privateMatcher.group(1)));
        }

        return null;
    }

    private String apiBaseUrl() {
        String apiUrl = trimmed(environment.get(ENV_GITHUB_API_URL));
        if (apiUrl.isEmpty()) {
            apiUrl = DEFAULT_API_URL;
        }
        while (apiUrl.endsWith("/")) {
            apiUrl = apiUrl.substring(0, apiUrl.length() - 1);
        }
        return apiUrl;
    }

    private String resolveToken() {
        for (String name : TOKEN_ENV_NAMES) {
            String token = trimmed(environment.get(name));
            if (!token.isEmpty()) {
                return token;
            }
        }
        return "";
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }
}
