/*
 * Copyright (c) 2026 Apryse Group NV
 * SPDX-License-Identifier: MIT
 * See LICENSE in the repository root.
 */
package com.itextpdf.markdown2pdf.ghpluginapp;

public interface IPreconditionToRun {
    boolean allowedToRun();

    String getMessageWhenNotAllowed();
}
