# PDF Digital Signatures — Technical Reference Book

## Book Concept

A comprehensive technical reference written for engineers, developers, and security researchers who need to understand PDF digital signatures at the deepest technical level. Every claim is backed by precise references to ISO 32000-2, ETSI PAdES standards, relevant RFCs, and the eIDAS regulation. Code examples use Apache PDFBox 3.0.0 and BouncyCastle 1.80 on Java 21.

**This is not a tutorial — it is study material for experts.**

The voice is that of an experienced practitioner who has spent years in the trenches with PDF signatures. The tone is confident but generous — sharing hard-won knowledge, real-world war stories, and the kind of advice that saves hours of debugging at 2 AM. Subtle humor about Adobe, PDF's history, and industry quirks is welcome. See `memory/book-writing-style.md` for full writing guidelines.

### Writing Depth Philosophy

This book defaults to **verbose and comprehensive**. Every mention of a library, tool, standard, person, or concept must be accompanied by enough context that the reader understands the full story — origins, evolution, key people, why things are the way they are, and what the practical consequences are. When a topic is touched in passing, it gets a proper explanation right there, plus a cross-reference for the deep dive.

The target depth is **3x a typical technical reference**. Historical context, implementation comparisons across libraries/viewers, debugging guidance, and "side-talk" about related concepts are not tangential — they are core content. The book should be the one resource where a developer can find **everything** about PDF signatures, without needing to search elsewhere.

Page count is not a constraint. Quality and completeness trump brevity.

## Estimated Scope

- **32 chapters** across 6 parts + appendix
- **~450–550 pages** total (expanded depth — see Writing Depth Philosophy above)
- **~80 diagrams** (plain LaTeX tables/figures — no TikZ)
- **Source format:** AsciiDoc (Asciidoctor)
- **Build pipeline:** AsciiDoc → DocBook → pandoc → LaTeX (`book` class) → LuaLaTeX → PDF

## Table of Contents

