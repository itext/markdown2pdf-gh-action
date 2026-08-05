/*
 * Copyright (c) 2026 Apryse Group NV
 * SPDX-License-Identifier: MIT
 * See LICENSE in the repository root.
 */
package com.itextpdf.markdown2pdf.ghpluginapp;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class JdkGithubApiClient {
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final HttpClient httpClient;

    public JdkGithubApiClient() {
        this(HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    JdkGithubApiClient(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public String get(String url, String token) throws IOException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(TIMEOUT)
                .header("Accept", "application/json")
                .header("User-Agent", "markdown2pdf-gh-plugin-app")
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Request to " + url + " was interrupted.", exception);
        }

        int statusCode = response.statusCode();
        if (statusCode < 200 || statusCode >= 300) {
            throw new IOException("GitHub API responded with status " + statusCode + " for " + url + ".");
        }

        return response.body();
    }
}

