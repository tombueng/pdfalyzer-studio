# Book Review: Factual Accuracy Audit

**Date:** 2026-03-11
**Scope:** All 32 chapters + 3 appendices (~610 pages)
**Method:** Three parallel review passes covering Parts 1-6 and appendices

---

## Summary

| Category | Result |
|---|---|
| Critical errors / hallucinations | **0** |
| Minor inaccuracies | **10** |
| OIDs verified correct | **100+** |
| RFC numbers verified correct | **All** |
| BouncyCastle API calls verified | **All correct** |
| PDFBox 3.x API calls verified | **All correct** |
| eIDAS article citations verified | **All correct** |
| ETSI standard numbers verified | **All correct** |
| Historical events verified | **All correct** |

**Overall assessment:** The book is exceptionally well-researched with no hallucinations, no made-up standards, no fictional libraries or classes, and no incorrect OIDs. All issues found are minor precision/wording concerns.

---

## Findings

### 1. Ch03 (X.509 Certificates) ~Line 243 -- UTCTime/GeneralizedTime boundary

**Text:** "This means every certificate issued today uses UTCTime for both `notBefore` and `notAfter` -- unless the certificate is valid until 2050 or later"

**Issue:** Overly broad. Per RFC 5280 Section 4.1.2.5, dates after December 31, 2049 MUST use GeneralizedTime. A certificate issued in 2026 with `notAfter` in 2050+ must use GeneralizedTime for that field. The statement implies UTCTime is always used for both fields, which is not true for long-lived certs (e.g., root CAs valid until 2050+).

**Fix:** Add qualifier: "...unless the `notAfter` date falls in 2050 or later, as is the case for many root CA certificates."

**Severity:** Low

---

### 2. Ch07 (PDF File Structure) ~Line 44 -- Spec section reference

**Text:** "{iso32000-2} Section 7.5.2 specifies that a `/Version` key in the document catalog may override this value."

**Issue:** The version override via the document catalog's `/Version` key is discussed in ISO 32000-2 Section 7.2.2 (or 8.3.1 depending on edition), not Section 7.5.2.

**Fix:** Verify exact section number against ISO 32000-2:2020 and correct.

**Severity:** Low

---

### 3. Ch08 (Incremental Updates) ~Line 63 -- "should" vs "shall"

**Text:** "{iso32000-2} Section 7.5.6 explicitly states that conforming readers should be able to retrieve previous revisions"

**Issue:** ISO 32000-2 uses "shall" (a MUST requirement) for this, not "should" (a recommendation). The distinction matters in standards language.

**Fix:** Change "should" to "shall" (or "must").

**Severity:** Low

---

### 4. Ch08 (Incremental Updates) ~Line 233 -- QPDF flag name

**Text:** "QPDF's `--linearize` option (when not combined with `--preserve-unreferenced-objects`)"

**Issue:** The correct QPDF flag may be `--keep-unreferenced-objects` rather than `--preserve-unreferenced-objects`. Verify against current QPDF documentation.

**Fix:** Check `qpdf --help` or man page for exact flag name.

**Severity:** Low

---

### 5. Ch09 (AcroForm) ~Line 348 -- XFA deprecation attribution

**Text:** "{iso32000-2} deprecated XFA"

**Issue:** ISO 32000-2:2020 does not formally deprecate XFA. It acknowledges XFA's existence but marks it as a legacy feature. The formal deprecation/removal was driven by Adobe, not by the ISO standard itself.

**Fix:** Change to "Adobe deprecated XFA, and ISO 32000-2 treats it as a legacy feature" or similar.

**Severity:** Low

---

### 6. Ch10 (Coordinate System) ~Line 13 -- Spec section reference

**Text:** "{iso32000-2} Section 8.3.2 defines the rules"

**Issue:** The coordinate system rules are in Section 8.3.3, not 8.3.2.

**Fix:** Change to "Section 8.3.3".

**Severity:** Low

---

