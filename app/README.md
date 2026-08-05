<!--
Copyright (c) 2026 Apryse Group NV
SPDX-License-Identifier: MIT
See LICENSE in the repository root.
-->

# markdown2pdf-gh-plugin-app

Source of `bin/itext-markdown2pdf`: a CLI that converts markdown to PDF 2.0 with the
[`markdown2pdf`](https://github.com/itext/itext-markdown-converter) library.

```
itext-markdown2pdf --input <file-or-folder> --output <file-or-folder> [--repository-name <owner/repo>] [--branch-name <branch>] [--verbose]
```

* `--input` a `.md` file, or a folder: every `.md` file in it (recursively) is converted.
* `--output` the target `.pdf` file, or the target folder. Inside a folder the `.md` extension is replaced by `.pdf` and
  the folder structure of the input is kept.
* `--repository-name` (optional) name of the repository the markdown belongs to, for example `owner/repo`.
* `--branch-name` (optional) name of the branch the markdown belongs to.
* `--verbose` (or `-v`) prints debug logging. It is also enabled by `RUNNER_DEBUG=1` (set by GitHub when you re-run a
  job with debug logging), `ACTIONS_STEP_DEBUG=true` or `MARKDOWN2PDF_DEBUG=true`.

## Licensing

The original CLI wrapper sources in this directory are [MIT-licensed](../LICENSE.txt).
That does not license the separate `markdown2pdf`, iText Core or pdfHTML libraries
under MIT. The distributed native executable, including its proprietary iText
components, is governed by [the binary license](../bin/LICENSE.txt). Third-party
code and fonts retain the licenses listed in [NOTICE](../NOTICE.txt) and
[the upstream notices](../bin/THIRD-PARTY-NOTICES.txt).

## Building

The `markdown2pdf` library is not published yet, so install it into your local Maven repository first (`mvn install` in
its own repository).

Then, with GraalVM (tested with GraalVM for JDK 25):

```sh
sdk use java 25.0.4-graal          # maven must run on the GraalVM JDK (the plugin needs Java 17+)
sudo apt-get install zlib1g-dev    # native-image links against zlib, only libz.so.1 is not enough
mvn -Pnative clean package        # add ,compress to also run UPX (needs upx on the PATH)
```

This produces only the shippable `target/itext-markdown2pdf` executable.
To also build the unrestricted testing-only executable, explicitly enable `localTesting`:

```sh
mvn -Pnative,localTesting clean package
```

The executables are:

| executable                                    | entry point                    | precondition                                                      |
|-----------------------------------------------|--------------------------------|-------------------------------------------------------------------|
| `itext-markdown2pdf`                          | `com.itextpdf.Main`            | only runs on a GitHub Actions runner of an open source repository |
| `itext-markdown2pdf-TESTING-ONLY-DO-NOT-SHIP` | `com.itextpdf.TestingOnlyMain` | none, always runs — never ship this one                           |


Never upload a bare executable or the testing-only executable to Artifactory.

## Native image notes

* `-H:+AddAllCharsets` is required: iText parses font tables with `Cp1252`, which is not in a native image by default
  (without it every conversion fails with "Type of font is not recognized"). It costs about 5.6 MB, there is no option
  to add a single charset.
* iText ships its own native-image metadata (io, kernel, layout, forms, svg, html2pdf), so
  `reachability-metadata.json` only needs to declare our own embedded Noto fonts and `simplelogger.properties`.

### UPX

`mvn -Pnative,compress clean package` shrinks the executable from ~50 MB to ~17 MB. It needs `upx` installed on
whatever machine runs the build (`sudo apt-get install upx-ucl`), so install it on the CI runner too if the
build is ever automated. The compressed executable itself has no runtime dependency on upx.
Add `,localTesting` to also build and compress the testing-only executable.

### Image size

| step                                                                 | size     |
|----------------------------------------------------------------------|----------|
| starting point                                                       | 101.1 MB |
| resource globs trimmed to our fonts                                  | 100.0 MB |
| no BouncyCastle reflection entries + `<metadataRepository>` disabled | 94.7 MB  |
| `-Os`                                                                | 67.5 MB  |
| `sign`, `barcodes`, `font-asian` and `hyph` excluded                 | 50.7 MB  |
| `-Pnative,compress` (UPX)                                            | 16.9 MB  |

Conversion of three documents takes ~0.10 s uncompressed and ~0.23 s UPX compressed, so the compression cost is one-off
decompression at startup.

