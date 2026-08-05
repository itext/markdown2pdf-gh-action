/*
 * Copyright (c) 2026 Apryse Group NV
 * SPDX-License-Identifier: MIT
 * See LICENSE in the repository root.
 */
package com.itextpdf.markdown2pdf.ghpluginapp;

import com.itextpdf.io.resolver.resource.DefaultResourceRetriever;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

public class GHPluginAppResourceRetriever extends DefaultResourceRetriever {

    private final Map<String, byte[]> data = new HashMap<>();


    public GHPluginAppResourceRetriever(String userAgent) {
        Map<String, String> header = new HashMap<>();
        header.put("user-agent", userAgent);
        setRequestHeaders(header);
        setConnectTimeout(1000);
        setReadTimeout(1000);
    }


    @Override
    public byte[] getByteArrayByUrl(URL url) throws IOException {
        return this.getInputStreamByUrl(url).readAllBytes();
    }

    @Override
    public InputStream getInputStreamByUrl(URL url) throws IOException {
        if (data.containsKey(url.toString())) {
            return new ByteArrayInputStream(data.get(url.toString()));
        }
        byte[] arr;
        try (InputStream stream = super.getInputStreamByUrl(url)) {
            arr = stream.readAllBytes();
        }

        data.put(url.toString(), arr);
        return new ByteArrayInputStream(arr);

    }
}
