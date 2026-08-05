#!/usr/bin/env bash
# Copyright (c) 2026 Apryse Group NV
# SPDX-License-Identifier: MIT
# See LICENSE in the repository root.
#
# Runs the bundled converter and produces a single PDF or a ZIP of PDFs.
#
# Usage: run-convert.sh <input-path> <output-path> [repository-name] [branch-name]
#
set -euo pipefail

if [[ $# -lt 2 || $# -gt 4 ]]; then
  echo "::error::Usage: run-convert.sh <input-path> <output-path> [repository-name] [branch-name]"
  exit 1
fi

INPUT_PATH="$1"
OUTPUT_PATH="$2"
REPOSITORY_NAME="${3:-}"
BRANCH_NAME="${4:-}"
# BIN_PATH can be overridden (used by the tests); by default the binary shipped
# next to this script is used.
BIN_PATH="${BIN_PATH:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/bin/itext-markdown2pdf}"

if [[ "${RUNNER_OS:-Linux}" != "Linux" ]]; then
  echo "::error::itext-markdown2pdf requires a Linux runner (use 'runs-on: ubuntu-latest')."
  exit 1
fi
if [[ "${RUNNER_ARCH:-$(uname -m)}" != "X64" && "${RUNNER_ARCH:-$(uname -m)}" != "x86_64" ]]; then
  echo "::error::itext-markdown2pdf requires an x86_64 runner (use 'runs-on: ubuntu-latest')."
  exit 1
fi

if [[ ! -f "$BIN_PATH" ]]; then
  echo "::error::Converter not found at '${BIN_PATH}'."
  exit 1
fi
chmod +x "$BIN_PATH" 2>/dev/null || true

if [[ ! -e "$INPUT_PATH" ]]; then
  echo "::error::Input path '$INPUT_PATH' does not exist."
  exit 1
fi

if [[ -d "$INPUT_PATH" ]]; then
  OUTPUT_TYPE=zip
  if ! command -v zip >/dev/null 2>&1; then
    echo "::error::ZIP output requires 'zip' (preinstalled on ubuntu-latest)."
    exit 1
  fi
elif [[ -f "$INPUT_PATH" ]]; then
  OUTPUT_TYPE=pdf
else
  echo "::error::Input must be a regular file or folder."
  exit 1
fi
if [[ "${OUTPUT_PATH,,}" != *."$OUTPUT_TYPE" ]]; then
  echo "::error::Output must be a .$OUTPUT_TYPE file for this input."
  exit 1
fi

# Absolute paths also work when ZIP changes its working directory. Reject line
# breaks so a path cannot inject additional entries into GITHUB_OUTPUT.
OUTPUT_PATH="$(realpath -m -- "$OUTPUT_PATH")"
if [[ "$OUTPUT_PATH" == *$'\n'* || "$OUTPUT_PATH" == *$'\r'* ]]; then
  echo "::error::Output paths must not contain line breaks."
  exit 1
fi
if [[ -d "$OUTPUT_PATH" || "$INPUT_PATH" -ef "$OUTPUT_PATH" ]]; then
  echo "::error::Output must be a file distinct from the input."
  exit 1
fi

# Stage beside the destination: failed conversions leave an existing result
# intact, and ZIPs contain only files from this run (never stale PDFs).
mkdir -p -- "$(dirname "$OUTPUT_PATH")"
STAGING_DIR="$(mktemp -d "$(dirname "$OUTPUT_PATH")/.itext-markdown2pdf.XXXXXX")"
trap 'rm -rf -- "$STAGING_DIR"' EXIT
if [[ "$OUTPUT_TYPE" == zip ]]; then
  CONVERTER_OUTPUT="$STAGING_DIR/pdfs"
  mkdir -p -- "$CONVERTER_OUTPUT"
else
  CONVERTER_OUTPUT="$STAGING_DIR/result.pdf"
fi

echo "Converting '$INPUT_PATH' -> '$OUTPUT_PATH'"
CONVERTER_ARGS=(--input "$INPUT_PATH" --output "$CONVERTER_OUTPUT")
if [[ -n "$REPOSITORY_NAME" ]]; then
  CONVERTER_ARGS+=(--repository-name "$REPOSITORY_NAME")
fi
if [[ -n "$BRANCH_NAME" ]]; then
  CONVERTER_ARGS+=(--branch-name "$BRANCH_NAME")
fi
"$BIN_PATH" "${CONVERTER_ARGS[@]}"

if [[ "$OUTPUT_TYPE" == zip ]]; then
  mapfile -d '' -t PDF_FILES < <(find "$CONVERTER_OUTPUT" -type f -name '*.pdf' -print0)
else
  PDF_FILES=("$CONVERTER_OUTPUT")
fi
if [[ ${#PDF_FILES[@]} -eq 0 ]]; then
  echo "::error::No PDFs were generated. Folder input must contain .md files."
  exit 1
fi
for pdf in "${PDF_FILES[@]}"; do
  if [[ ! -s "$pdf" ]] || [[ "$(head -c 5 -- "$pdf")" != '%PDF-' ]]; then
    echo "::error::Converter did not produce a non-empty PDF: '$pdf'."
    exit 1
  fi
done

if [[ "$OUTPUT_TYPE" == zip ]]; then
  (cd "$CONVERTER_OUTPUT" && zip -q -r "$STAGING_DIR/result.zip" . -i '*.pdf')
fi
mv -fT -- "$STAGING_DIR/result.$OUTPUT_TYPE" "$OUTPUT_PATH"

if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
  {
    printf 'output-path=%s\n' "$OUTPUT_PATH"
    printf 'output-type=%s\n' "$OUTPUT_TYPE"
    printf 'pdf-count=%s\n' "${#PDF_FILES[@]}"
  } >> "$GITHUB_OUTPUT"
fi
echo "Conversion finished: ${#PDF_FILES[@]} PDF(s), output: '$OUTPUT_PATH'."