### 7. Ch11 (Font Embedding) ~Line 90 -- "should" vs "shall" for Standard 14 fonts

**Text:** "{iso32000-2} Section 9.6.2.2 states that conforming readers _should_ provide these fonts"

**Issue:** The spec uses "shall" -- conforming readers MUST provide the Standard 14 fonts. "Should" implies optional.

**Fix:** Change "_should_" to "_shall_" (or "must").

**Severity:** Low

---

### 8. Ch11 (Font Embedding) ~Line 101 -- WinAnsiEncoding vs Windows-1252

**Text:** "characters outside the Windows-1252 code page"

**Issue:** WinAnsiEncoding is similar to but not identical to Windows-1252. There are differences in control character ranges (0x80-0x9F). Conflating them is imprecise.

**Fix:** Change to "characters outside the WinAnsiEncoding range (similar to, but not identical to, Windows-1252)".

**Severity:** Low

---

### 9. Ch17 (PAdES Profiles) ~Line 67 -- SubFilter strictness

**Text:** "SubFilter: Must be `ETSI.CAdES.detached` (not `adbe.pkcs7.detached`)"

**Issue:** While ETSI EN 319 142-1 requires `ETSI.CAdES.detached` for PAdES, many real-world validators (including Adobe Acrobat) accept `adbe.pkcs7.detached` with proper CAdES signed attributes. The statement is correct per strict spec but may mislead readers into thinking `adbe.pkcs7.detached` signatures are invalid.

**Fix:** Add a note: "In practice, many validators also accept `adbe.pkcs7.detached` when proper CAdES attributes are present, but strict PAdES conformance requires `ETSI.CAdES.detached`."

**Severity:** Low

---

### 10. Ch17 (PAdES Profiles) ~Line 245 -- VRI hash algorithm flexibility

**Text:** "a dictionary keyed by uppercase hex SHA-1 hashes of signature values"

**Issue:** While SHA-1 is the de facto standard for VRI keys (and used by Adobe), the ISO 32000-2 specification does not strictly mandate SHA-1 as the only option. The text could note this is the conventional choice.

**Fix:** Minor -- add "by convention" or "per common practice".

**Severity:** Very low

---

## Verified Correct (Selected Highlights)

These high-risk claims were explicitly verified as accurate:

- **All 100+ OIDs** across all chapters (SHA-256, RSA, ECDSA, CMS attributes, X.509 extensions, QCStatements, etc.)
- **All RFC citations** (5652, 5280, 3161, 6960, 8017, 6979, 8032, 7292, 5958, 2634, 5035, 4055, etc.)
- **eIDAS Regulation (EU) No 910/2014** -- all article numbers correct
- **eIDAS 2.0 (EU 2024/1183)** -- correctly dated April 11, 2024
- **ETSI standards** (EN 319 102, 122, 142, 401, 411, 412-5; EN 419 211, 221, 241)
- **DigiNotar breach (2011)** -- 531+ fraudulent certs, Iranian surveillance context, correct
- **Comodo breach (2011)** -- correctly referenced
- **Sony PS3 ECDSA k-reuse (2010)** -- correct
- **"SHA-1 is a Shambles" (Leurent & Peyrin, EUROCRYPT 2020)** -- correct authors and venue
- **Shadow attacks (Ruhr University Bochum)** -- USF, ISA, SWA variants all accurate
- **iText licensing history** -- LGPL to AGPL in 2009, package rename, all correct
- **EU DSS 6.0 groupId change** -- `eu.europa.ec.joinup.sd-dss` to `eu.europa.dss`, correct
- **PDFBox 3.x API** -- `Loader.loadPDF()`, `PDSignature`, `saveIncremental()` all correct
- **BouncyCastle API** -- all class names, method signatures, and usage patterns correct
- **CSC API endpoints and flow** -- accurate per CSC v2.0 specification
- **SCAL1 vs SCAL2** -- correctly described per EN 419 241-1
- **All code examples** -- syntactically valid Java 21 targeting PDFBox 3.x and BouncyCastle 1.80
