<!--
Copyright (c) 2026 Apryse Group NV
SPDX-License-Identifier: MIT
See LICENSE in the repository root.
-->

# Releasing

1. Run `python3 -m unittest discover -s tests -v`. Rebuild and replace **only the production
   executable** using the procedure below if necessary. Push to a **public**
   GitHub repo with `action.yml` at the root; wait for CI to be green. Check that
   the self-test produced both `documents.zip` (including nested PDFs) and
   `sample.pdf`, and that the uploaded artifact can be downloaded.
2. Tag and push:
   ```bash
   git tag -a v1.0.0 -m "itext-markdown2pdf v1.0.0" && git push origin v1.0.0
   git tag -f v1 v1.0.0 && git push -f origin v1
   ```
3. In the GitHub UI: Releases → Draft a new release → pick `v1.0.0` → tick
   **"Publish this Action to the GitHub Marketplace"** → category *Publishing* → publish.
4. Verify in a separate public repo that `uses: itext/markdown2pdf@v1`
   produces a `.pdf` for file input and a `.zip` for folder input. Upload
   `steps.<step-id>.outputs.output-path` with `actions/upload-artifact@v4` and
   confirm the downloaded files are readable. Local conversion tests do not
   verify the hosted `GITHUB_TOKEN`, public-repository check, or Marketplace
   publication; those must be checked on GitHub.

