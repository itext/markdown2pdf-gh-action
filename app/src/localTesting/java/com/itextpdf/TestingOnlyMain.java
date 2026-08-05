/*
 * Copyright (c) 2026 Apryse Group NV
 * SPDX-License-Identifier: MIT
 * See LICENSE in the repository root.
 */
package com.itextpdf;

import com.itextpdf.markdown2pdf.ghpluginapp.RunTestsPrecondition;

/**
 * Entry point of the "itext-markdown2pdf-TESTING-ONLY-DO-NOT-SHIP" executable.
 *
 * <p>It runs the exact same conversion as {@link Main}, but without any environment precondition,
 * so it can be executed locally or in a test suite. NEVER ship this executable.
 * Included in application sources only by the {@code localTesting} Maven profile.
 */
public class TestingOnlyMain {

	public static void main(String[] args) {
		int exitCode = Main.run(args, new RunTestsPrecondition());
		if (exitCode != 0) {
			System.exit(exitCode);
		}
	}
}


