/*
 * Copyright (c) 2026 Apryse Group NV
 * SPDX-License-Identifier: MIT
 * See LICENSE in the repository root.
 */
package com.itextpdf.markdown2pdf.ghpluginapp;

/**
 * Precondition which always allows the conversion to run. Only meant for tests and for the
 * testing only executable, never use this in a shippable artifact.
 * Included in application sources only by the {@code localTesting} Maven profile.
 */
public class RunTestsPrecondition implements IPreconditionToRun {

    @Override
    public boolean allowedToRun() {
        return true;
    }

    @Override
    public String getMessageWhenNotAllowed() {
        return "";
    }
}


