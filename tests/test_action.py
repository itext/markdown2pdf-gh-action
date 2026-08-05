# Copyright (c) 2026 Apryse Group NV
# SPDX-License-Identifier: MIT
# See LICENSE in the repository root.

"""Offline wrapper tests; set TEST_CONVERTER_BIN for real PDF integration tests."""

import json
import os
from pathlib import Path
import subprocess
import tempfile
import textwrap
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[1]
RUNNER = ROOT / "scripts/run-convert.sh"


class ActionTestCase(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="itext action tests ")
        self.addCleanup(self.temp.cleanup)
        self.work = Path(self.temp.name)
        self.input = self.work / "markdown files"
        self.input.mkdir()
        (self.input / "sample.md").write_text("# Sample document\n\nHello, PDF!\n")
        nested = self.input / "nested folder"
        nested.mkdir()
        (nested / "second file.md").write_text("# Second document\n")
        (self.input / "ignored.txt").write_text("Not Markdown")
        self.outputs = self.work / "github-output"
        self.binary = ROOT / "bin/itext-markdown2pdf"

    def run_action(self, source, destination, *, expected=0, extra_env=None):
        self.outputs.write_text("")
        env = dict(os.environ, BIN_PATH=str(self.binary), RUNNER_OS="Linux",
                   RUNNER_ARCH="X64", GITHUB_OUTPUT=str(self.outputs))
        env.update(extra_env or {})
        result = subprocess.run(
            ["bash", str(RUNNER), str(source), str(destination),
             "owner/repository", "feature/a branch"],
            cwd=self.work, env=env, text=True, capture_output=True, timeout=120,
        )
        self.assertEqual(expected, result.returncode, result.stdout + result.stderr)
        self.assertFalse(list(self.work.rglob(".itext-markdown2pdf.*")))
        if expected:
            self.assertEqual("", self.outputs.read_text())
        return result

    def assert_outputs(self, path, kind, count):
        self.assertEqual(
            {"output-path": str(path.resolve()), "output-type": kind,
             "pdf-count": str(count)},
            dict(line.split("=", 1) for line in self.outputs.read_text().splitlines()),
        )


