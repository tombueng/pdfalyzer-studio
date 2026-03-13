# Manual License Text Fallbacks

These files are shipped to cover dependencies whose declared license URLs can intermittently fail (HTTP 403/404) during automated `license:download-licenses` execution.

## Included fallback texts

- `GNU-LGPL-2.1.txt`
  - For LGPL-2.1 references that failed to download from historical GNU URLs.
- `GPL-2.0-with-classpath-exception.html`
  - For `GPL2 w/ CPE` style references when classpath exception URL retrieval fails.
- `JCodec-LICENSE.txt`
  - For JCodec license URL failures.
- `jQuery-MIT-LICENSE.txt`
  - For jQuery MIT license URL failures.

## Policy

- Keep automated downloads as primary source (`licenses/texts/`).
- Keep these manual files as deterministic fallback content in distributed artifacts.
- Update this folder if dependency graph or upstream license locations change.
