/*
 * Copyright (c) 2026 Apryse Group NV
 * SPDX-License-Identifier: MIT
 * See LICENSE in the repository root.
 */
package com.itextpdf.markdown2pdf.ghpluginapp;

import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfUAConformance;
import com.itextpdf.kernel.pdf.PdfVersion;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.kernel.pdf.WriterProperties;
import com.itextpdf.markdown2pdf.MarkdownConverterProperties;
import com.itextpdf.markdown2pdf.MarkdownToPdfConverter;
import com.itextpdf.markdown2pdf.utils.FallbackResourceRetriever;
import com.itextpdf.pdfua.PdfUAConfig;
import com.itextpdf.pdfua.PdfUADocument;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Converts markdown to PDF/UA-2 documents, falling back to standard PDF 2.0 when necessary.
 */
public final class MarkdownConversion {

    private static final Logger LOGGER = LoggerFactory.getLogger(MarkdownConversion.class);
    private static final String MARKDOWN_EXTENSION = ".md";
    private static final String PDF_EXTENSION = ".pdf";
    private static final String LANGUAGE = "en-US";
    private static final String USER_AGENT = "itext-markdown2pdf";

    private MarkdownConversion() {
    }

    /**
     * Converts a single markdown file, or every markdown file of a folder.
     *
     * @param input  markdown file or folder containing markdown files
     * @param output target pdf file when the input is a file, target folder when the input is a folder
     *
     * @return the list of generated pdf files
     */
    public static List<Path> convert(Path input, Path output) throws IOException {
        return convert(input, output, null, null);
    }

    /**
     * Converts a single markdown file, or every markdown file of a folder.
     *
     * @param input          markdown file or folder containing markdown files
     * @param output         target pdf file when the input is a file, target folder when the input is a folder
     * @param repositoryName name of the repository the markdown belongs to, may be {@code null}
     * @param branchName     name of the branch the markdown belongs to, may be {@code null}
     *
     * @return the list of generated pdf files
     */
    public static List<Path> convert(Path input, Path output, String repositoryName, String branchName)
            throws IOException {
        List<Path> generated = new ArrayList<>();
        if (Files.isDirectory(input)) {
            LOGGER.info("Scanning folder for markdown files: {}", input);
            List<Path> markdownFiles = findMarkdownFiles(input);
            if (markdownFiles.isEmpty()) {
                LOGGER.warn("No markdown (*{}) files found in {}, nothing to convert.", MARKDOWN_EXTENSION, input);
                return generated;
            }

            LOGGER.info("Found {} markdown file{} to convert.", markdownFiles.size(),
                    markdownFiles.size() == 1 ? "" : "s");
            int index = 0;
            for (Path markdownFile : markdownFiles) {
                index++;
                Path relative = input.relativize(markdownFile);
                Path target = output.resolve(toPdfPath(relative.toString()));
                LOGGER.info("[{}/{}] {}", index, markdownFiles.size(), relative);
                generated.add(convertFile(markdownFile, target, branchName, repositoryName));
            }
        } else {
            LOGGER.info("Converting a single markdown file.");
            generated.add(convertFile(input, output, branchName, repositoryName));
        }
        return generated;
    }