class WrapperTests(ActionTestCase):
    def setUp(self):
        super().setUp()
        self.binary = self.work / "fake converter"
        self.binary.write_text(textwrap.dedent("""\
            #!/usr/bin/env python3
            import json
            import os
            from pathlib import Path
            import sys

            args = dict(zip(sys.argv[1::2], sys.argv[2::2]))
            Path(os.environ['ARGUMENT_LOG']).write_text(json.dumps(args))
            source = Path(args['--input'])
            output = Path(args['--output'])
            mode = os.environ.get('CONVERTER_MODE', 'ok')
            if mode == 'empty':
                sys.exit(0)
            inputs = sorted(source.rglob('*.md')) if source.is_dir() else [source]
            for item in inputs:
                target = output / item.relative_to(source).with_suffix('.pdf') if source.is_dir() else output
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(b'%PDF-1.7\\nStub PDF\\n' if mode != 'invalid' else b'not a PDF')
                if mode == 'fail':
                    sys.exit(3)
        """))
        self.binary.chmod(0o755)
        self.argument_log = self.work / "arguments.json"

    def run_action(self, *args, extra_env=None, **kwargs):
        env = {"ARGUMENT_LOG": str(self.argument_log)}
        env.update(extra_env or {})
        return super().run_action(*args, extra_env=env, **kwargs)

    def test_single_pdf_and_argument_forwarding(self):
        target = self.work / "output files/result.pdf"
        self.run_action(self.input / "sample.md", "output files/result.pdf")
        self.assertTrue(target.read_bytes().startswith(b"%PDF-"))
        self.assert_outputs(target, "pdf", 1)
        arguments = json.loads(self.argument_log.read_text())
        self.assertEqual("owner/repository", arguments["--repository-name"])
        self.assertEqual("feature/a branch", arguments["--branch-name"])
        self.assertEqual(str(self.input / "sample.md"), arguments["--input"])

    def test_zip_contains_only_generated_pdfs_preserving_structure(self):
        (self.input / ".hidden").mkdir()
        (self.input / ".hidden/-a 'quoted' name.md").write_text("# Hidden file")
        (self.input / "stale.pdf").write_bytes(b"%PDF-old")
        target = self.work / "output files/documents.zip"
        self.run_action(self.input, target)
        with zipfile.ZipFile(target) as archive:
            self.assertIsNone(archive.testzip())
            self.assertEqual({"sample.pdf", "nested folder/second file.pdf",
                              ".hidden/-a 'quoted' name.pdf"}, set(archive.namelist()))
            for name in archive.namelist():
                self.assertTrue(archive.read(name).startswith(b"%PDF-"))
        self.assert_outputs(target, "zip", 3)

    def test_rerun_replaces_archive_instead_of_retaining_stale_entries(self):
        target = self.input / "documents.zip"
        self.run_action(self.input, target)
        (self.input / "nested folder/second file.md").unlink()
        self.run_action(self.input, target)
        with zipfile.ZipFile(target) as archive:
            self.assertEqual(["sample.pdf"], archive.namelist())
        self.assert_outputs(target, "zip", 1)

    def test_folder_with_one_markdown_still_produces_zip(self):
        (self.input / "nested folder/second file.md").unlink()
        target = self.work / "one.zip"
        self.run_action(self.input, target)
        self.assertTrue(zipfile.is_zipfile(target))
        self.assert_outputs(target, "zip", 1)

    def test_empty_folder_fails(self):
        empty = self.work / "empty"
        empty.mkdir()
        (empty / "ignored.txt").touch()
        target = self.work / "empty.zip"
        result = self.run_action(empty, target, expected=1)
        self.assertIn("No PDFs were generated", result.stdout)
        self.assertFalse(target.exists())

    def test_failed_or_invalid_conversions_preserve_previous_output(self):
        for kind in ("pdf", "zip"):
            for mode, code in (("fail", 3), ("empty", 1), ("invalid", 1)):
                with self.subTest(kind=kind, mode=mode):
                    source = self.input if kind == "zip" else self.input / "sample.md"
                    target = self.work / f"existing.{kind}"
                    target.write_bytes(b"previous result")
                    self.run_action(source, target, expected=code,
                                    extra_env={"CONVERTER_MODE": mode})
                    self.assertEqual(b"previous result", target.read_bytes())

    def test_invalid_output_extension_or_directory_fails(self):
        for source, output in ((self.input, "wrong.pdf"),
                               (self.input / "sample.md", "wrong.zip"),
                               (self.input, "old-folder-output")):
            with self.subTest(output=output):
                self.run_action(source, self.work / output, expected=1)
        directory = self.work / "directory.pdf"
        directory.mkdir()
        self.run_action(self.input / "sample.md", directory, expected=1)
        self.assertFalse(self.argument_log.exists())

    def test_missing_input_or_binary_fails(self):
        self.run_action(self.work / "missing.md", self.work / "out.pdf", expected=1)
        self.binary = self.work / "missing-converter"
        self.run_action(self.input, self.work / "out.zip", expected=1)

    def test_unsupported_runners_fail_before_conversion(self):
        for env in ({"RUNNER_OS": "Windows"}, {"RUNNER_OS": "macOS"},
                    {"RUNNER_ARCH": "ARM64"}):
            with self.subTest(env=env):
                self.run_action(self.input, self.work / "out.zip", expected=1, extra_env=env)
        self.assertFalse(self.argument_log.exists())

    def test_output_cannot_overwrite_input_or_inject_workflow_outputs(self):
        source = self.work / "source.pdf"
        source.write_text("# Markdown content")
        self.run_action(source, source, expected=1)
        for suffix in ("injected\noutput-type=zip.pdf", "injected\r.pdf"):
            self.run_action(source, self.work / suffix, expected=1)
        self.assertEqual("# Markdown content", source.read_text())
        self.assertFalse(self.argument_log.exists())

    def test_bundled_binary_accepts_action_arguments_and_keeps_runner_guard(self):
        # No token or network needed: parsing succeeds, then the runner guard fails.
        self.binary = ROOT / "bin/itext-markdown2pdf"
        result = self.run_action(self.input / "sample.md", self.work / "guard.pdf",
                                 expected=2, extra_env={"GITHUB_ACTIONS": "false"})
        self.assertIn("Not running on a GitHub Actions runner", result.stderr)
        self.assertFalse((self.work / "guard.pdf").exists())


@unittest.skipUnless(os.environ.get("TEST_CONVERTER_BIN"),
                     "Set TEST_CONVERTER_BIN to the testing-only native executable")
class NativeConversionTests(ActionTestCase):
    def setUp(self):
        super().setUp()
        self.binary = Path(os.environ["TEST_CONVERTER_BIN"]).resolve()

    def assert_readable_pdf(self, path, expected_text):
        info = subprocess.run(["pdfinfo", str(path)], text=True, capture_output=True, check=True)
        self.assertRegex(info.stdout, r"Pages:\s+[1-9][0-9]*")
        text = subprocess.run(["pdftotext", str(path), "-"], text=True,
                              capture_output=True, check=True)
        self.assertIn(expected_text, text.stdout)

    def test_real_single_pdf(self):
        target = self.work / "real output/sample.pdf"
        self.run_action(ROOT / "test-fixtures/sample.md", target)
        self.assert_outputs(target, "pdf", 1)
        self.assert_readable_pdf(target, "Sample document")

    def test_real_nested_zip(self):
        target = self.work / "real output/documents.zip"
        self.run_action(self.input, target)
        self.assert_outputs(target, "zip", 2)
        with zipfile.ZipFile(target) as archive:
            self.assertIsNone(archive.testzip())
            self.assertEqual({"sample.pdf", "nested folder/second file.pdf"}, set(archive.namelist()))
            archive.extractall(self.work / "extracted")
        self.assert_readable_pdf(self.work / "extracted/sample.pdf", "Sample document")
        self.assert_readable_pdf(self.work / "extracted/nested folder/second file.pdf", "Second document")


if __name__ == "__main__":
    unittest.main(verbosity=2)