| # | Chapter | Est. Pages | Key Content |
|---|---------|-----------|-------------|
| **Part I: Cryptographic Foundations** | | **~75 pp** | |
| 1 | Asymmetric Cryptography Primer | 14 | RSA/ECDSA key pairs, key generation, mathematical basis, key sizes vs security levels, EdDSA/Ed25519 for future-proofing, comparison of algorithm performance and adoption across PDF viewers |
| 2 | Hash Functions & Digital Signing | 14 | SHA-256/384/512, signing = encrypt(hash, privKey), verification, collision resistance, SHA-1 deprecation story (SHAttered, consequences), hash function evolution timeline, why MD5 still appears in legacy PDFs |
| 3 | X.509 Certificates | 16 | ASN.1/DER structure, extensions (KeyUsage, BasicConstraints, SubjectKeyIdentifier, AuthorityKeyIdentifier, CRL DistPoints), certificate profiles, real-world certificate dissection, how different CAs structure their certs, common certificate problems in the wild |
| 4 | Certificate Chains & Trust | 14 | Root CAs, intermediates, chain building, path validation (RFC 5280), OCSP, CRLs, trust store differences across OS/browsers/PDF viewers, Adobe AATL vs OS trust store vs custom trust store, debugging chain failures |
| 5 | Key Material Formats | 10 | PKCS#12/PFX, PEM, DER, JKS, PKCS#8, PKCS#1 — structure, conversion, code samples, HSM integration, PKCS#11 overview, why key format conversion goes wrong and how to debug it |
| 6 | CMS/PKCS#7 Signed Data | 10 | RFC 5652 structure, SignedData, SignerInfo, signed/unsigned attributes, detached signatures, CAdES extensions, evolution from PKCS#7 to CMS, library-specific CMS quirks (BouncyCastle vs older implementations) |
| **Part II: PDF Internals** | | **~70 pp** | |
| 7 | PDF File Structure | 16 | Objects, xref table, trailer, `%%EOF`, object streams, cross-reference streams (ISO 32000-2 §7), hex dump walkthrough of real PDFs, how different generators produce different structures |
| 8 | Incremental Updates | 14 | Append mode vs full rewrite, new xref sections, how objects are overridden, revision history, `%%EOF` markers, why incremental updates are critical for signatures, what goes wrong when tools flatten incrementally-saved PDFs |
| 9 | AcroForm & Interactive Forms | 12 | Field tree, widget annotations, field types, appearance streams (`/AP`), `/NeedAppearances`, XFA coexistence problems, form flattening and its impact on signatures, common form/signature interaction bugs |
| 10 | PDF Coordinate System | 8 | User space, page MediaBox/CropBox, annotation rectangles, transformations for viewer rendering, why signature rectangles appear in wrong positions, debugging coordinate issues |
| 11 | Font Embedding in PDF | 16 | Type1, TrueType, Type0/CIDFont, encoding, ToUnicode CMap, subsetting, `/BaseFont` naming, why fonts matter for signature appearances, font licensing implications, what happens when signature appearance fonts are missing |
| **Part III: PDF Digital Signatures** | | **~110 pp** | |
| 12 | Signature Architecture (ISO 32000-2 §12.8) | 20 | `/Sig` dictionary, `/ByteRange`, `/Contents`, `/SubFilter`, signature field vs signature value, `/TransformMethod`, full dictionary dissection with companion source blocks, seed value dictionaries, lock dictionaries |
| 13 | Byte Range Deep Dive | 18 | Coverage mechanics, gap for `/Contents`, trailing bytes, incremental saves after signing, multi-signature scenarios, hex visualization of byte ranges, what attackers exploit in byte range handling, size estimation strategies |
| 14 | Approval vs Certification Signatures | 14 | DocMDP (`/TransformMethod`), permission levels (P=1,2,3), Lock dictionaries (`/Action`, `/Fields`), FieldMDP, interaction rules, what happens when permission levels conflict, real-world DocMDP failures |
| 15 | Visual Appearance of Signatures | 12 | Appearance streams (`/AP /N`), layers (n0/n2/n4), text rendering, image overlays, invisible signatures, how different viewers render signatures, appearance generation code, common rendering bugs |
| 16 | Signature Validation | 18 | Structural integrity (byte range), cryptographic verification (CMS), certificate chain validation, modification detection, Adobe trust model, validation differences across viewers (Adobe/Foxit/LibreOffice/PDFBox), debugging validation failures step-by-step |
| 17 | PAdES Profiles (ETSI EN 319 142) | 14 | B-B, B-T, B-LT, B-LTA, differences from CAdES, ESS signing-certificate-v2 attribute, SubFilter values, profile comparison matrix, migration path from basic to LTA, common PAdES compliance failures |
| 18 | Timestamps & Long-Term Validation | 12 | RFC 3161 TSA, document timestamps, DSS dictionary, VRI, archival timestamps, TSA selection, timestamp embedding mechanics, what happens when timestamps expire, LTV chain construction |
| **Part IV: eIDAS & Qualified Signatures** | | **~60 pp** | |
| 19 | eIDAS Regulation (EU 910/2014) | 14 | Legal framework, electronic signatures vs advanced vs qualified, legal equivalence to handwritten, mutual recognition, practical implications for developers, member state implementation differences |
| 20 | Qualified Certificates & Trust Services | 14 | QTSPs, EUTL (EU Trusted List), conformity assessment, QSCD requirements, supervision, how to programmatically validate against EUTL, trust list parsing code |
| 21 | Qualified Signatures vs Seals | 8 | Natural persons vs legal entities, QES vs QESeal, use cases, legal effects, implementation differences |
| 22 | Remote Signing (Server-Side Key) | 14 | CSC API (Cloud Signature Consortium), SAM/HSM architecture, hash signing flow, SCAL1 vs SCAL2, sole control, vendor landscape comparison, integration code samples |
| 23 | EU DSS Framework | 10 | Architecture, modules (cades/pades/xades/jades), `SignatureParameters`, `SignatureTokenConnection`, `ToBeSigned`/`SignatureValue` flow, complete working examples |
| **Part V: Security, Incidents & Outlook** | | **~50 pp** | |
| 24 | PDF Signature Attacks — Technical Analysis | 16 | Ruhr-Uni Bochum attacks (2019-2021): USF, ISA, SWA, Shadow Attacks — full technical dissection with attack code, defense code, vulnerability matrices, researcher backgrounds |
| 25 | Certificate Authority Breaches | 12 | DigiNotar (2011), Comodo (2011), Symantec distrust, Let's Encrypt revocations — timeline + technical impact, forensic analysis, what changed in the trust ecosystem |
| 26 | Oddities & Annoyances | 10 | Adobe AATL monopoly, broken validation across viewers, SubFilter confusion, PAdES vs ISO 32000 divergence, timestamp chicken-and-egg, the many ways signing can fail silently |
| 27 | EUDI Wallet & Digital Identity | 12 | eIDAS 2.0 (2024 revision), EUDI wallet architecture, PID/QEAA attestations, ARF, selective disclosure, impact on signing, pilot program status, what developers need to prepare for |
| **Part VI: Code, Tools & Implementation** | | **~75 pp** | |
| 28 | The Java PDF Library Landscape | 18 | History & ecosystem: iText origins (Bruno Lowagie, 2000), the LGPL-to-AGPL transition (2009) and its industry shockwaves, why old iText 2.1.7 persists in production, IBM's iText involvement, OpenPDF fork (LibrePDF), PDFBox origin story (Ben Litchfield, Apache incubation), BouncyCastle's role, commercial libraries (iText 7/8, Apryse, PSPDFKit), analysis tools (rups, pdfalyzer, QPDF, Mutool, veraPDF), CMS library differences, choosing the right library for signing |
| 29 | PDFBox Signing — Complete Walkthrough | 18 | `PDDocument.addSignature()`, `SignatureInterface`, incremental save, appearance streams, multi-sign, code samples, PDFBox internals for signing, common PDFBox pitfalls, migration from PDFBox 2.x to 3.x |
| 30 | BouncyCastle CMS Construction | 12 | `CMSSignedDataGenerator`, signed attributes, PAdES-B-B compliance, digest calculation, code samples, BouncyCastle provider registration, algorithm negotiation, debugging CMS construction |
| 31 | Verification & Analysis with PDFBox | 14 | Extracting signatures, parsing CMS, certificate chain reconstruction, byte range verification, code samples, building a complete verification pipeline, handling edge cases |
| 32 | Test Cases & Reference PDFs | 10 | JUnit test cases for each scenario, test PDF generators, edge cases, building a test corpus, automated regression testing for signature validation |
| **Appendix** | | **~20 pp** | |
| A | Complete Code Listings | 10 | Full Java classes referenced in chapters |
| B | ASN.1 Structure Reference | 5 | OID table, CMS structures, X.509 extensions, common OIDs with explanations |
| C | Glossary & Spec References | 5 | ISO 32000-2, ETSI EN 319 142, RFC 5652, RFC 5280, RFC 3161, eIDAS 910/2014 |