    private static List<Path> findMarkdownFiles(Path folder) throws IOException {
        try (Stream<Path> files = Files.walk(folder)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(MARKDOWN_EXTENSION)).sorted()
                    .collect(Collectors.toList());
        }
    }

    private static String toPdfPath(String markdownPath) {
        return markdownPath.substring(0, markdownPath.length() - MARKDOWN_EXTENSION.length()) + PDF_EXTENSION;
    }

    private static Path convertFile(Path markdownFile, Path pdfFile, String branchName, String repoName)
            throws IOException {
        if (!Files.isRegularFile(markdownFile)) {
            throw new IOException("Input file does not exist: " + markdownFile);
        }
        Path parent = pdfFile.toAbsolutePath().getParent();
        if (parent != null && !Files.isDirectory(parent)) {
            LOGGER.debug("Creating output folder {}", parent);
            Files.createDirectories(parent);
        }

        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Reading {} ({})", markdownFile, humanReadableSize(sizeOf(markdownFile)));
        }

        long startedAt = System.nanoTime();
        String format = "PDF/UA-2";
        try {
            convertFileToPdfUa2(markdownFile, pdfFile, repoName);
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("PDF/UA-2 conversion failed for {}: {}. Falling back to PDF 2.0.", markdownFile,
                    exception.getMessage());
            LOGGER.debug("PDF/UA-2 conversion failure", exception);
            format = "PDF 2.0";
            try {
                convertFileToPdf20(markdownFile, pdfFile, repoName);
            } catch (IOException | RuntimeException fallbackException) {
                fallbackException.addSuppressed(exception);
                LOGGER.error("PDF 2.0 fallback conversion failed for {}", markdownFile, fallbackException);
                throw fallbackException;
            }
        }
        long durationMs = (System.nanoTime() - startedAt) / 1_000_000L;

        LOGGER.info("        -> {} ({}, {}, {} ms)", pdfFile, format, humanReadableSize(sizeOf(pdfFile)), durationMs);
        return pdfFile;
    }

    private static void convertFileToPdfUa2(Path markdownFile, Path pdfFile, String repoName) throws IOException {
        LOGGER.info("Writing PDF/UA-2 document to {}", pdfFile);
        WriterProperties properties = new WriterProperties().setPdfVersion(PdfVersion.PDF_2_0)
                .setFullCompressionMode(true);
        try (InputStream markdownInput = Files.newInputStream(markdownFile); PdfWriter writer = new PdfWriter(
                pdfFile.toAbsolutePath().toString(), properties); PdfUADocument pdfDocument = new PdfUADocument(writer,
                new PdfUAConfig(PdfUAConformance.PDF_UA_2, titleOf(markdownFile), LANGUAGE))) {
            MarkdownToPdfConverter.convert(markdownInput, pdfDocument, converterProperties(markdownFile, repoName));
        }
    }

    private static void convertFileToPdf20(Path markdownFile, Path pdfFile, String repoName) throws IOException {
        LOGGER.info("Writing PDF 2.0 document to {}", pdfFile);
        WriterProperties properties = new WriterProperties().setPdfVersion(PdfVersion.PDF_2_0)
                .setFullCompressionMode(true);
        try (InputStream markdownInput = Files.newInputStream(markdownFile); PdfWriter writer = new PdfWriter(
                pdfFile.toAbsolutePath().toString(), properties); PdfDocument pdfDocument = new PdfDocument(writer)) {
            pdfDocument.setTagged();
            MarkdownToPdfConverter.convert(markdownInput, pdfDocument, converterProperties(markdownFile, repoName));
        }
    }

    private static MarkdownConverterProperties converterProperties(Path markdownFile, String repoName) {
        MarkdownConverterProperties properties = new MarkdownConverterProperties();
        String userAgent = repoName == null || repoName.isEmpty() ? USER_AGENT : USER_AGENT + " " + repoName;
        properties.ServiceProvider.setIResourceRetriever(
                new FallbackResourceRetriever(new GHPluginAppResourceRetriever(userAgent)));
        // Relative resources are resolved against the folder of the markdown file itself.
        properties.setBaseUri(markdownFile.toAbsolutePath().getParent().toString());
        return properties;
    }

    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException exception) {
            return -1L;
        }
    }

    /**
     * Formats a byte count for humans, for example {@code 12.3 kB}.
     *
     * @param bytes number of bytes, negative when unknown
     *
     * @return the formatted size
     */
    public static String humanReadableSize(long bytes) {
        if (bytes < 0L) {
            return "unknown size";
        }
        if (bytes < 1024L) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024L) {
            return String.format("%.1f kB", Double.valueOf(bytes / 1024.0));
        }
        return String.format("%.1f MB", Double.valueOf(bytes / (1024.0 * 1024.0)));
    }

    private static String titleOf(Path markdownFile) {
        String name = markdownFile.getFileName().toString();
        return name.endsWith(MARKDOWN_EXTENSION) ? name.substring(0, name.length() - MARKDOWN_EXTENSION.length())
                : name;
    }
}

