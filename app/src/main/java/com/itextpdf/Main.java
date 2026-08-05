/*
 * Copyright (c) 2026 Apryse Group NV
 * SPDX-License-Identifier: MIT
 * See LICENSE in the repository root.
 */
package com.itextpdf;

import com.itextpdf.markdown2pdf.ghpluginapp.IPreconditionToRun;
import com.itextpdf.markdown2pdf.ghpluginapp.MarkdownConversion;
import com.itextpdf.markdown2pdf.ghpluginapp.OnlyRunWhenInGhWorkerInOpenSourceRepoEnv;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class Main {

    private static final String TOOL_NAME = "itext-markdown2pdf";

    public static void main(String[] args) {
        int exitCode = run(args, new OnlyRunWhenInGhWorkerInOpenSourceRepoEnv());
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int run(String[] args, IPreconditionToRun preconditionToRun) {
        CliOptions options;
        try {
            options = parseArgs(args);
        } catch (IllegalArgumentException exception) {
            System.err.println(exception.getMessage());
            System.err.println();
            printUsage(System.err);
            return 1;
        }

        configureLogging(options.verbose());
        Logger logger = LoggerFactory.getLogger(Main.class);

        logger.info("{} - markdown to PDF conversion", TOOL_NAME);
        logger.info("Input: {}", options.input());
        logger.info("Output: {}", options.output());
        if (options.repositoryName() != null) {
            logger.info("Repository: {}", options.repositoryName());
        }
        if (options.branchName() != null) {
            logger.info("Branch: {}", options.branchName());
        }
        logger.debug("Input is a {}", Files.isDirectory(options.input()) ? "folder" : "file");
        logger.debug("Running on {} {}, java {}", System.getProperty("os.name"), System.getProperty("os.arch"),
                System.getProperty("java.version"));

        logger.debug("Checking whether this environment is allowed to run the conversion.");
        if (!preconditionToRun.allowedToRun()) {
            logger.error("Can not run conversion: {}", preconditionToRun.getMessageWhenNotAllowed());
            return 2;
        }
        logger.debug("Precondition check passed.");

        long startedAt = System.nanoTime();
        try {
            List<Path> generated = MarkdownConversion.convert(options.input(), options.output(),
                    options.repositoryName(), options.branchName());
            long durationMs = (System.nanoTime() - startedAt) / 1_000_000L;
            if (generated.isEmpty()) {
                logger.warn("Finished in {} ms, but no PDF was generated.", durationMs);
            } else {
                logger.info("Done: generated {} PDF file{} in {} ms.", generated.size(),
                        generated.size() == 1 ? "" : "s", durationMs);
            }
        } catch (IOException | RuntimeException exception) {
            if (logger.isDebugEnabled()) {
                logger.error("Conversion failed: {}", exception.getMessage(), exception);
            } else {
                logger.error("Conversion failed: {} ({}); run with --verbose for the full stack trace.",
                        exception.getMessage(), exception.getClass().getName());
            }
            return 3;
        }
        return 0;
    }

    private static void configureLogging(boolean verbose) {
        // SimpleLogger reads simplelogger.properties on the first LoggerFactory call.
        // Apply the verbose override before initialization; all defaults live in the config file.
        boolean debug = verbose
                || environmentFlag("RUNNER_DEBUG", "1")
                || environmentFlag("ACTIONS_STEP_DEBUG", "true")
                || environmentFlag("MARKDOWN2PDF_DEBUG", "true");
        if (debug) {
            System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "debug");
        }
    }

    private static boolean environmentFlag(String name, String expected) {
        String value = System.getenv(name);
        return value != null && expected.equalsIgnoreCase(value.trim());
    }

    private static CliOptions parseArgs(String[] args) {
        Path input = null;
        Path output = null;
        String repositoryName = null;
        String branchName = null;
        boolean verbose = false;

        for (int i = 0; i < args.length; i++) {
            String argument = args[i];

            if ("--input".equals(argument)) {
                ensureNotAlreadySet(input, "--input");
                input = Paths.get(requireValue(args, ++i, "--input"));
            } else if ("--output".equals(argument) || "--ouput".equals(argument)) {
                ensureNotAlreadySet(output, "--output");
                output = Paths.get(requireValue(args, ++i, argument));
            } else if ("--repository-name".equals(argument) || "--repositoryName".equals(argument)) {
                ensureNotAlreadySet(repositoryName, "--repository-name");
                repositoryName = requireValue(args, ++i, argument);
            } else if ("--branch-name".equals(argument) || "--branchName".equals(argument)) {
                ensureNotAlreadySet(branchName, "--branch-name");
                branchName = requireValue(args, ++i, argument);
            } else if ("--verbose".equals(argument) || "-v".equals(argument)) {
                verbose = true;
            } else {
                throw new IllegalArgumentException("Unknown argument: " + argument);
            }
        }

        if (input == null) {
            throw new IllegalArgumentException("Missing required argument: --input");
        }
        if (output == null) {
            throw new IllegalArgumentException("Missing required argument: --output");
        }

        return new CliOptions(input, output, repositoryName, branchName, verbose);
    }

    private static void ensureNotAlreadySet(Object value, String optionName) {
        if (value != null) {
            throw new IllegalArgumentException("Duplicate argument: " + optionName);
        }
    }

    private static String requireValue(String[] args, int index, String optionName) {
        if (index >= args.length || args[index].startsWith("--") || "-v".equals(args[index])) {
            throw new IllegalArgumentException("Missing value for " + optionName);
        }
        return args[index];
    }

    private static void printUsage(PrintStream err) {
        err.println("Usage: java com.itextpdf.Main --input <file-or-folder> --output <file-or-folder> "
                + "[--repository-name <owner/repo>] [--branch-name <branch>] [--verbose]");
        err.println();
        err.println("  --input            markdown file, or folder containing markdown files");
        err.println("  --output           target pdf file, or target folder when the input is a folder");
        err.println("  --repository-name  name of the repository the markdown belongs to (for example owner/repo)");
        err.println("  --branch-name      name of the branch the markdown belongs to");
        err.println("  --verbose          print debug logging (also enabled by RUNNER_DEBUG=1)");
    }

    private static final class CliOptions {
        private final Path input;
        private final Path output;
        private final String repositoryName;
        private final String branchName;
        private final boolean verbose;

        CliOptions(Path input, Path output, String repositoryName, String branchName, boolean verbose) {
            this.input = input;
            this.output = output;
            this.repositoryName = repositoryName;
            this.branchName = branchName;
            this.verbose = verbose;
        }

        Path input() {
            return input;
        }

        Path output() {
            return output;
        }

        String repositoryName() {
            return repositoryName;
        }

        String branchName() {
            return branchName;
        }

        boolean verbose() {
            return verbose;
        }
    }
}