## Graphics Estimate

| Part | Diagrams | Type |
|------|----------|------|
| I: Crypto Foundations | ~15 | Key pair flow, certificate structure (ASN.1 tree), chain diagrams, CMS structure, algorithm comparison |
| II: PDF Internals | ~18 | File structure layout, xref table, incremental update before/after, object graph, coordinate system, form field trees |
| III: PDF Signatures | ~22 | Byte range visualization, multi-sig scenarios, DocMDP flow, appearance stream layers, validation decision tree, PAdES profile comparison |
| IV: eIDAS | ~10 | EUTL hierarchy, remote signing sequence, CSC API flow, QSCD architecture |
| V: Security | ~10 | Shadow attack diagrams, attack trees, timeline graphics, vulnerability matrices |
| VI: Code & Tools | ~8 | Library ecosystem timeline, architecture diagrams, test flow, tool comparison |
| **Total** | **~83** | |

## Diagram Style Decision

**Plain LaTeX** — no TikZ. All diagrams rendered as LaTeX tables and figures using the standard `book` document class, `booktabs`, and `longtable`. Code blocks rendered as plain monospace (`fancyvrb`) with no syntax highlighting colors.

The AsciiDoc source uses `[source,asn1]` / `[source,java]` annotations to preserve semantic markup, but the LaTeX preamble overrides pandoc's highlighting to produce plain monospace output.

For diagrams that were previously TikZ (inline in `.adoc` via `[latexmath]`), these use `ifdef::backend-pdf[]` / `ifdef::backend-html5[]` conditional blocks with HTML fallback tables.

## Project Structure

```
pdf-signatures/
├── book.adoc                        # Master document (includes all chapters)
├── book-attributes.adoc             # Shared attributes, version, date
├── pdf-signature-book-concept.md    # This file
├── docker-compose.yml               # Build: docker compose run book pdf
├── docker/
│   └── Dockerfile                   # LuaLaTeX + asciidoctor + pandoc
├── scripts/
│   ├── build-book.sh                # Pipeline: adoc → docbook → latex → pdf
│   ├── compile-variants.sh          # Test diagram compilation
│   └── TikzExtractor.java           # SVG export for HTML output
├── latex/
│   └── preamble.tex                 # LaTeX preamble (typography, colors, overrides)
├── chapters/
│   ├── part-1-crypto/               # Chapters 1–6
│   ├── part-2-pdf-internals/        # Chapters 7–11
│   ├── part-3-pdf-signatures/       # Chapters 12–18 (ch12 written)
│   ├── part-4-eidas/                # Chapters 19–23
│   ├── part-5-security/             # Chapters 24–27
│   └── part-6-code/                 # Chapters 28–32
├── diagrams/                        # SVG diagrams (for HTML export)
├── appendix/
│   ├── app-a-code-listings.adoc
│   ├── app-b-asn1-reference.adoc
│   └── app-c-glossary.adoc
├── src/main/java/io/pdfalyzer/book/ # Compilable code examples
├── src/test/java/io/pdfalyzer/book/ # Test cases referenced in chapters
└── target/book/pdf/                 # Build output
```

## Current Status

- **Written:** All 32 chapters complete, all 6 parts
- **Written chapters:** 32 of 32 (ch01-32)
- **Current PDF:** 596 pages, 1.8 MB
- **Appendix:** A (Code Listings), B (ASN.1 Reference), C (Glossary) — all complete
- **Build pipeline:** Working (Docker, LuaLaTeX, tested)
- **Next steps:** Review and polish all chapters, complete appendix C, expand any thin sections

## Build Commands

```bash
cd pdf-signatures
docker compose run book pdf    # Full PDF
docker compose run book html   # HTML with embedded SVGs
docker compose run book svg    # Just diagrams → SVG
docker compose run book all    # Everything
```

Output: `target/book/pdf/pdf-digital-signatures.pdf`
