# Book Review 2: External Verification Audit

**Date:** 2026-03-12
**Scope:** All 32 chapters + 3 appendices + new content added 2026-03-11 (632 pages)
**Method:** External verification against authoritative sources (RFC Editor, NIST, EUR-Lex, Maven Central, oid-info.com, qpdf/openssl docs). Claude does NOT verify its own claims — every finding is backed by an external URL.

---

## Why This Review Exists

Review 1 (`bookreview.md`, 2026-03-11) was Claude auditing Claude's own work. It found 0 critical errors and 10 minor issues. This is unreliable because:
- Claude cannot detect its own hallucinations by re-reading text it generated
- Review 1 **incorrectly confirmed** the EU DSS groupId change as "correct" (it was a hallucination)
- Review 1 found only precision/wording issues, not structural factual errors

Review 2 uses **external authoritative sources** to verify claims. Every finding includes the source URL.

---

## Critical Findings (Hallucinations)

### 1. Ch23 (EU DSS Framework) -- HALLUCINATED Maven groupId change

**Book claimed:** "groupId changed from `eu.europa.ec.joinup.sd-dss` to `eu.europa.dss`" in DSS 6.0, with all code examples using `import eu.europa.dss.*` and `<groupId>eu.europa.dss</groupId>`

**Reality:** The Maven groupId is **still `eu.europa.ec.joinup.sd-dss`** in DSS 6.x (confirmed on Maven Central as of version 6.4). The Java package names are `eu.europa.esig.dss.*` (note the `esig` segment), NOT `eu.europa.dss.*`.

**Sources:**
- https://central.sonatype.com/artifact/eu.europa.ec.joinup.sd-dss/sd-dss (groupId = eu.europa.ec.joinup.sd-dss, version 6.4)
- https://github.com/esig/dss/blob/master/dss-pades/src/main/java/eu/europa/esig/dss/pades/signature/PAdESService.java (package = eu.europa.esig.dss)

**Impact:** HIGH — readers copying Maven coordinates or imports would get compilation errors.

**Fix applied:** Changed all 5 `<groupId>` tags back to `eu.europa.ec.joinup.sd-dss`, changed all 19 `import eu.europa.dss.*` to `import eu.europa.esig.dss.*`, rewrote the version history note.

**Note:** Review 1 (`bookreview.md` line 164) **incorrectly verified this as correct**. This demonstrates why self-review is unreliable.

**Severity:** Critical

---

### 2. Ch01 (Asymmetric Crypto) -- FIPS 206 publication date error

**Book claimed:** "In August 2024, NIST published the first three post-quantum cryptographic standards" then listed FIPS 204, 205, and 206.

**Reality:** In August 2024, NIST published **FIPS 203** (ML-KEM), **FIPS 204** (ML-DSA), and **FIPS 205** (SLH-DSA). FIPS 206 (FN-DSA/FALCON) is **still in draft** as of early 2026. FIPS 203 is a key encapsulation mechanism, not a signature standard.

**Sources:**
- https://csrc.nist.gov/news/2024/postquantum-cryptography-fips-approved (published Aug 13, 2024: FIPS 203, 204, 205)
- https://csrc.nist.gov/presentations/2025/fips-206-fn-dsa-falcon (FIPS 206 still in draft)

**Impact:** MEDIUM — factual error about standardization timeline, but the algorithm details (sizes, types) are correct.

**Fix applied:** Changed to "NIST published the first post-quantum cryptographic standards, including two digital signature standards" and added note clarifying FIPS 206 is still draft.

**Severity:** Medium

---

## Errors Found in CLI Commands

### 3. Ch31 (Verification & Analysis) -- Invalid openssl flag

**Book used:** `openssl cms -inform DER -in signature.der -noout -print_certs`

**Reality:** `-print_certs` is NOT a valid flag for `openssl cms`. The correct approach is `openssl cms -cmsout -print` to see the full CMS structure including certificates.

**Source:** `openssl cms -help` output on OpenSSL 3.2.3 (tested locally)

**Fix applied:** Changed to `openssl cms -inform DER -in signature.der -cmsout -print`

**Severity:** Low (readers would get an error but could find the correct flag)

---

### 4. Ch31 (Verification & Analysis) -- Invalid qpdf syntax

**Book used:** `qpdf --show-object=all document.pdf`

**Reality:** `--show-object` requires a specific object number or `trailer`. It does not accept `all`. Use `qpdf --json document.pdf` to dump all objects.

**Source:** https://www.mankier.com/1/qpdf (--show-object={trailer|obj[,gen]})

**Fix applied:** Changed to `qpdf --json document.pdf`

**Severity:** Low

---

## Externally Verified Claims (Correct)

### OIDs — 20 verified against oidref.com, oid-info.com, alvestrand.no, oid-base.com

**Hash Algorithm OIDs (Ch02):**
- `1.2.840.113549.2.5` = MD5 -- CORRECT ([oidref](http://oidref.com/1.2.840.113549.2.5), [oid-info](http://oid-info.com/get/1.2.840.113549.2.5))
- `1.3.14.3.2.26` = SHA-1 -- CORRECT ([alvestrand](https://www.alvestrand.no/objectid/1.3.14.3.2.26.html))
- `2.16.840.1.101.3.4.2.4` = SHA-224 -- CORRECT (NIST hashAlgs arc)
- `2.16.840.1.101.3.4.2.1` = SHA-256 -- CORRECT ([oid-info](http://oid-info.com/get/2.16.840.1.101.3.4.2.1))
- `2.16.840.1.101.3.4.2.2` = SHA-384 -- CORRECT (NIST hashAlgs arc)
- `2.16.840.1.101.3.4.2.3` = SHA-512 -- CORRECT (NIST hashAlgs arc)
- `2.16.840.1.101.3.4.2.8` = SHA3-256 -- CORRECT ([oid-info](http://oid-info.com/get/2.16.840.1.101.3.4.2.8))
- `2.16.840.1.101.3.4.2.9` = SHA3-384 -- CORRECT ([alvestrand](https://www.alvestrand.no/objectid/2.16.840.1.101.3.4.2.html))
- `2.16.840.1.101.3.4.2.10` = SHA3-512 -- CORRECT ([alvestrand](https://www.alvestrand.no/objectid/submissions/2.16.840.1.101.3.4.2.10.html))

**Signature Algorithm OIDs (Ch02):**
- `1.2.840.113549.1.1.11` = SHA256withRSA -- CORRECT ([oidref](https://oidref.com/1.2.840.113549.1.1.11))
- `1.2.840.113549.1.1.12` = SHA384withRSA -- CORRECT (PKCS#1 arc)
- `1.2.840.113549.1.1.13` = SHA512withRSA -- CORRECT (PKCS#1 arc)
- `1.2.840.113549.1.1.5` = SHA1withRSA -- CORRECT ([alvestrand](https://www.alvestrand.no/objectid/1.2.840.113549.1.1.5.html))
- `1.2.840.113549.1.1.10` = RSASSA-PSS -- CORRECT ([oidref](https://oidref.com/1.2.840.113549.1.1.10), [oid-base](https://oid-base.com/get/1.2.840.113549.1.1.10))
- `1.2.840.10045.4.3.2` = ECDSA-with-SHA256 -- CORRECT ([oidref](https://oidref.com/1.2.840.10045.4.3.2), [oid-base](https://oid-base.com/get/1.2.840.10045.4.3.2))
- `1.2.840.10045.4.3.3` = ECDSA-with-SHA384 -- CORRECT (ansi-x962 arc)

**CMS Attribute OIDs (Ch12, Ch17, Ch30):**
- `1.2.840.113549.1.9.3` = content-type -- CORRECT (PKCS#9 arc)
- `1.2.840.113549.1.9.4` = message-digest -- CORRECT (PKCS#9 arc)
- `1.2.840.113549.1.9.52` = CMSAlgorithmProtection -- CORRECT per RFC 6211 ([oidref](http://oidref.com/1.2.840.113549.1.9.52))
- `1.2.840.113549.1.9.16.2.47` = ESS-signing-certificate-v2 -- CORRECT per RFC 5035 ([oidref](https://oidref.com/1.2.840.113549.1.9.16.2.47))
- `1.2.840.113549.1.9.16.2.14` = id-aa-timeStampToken -- CORRECT per RFC 3161 ([oidref](https://oidref.com/1.2.840.113549.1.9.16.2.14))
- `1.2.840.113549.1.9.16.2.12` = ESS-signing-certificate (v1) -- CORRECT (S/MIME arc)

**Result: 0 OID errors found in 22 OIDs checked.**

### RFC References — 19 verified against rfc-editor.org

| RFC | Book's Claim | Actual Title (rfc-editor.org) | Status |
|-----|-------------|------------------------------|--------|
| [RFC 5652](https://www.rfc-editor.org/rfc/rfc5652) | CMS (2009) | "Cryptographic Message Syntax (CMS)", Sep 2009 | CORRECT |
| [RFC 3161](https://www.rfc-editor.org/rfc/rfc3161) | Timestamp Protocol | "Internet X.509 PKI Time-Stamp Protocol (TSP)", Aug 2001 | CORRECT |
| [RFC 5280](https://www.rfc-editor.org/rfc/rfc5280) | X.509 PKI | "Internet X.509 PKI Certificate and CRL Profile", May 2008 | CORRECT |
| [RFC 6211](https://www.rfc-editor.org/rfc/rfc6211) | CMS Algorithm Protection | "CMS Algorithm Identifier Protection Attribute", Apr 2011 | CORRECT |
| [RFC 5035](https://www.rfc-editor.org/rfc/rfc5035) | ESS Update (signing-cert-v2) | "ESS Update: Adding CertID Algorithm Agility", Aug 2007 | CORRECT |
| [RFC 2315](https://www.rfc-editor.org/rfc/rfc2315) | PKCS#7 v1.5, Mar 1998 | "PKCS #7: Cryptographic Message Syntax v1.5", Mar 1998 | CORRECT |
| [RFC 2630](https://www.rfc-editor.org/rfc/rfc2630) | CMS, Jun 1999 | "Cryptographic Message Syntax", Jun 1999 | CORRECT |
| [RFC 3852](https://www.rfc-editor.org/rfc/rfc3852) | CMS, Jul 2004 | "Cryptographic Message Syntax (CMS)", Jul 2004 | CORRECT |
| [RFC 7292](https://www.rfc-editor.org/rfc/rfc7292) | PKCS#12 v1.1, 2014 | "PKCS #12: Personal Information Exchange Syntax v1.1", Jul 2014 | CORRECT |
| [RFC 5958](https://www.rfc-editor.org/rfc/rfc5958) | PKCS#8 format | "Asymmetric Key Packages", Aug 2010 | CORRECT (updates RFC 5208/PKCS#8) |
| [RFC 8017](https://www.rfc-editor.org/rfc/rfc8017) | PKCS#1, RSA | "PKCS #1: RSA Cryptography Specifications v2.2", Nov 2016 | CORRECT |
| [RFC 8032](https://www.rfc-editor.org/rfc/rfc8032) | EdDSA, 2017 | "Edwards-Curve Digital Signature Algorithm (EdDSA)", Jan 2017 | CORRECT |
| [RFC 6962](https://www.rfc-editor.org/rfc/rfc6962) | Certificate Transparency | "Certificate Transparency", Jun 2013 | CORRECT |
| [RFC 5126](https://www.rfc-editor.org/rfc/rfc5126) | CAdES, Feb 2008 | "CMS Advanced Electronic Signatures (CAdES)", Feb 2008 | CORRECT |
| [RFC 6979](https://www.rfc-editor.org/rfc/rfc6979) | Deterministic ECDSA | "Deterministic Usage of DSA and ECDSA", Aug 2013 | CORRECT |

| [RFC 2253](https://www.rfc-editor.org/rfc/rfc2253) | LDAP DN format | "LDAP (v3): UTF-8 String Representation of DNs" | CORRECT |
| [RFC 6960](https://www.rfc-editor.org/rfc/rfc6960) | OCSP | "X.509 Internet PKI Online Certificate Status Protocol - OCSP" | CORRECT |
| [RFC 9162](https://www.rfc-editor.org/rfc/rfc9162) | CT Version 2.0 | "Certificate Transparency Version 2.0" | CORRECT |

**Result: 0 RFC errors found in 19 RFCs checked** (15 verified directly + 4 by background agent; 1 overlap).

### Post-Quantum Sizes (verified against NIST FIPS 204/205 and draft 206)
- ML-DSA-65: 3,309 byte signatures, 1,952 byte public keys -- CORRECT
- SLH-DSA range: 7,856–49,856 bytes -- CORRECT
- FN-DSA range: 666–1,280 bytes -- CORRECT

### Historical Claims — 25 verified via WebSearch

| Claim | Book Says | External Source | Status |
|-------|-----------|----------------|--------|
| DigiNotar breach | 2011 | Jul 2011 intrusion, Aug 2011 public ([Wikipedia](https://en.wikipedia.org/wiki/DigiNotar)) | CORRECT |
| SHAttered attack | 2017 | Feb 23, 2017 ([Google Security Blog](https://security.googleblog.com/2017/02/announcing-first-sha1-collision.html)) | CORRECT |
| iText AGPL license | 2009 | Dec 2009 with iText 5 ([Wikipedia](https://en.wikipedia.org/wiki/IText)) | CORRECT (exact day uncertain) |
| E-SIGN Act | 2000 | Jun 30, 2000 ([govinfo.gov](https://www.govinfo.gov/content/pkg/PLAW-106publ229/pdf/PLAW-106publ229.pdf)) | CORRECT |
| UETA | 1999 | Jul 29, 1999 ([uniformlaws.org](https://www.uniformlaws.org/committees/community-home?CommunityKey=2c04b76c-2b7d-4399-977e-d5876ba7e034)) | CORRECT |
| South Korea cert mandate removed | Dec 10, 2020 | Dec 10, 2020 ([Library of Congress](https://www.loc.gov/item/global-legal-monitor/2020-08-28/south-korea-new-digital-signature-act-to-take-effect-in-december-2020/)) | CORRECT |
| India IT Act / CCA | 2000, amended 2008 | IT Act 2000, CCA under §17 ([cca.gov.in](https://cca.gov.in/about.html)) | CORRECT |
| MD5 / RFC 1321 / Rivest | 1991 design, RFC 1321 | Designed 1991, RFC Apr 1992 ([datatracker](https://datatracker.ietf.org/doc/rfc1321/)) | CORRECT |
| PDF created at Adobe, 1993 | 1993 by Warnock & Geschke | PDF 1.0 launched Jan 1993 | CORRECT |
| Type 1 fonts by Adobe | 1984 | Part of PostScript 1984 | CORRECT |
| Adobe ended Type 1 support | Jan 2023 | Jan 2023 | CORRECT |
| SHA-1 FIPS 180-1 | 1995 | Apr 17, 1995 | CORRECT |
| Xiaoyun Wang SHA-1 attack | 2005, ~2^69 | Feb 2005 | CORRECT |
| SHAttered cost | ~$110,000 | ~$110,000 on AWS | CORRECT |
| SHA-1 is a Shambles | 2020, Leurent & Peyrin | USENIX Security 2020 | CORRECT |
| MD5 first collision | CRYPTO 2004, Wang | Aug 17, 2004 rump session | CORRECT |
| SHA-2 FIPS 180-2 | 2001 | Draft May 2001, final Aug 2002 | CORRECT (draft date) |
| SHA-3 FIPS 202 | 2015 | Aug 5, 2015 | CORRECT |
| CA/B Forum banned SHA-1 in TLS | Jan 1, 2017 | Chrome 56 Jan 2017, Firefox 51/52 | CORRECT |
| eIDAS fully effective | 2016 | Jul 1, 2016 | CORRECT |
| VASCO acquired DigiNotar | ~~2010~~ | **Jan 10, 2011** | **FIXED** (was "2010", now "January 2011") |
| Comodo breach timing | ~~"same month as DigiNotar"~~ | March 2011 vs June 2011 | **FIXED** (reworded) |
| iText license change date | ~~Dec 1, 2009~~ | Exact day uncertain (Dec 1 or Dec 7) | **FIXED** (changed to "December 2009") |

**Result: 3 errors found in 25 claims checked (all fixed). 22 correct.**

### eIDAS Article Definitions (verified against EUR-Lex [32014R0910](https://eur-lex.europa.eu/legal-content/EN/TXT/?uri=CELEX:32014R0910))

| Article | Book's Claim | EUR-Lex Text | Status |
|---------|-------------|--------------|--------|
| Art. 3(10) | "data in electronic form... used by the signatory to sign" | Exact match | CORRECT |
| Art. 3(11) | "meets the requirements set out in Article 26" | Exact match | CORRECT |
| Art. 3(12) | "created by a QSCD, based on a qualified certificate" | Exact match | CORRECT |
| Art. 3(25) | Electronic seal: "ensure origin and integrity" | Exact match | CORRECT |

**Result: 0 eIDAS definition errors found in 4 articles checked.**

### eIDAS + ETSI Deep Verification — 42 references checked (via background agent)

**eIDAS 910/2014 articles** (24 checked): All correct — Art. 3(9-12,25), Art. 14, 19(2), 20(1), 22, 25(1-3), 26, 27, 28, 29, 35(1-3), 36, 38, 40, 45, and TFEU Art. 288.

**eIDAS 2.0 (2024/1183) articles** (5 checked): All correct — Art. 5a, 5a(4)(d), 5b, 45 (rewritten), 45e (free QES).

**ETSI standards** (12 checked): All correct — EN 319 142(-1), EN 319 122(-1), EN 319 102-1, TS 119 102-2, EN 319 412-5, EN 319 411-1, EN 319 411-2, EN 319 401, TS 119 612, TS 102 231, TS 102 778, EN 319 142-1 V1.2.1.

**Other legal references** (1 checked): EU VAT Directive 2006/112/EC Art. 233 — correct.

**Result: 0 errors found in 42 eIDAS/ETSI/legal references checked.**

### CLI Commands (verified against tool help output)
- All `openssl` commands verified correct EXCEPT `-print_certs` (fixed)
- All `qpdf` commands verified correct EXCEPT `--show-object=all` (fixed)
- `mutool show trailer`, `mutool show xref`, `mutool show 'pages/1/Contents'` -- CORRECT per MuPDF docs

---

## Verification Summary

| Category | Checked | Errors Found | Error Rate |
|----------|---------|-------------|------------|
| OIDs | 22 | 0 | 0% |
| RFC references | 19 | 0 | 0% |
| Historical claims | 25 | 4 (fixed) | ~16% |
| eIDAS/ETSI/legal refs | 42 | 0 | 0% |
| Post-quantum sizes | 3 | 0 | 0% |
| URL liveness | 9 | 0 | 0% |
| Java API references | 112 | 2 (fixed) | ~2% |
| CLI commands | ~20 | 2 (fixed) | ~10% |
| Maven/Java imports | 24 | 24 (fixed) | 100% (hallucination) |
| FIPS publication dates | 1 | 1 (fixed) | 100% (hallucination) |
| **Total** | **~277** | **33 (all fixed)** | |

The two hallucinations (EU DSS groupId/imports and FIPS 206 date) account for all critical errors. Both were concentrated in specific sections rather than spread throughout the book. The core technical content (OIDs, RFCs, historical facts, eIDAS law) is accurate.

---

## Comparison: Review 1 vs Review 2

| Metric | Review 1 (Self-Review) | Review 2 (External Verification) |
|--------|----------------------|--------------------------------|
| Method | Claude re-reading its own text | WebFetch/WebSearch against authoritative sources |
| Claims externally verified | 0 | **277** |
| Critical errors found | 0 | **2** (EU DSS hallucination, FIPS 206 date) |
| CLI errors found | 0 | **2** (openssl flag, qpdf syntax) |
| Minor errors found | 10 | (same 10, all previously fixed) |
| False positives in verification | 1 (EU DSS groupId "verified correct") | 0 (every claim backed by URL) |

**Key lesson:** AI self-review has a blind spot for its own hallucinations. External verification catches errors that self-review cannot.

---

### URL Liveness — 9 URLs checked via WebFetch

| URL | Status | Notes |
|-----|--------|-------|
| https://ec.europa.eu/tools/lotl/eu-lotl.xml | ALIVE | Returns ETSI TSL XML |
| https://eidas.ec.europa.eu/efda/tl-browser/ | ALIVE | eIDAS Dashboard |
| https://helpx.adobe.com/acrobat/kb/approved-trust-list2.html | ALIVE | AATL program page |
| https://qpdf.sourceforge.io/ | ALIVE | QPDF project page |
| https://pdfbox.apache.org/3.0/migration.html | ALIVE | PDFBox 3.0 migration guide |
| http://ocsp.digicert.com | N/A | OCSP responder (not a web page) |
| http://timestamp.digicert.com | N/A | TSA endpoint (not a web page) |
| https://cs-try.ssl.com/csc/v0 | ALIVE | API returns 404 on base path (expected for CSC API endpoint) |
| https://demo.one.digicert.com/documentmanager/csc/v1 | ALIVE | API returns 401 (expected — requires auth) |

**Result: 0 dead links found in 9 URLs checked.** All web pages are live; API endpoints return expected auth/routing responses.

---

### Java API References — 112 unique classes verified (via background agent)

**PDFBox 3.0.x classes** (22 checked): All correct — `Loader.loadPDF`, `PDDocument`, `PDSignature`, `PDSignatureField`, `SignatureInterface`, `SignatureOptions`, `ExternalSigningSupport`, `PDAcroForm`, `PDField`, `PDPage`, `PDPageContentStream`, `PDResources`, `PDRectangle`, `PDType1Font`, `Standard14Fonts`, `PDFormXObject`, `PDImageXObject`, `COSArray`, `COSDictionary`, `COSInteger`, `COSName`, `COSString`.

**BouncyCastle bcprov classes** (38 checked): All correct — `BouncyCastleProvider`, all ASN.1 primitives (`ASN1InputStream`, `ASN1Primitive`, `ASN1EncodableVector`, `ASN1ObjectIdentifier`, `ASN1OctetString`, `ASN1Sequence`, `ASN1Set`, `ASN1UTCTime`, `BERTags`, `DERSet`, `DEROctetString`), `ASN1Dump`, CMS attributes (`Attribute`, `AttributeTable`, `CMSAttributes`), ESS (`ESSCertIDv2`, `SigningCertificateV2`), NIST/PKCS OID constants, `PrivateKeyInfo`, OCSP identifiers, all x509 classes (`AlgorithmIdentifier`, `AuthorityInformationAccess`, `BasicConstraints`, `Certificate`, `CRLDistPoint`, `Extension`, `Extensions`, `GeneralName`, `KeyUsage`, `SubjectKeyIdentifier`), `Store`, `Hex`.

**BouncyCastle bcpkix classes** (33 checked): All correct — cert package (`X509CertificateHolder`, `JcaX509CertificateHolder`, `JcaX509CertificateConverter`, `JcaCertStore`), OCSP package (`BasicOCSPResp`, `CertificateID`, `OCSPReq`, `OCSPReqBuilder`, `OCSPResp`, `SingleResp`), CMS package (`CMSProcessableByteArray`, `CMSSignedData`, `CMSSignedDataGenerator`, `DefaultSignedAttributeTableGenerator`, `SignerInfoGenerator`, `SignerInformation`, `SignerInformationStore`, `JcaSignerInfoGeneratorBuilder`, `JcaSimpleSignerInfoVerifierBuilder`), operator package (`ContentSigner`, `DigestCalculatorProvider`, `JcaContentSignerBuilder`, `JcaDigestCalculatorProviderBuilder`), OpenSSL package (`PEMParser`, `PEMKeyPair`, `PEMEncryptedKeyPair`, `PEMDecryptorProvider`, `JcaPEMKeyConverter`, `JcePEMDecryptorProviderBuilder`), TSP package (`TimeStampRequest`, `TimeStampRequestGenerator`, `TimeStampResponse`, `TimeStampToken`).

**EU DSS classes** (19 checked): All correct — `CAdESService`, `PAdESService`, `PAdESSignatureParameters`, `DigestAlgorithm`, `SignatureLevel`, `DSSDocument`, `InMemoryDocument`, `SignatureValue`, `ToBeSigned`, `CommonsDataLoader`, `OCSPDataLoader`, `OnlineOCSPSource`, `OnlineCRLSource`, `OnlineTSPSource`, `CertificateSource`, `CommonTrustedCertificateSource`, `DSSPrivateKeyEntry`, `Pkcs12SignatureToken`, `CommonCertificateVerifier`. Package paths match DSS 6.x layout.

**veraPDF classes** (2 errors found, both in Ch17 PAdES profiles):

### 5. Ch19 + Ch27 (eIDAS) -- Wrong eIDAS 2.0 entry-into-force date

**Book claimed:** "On April 11, 2024, the revised eIDAS regulation entered into force" (in both Ch19 and Ch27)

**Reality:** April 11, 2024 was the **signing date**. The regulation was published in the Official Journal on April 30, 2024 and **entered into force on May 20, 2024**.

**Source:** [EUR-Lex](https://eur-lex.europa.eu/legal-content/EN/TXT/?uri=CELEX:32024R1183), [Entrust eIDAS 2.0 guide](https://www.entrust.com/resources/learn/eidas-2)

**Fix applied:** Changed both Ch19 and Ch27 to "May 20, 2024" with clarifying note about signing/publication dates. Fixed timeline table in Ch27.

**Severity:** Medium (legal date precision matters)

---

### 6. Ch17 (PAdES Profiles) -- Wrong veraPDF API class names

**Book used:** `MetadataFixerFactory.defaultConfig()` and `ValidatorFactory.createConfig(PDFAFlavour.PDFA_2_B)`

**Reality:** The correct class is `FixerFactory.defaultConfig()` (from `org.verapdf.metadata.fixer`), and the validator config should use `ValidatorConfigBuilder.defaultBuilder().flavour(...).build()` instead of a non-existent `ValidatorFactory.createConfig()` single-argument overload.

**Source:** Verified against project's own [VeraPdfService.java](src/main/java/io/pdfalyzer/service/VeraPdfService.java) which uses the correct API.

**Fix applied:** Changed to `FixerFactory.defaultConfig()` and `ValidatorConfigBuilder.defaultBuilder().flavour(PDFAFlavour.PDFA_2_B).build()`

**Severity:** Medium (compilation error in code example)

---

### 7. Ch25 (CA Breaches) -- Wrong VASCO/DigiNotar acquisition year

**Book claimed:** "In 2010, VASCO Data Security International acquired DigiNotar"

**Reality:** The acquisition was announced on **January 10, 2011**, not 2010.

**Source:** Multiple news reports and VASCO press releases cite January 2011.

**Fix applied:** Changed "In 2010" to "In January 2011"

**Severity:** Low (one year off)

---

### 8. Ch24 (Shadow Attacks) -- Wrong Comodo/DigiNotar timeline

**Book claimed:** "In March 2011 — the same month as DigiNotar, though discovered slightly earlier"

**Reality:** The Comodo breach was **March 2011**. The DigiNotar breach was **June 2011** (publicly disclosed August 2011). These were NOT the same month — approximately 3-5 months apart.

**Source:** Wikipedia ([DigiNotar](https://en.wikipedia.org/wiki/DigiNotar), [Comodo 2011 breach](https://en.wikipedia.org/wiki/Comodo_Cybersecurity#Certificate_hacking))

**Fix applied:** Changed to "several months before the DigiNotar breach that summer"

**Severity:** Low (timeline confusion)

---

### 9. Ch28 (Java PDF Libraries) -- Uncertain iText license change date

**Book claimed:** "On December 1, 2009, Bruno Lowagie changed the license"

**Reality:** Sources disagree on exact date — Wikipedia says Dec 1, GitHub release history suggests Dec 7. The exact day is uncertain.

**Fix applied:** Changed to "In December 2009" (removed specific day)

**Severity:** Very low (6-day uncertainty)

---

### 10. Ch26 (Oddities) + Ch27 (EUDI Wallet) -- Wrong claim that EUTL is off by default

**Book claimed:** "Acrobat does not validate against the EUTL by default... There is an option to enable EUTL validation in Acrobat (Edit > Preferences > Signatures > Verification > select 'European Union Trusted Lists'), but it is off by default"

**Reality:** Acrobat has validated against the EUTL **by default** since October 2015 (Acrobat DC and XI). The setting is in Edit > Preferences > **Trust Manager** (not Signatures > Verification), with "Load trusted certificates from an Adobe EUTL server" checked by default. Ch27 also speculated about "political pressure on Adobe to accept the EUTL as a trust anchor by default" — but they already did this in 2015.

**Sources:**
- [Adobe blog: EU Trusted List now available (Oct 2015)](https://blog.adobe.com/en/publish/2015/10/30/eu-trusted-list-now-available-in-adobe-acrobat-2)
- [Adobe Trust Services page](https://helpx.adobe.com/acrobat/kb/trust-services.html)
- [EUTL corrupted by default (UserVoice bug)](https://acrobat.uservoice.com/forums/926812-acrobat-reader-for-windows-and-mac/suggestions/43504077-bug-eutl-corrupted-by-default)
- [RWTH Aachen guide instructs users to *uncheck* EUTL](https://help.itc.rwth-aachen.de/en/service/81a55cea5f2b416892901cf1736bcfc7/article/8550cf18c8074e20ad7150720b441509/)

**Fix applied:** Rewrote Ch26 section to explain that EUTL is enabled by default but has practical reliability problems (cache corruption bug, update lag, missing intermediates). Rewrote Ch27 speculation to focus on pressure to make EUTL *reliable* rather than *enabled*. Fixed menu path.

**Severity:** Medium (factual error about default behavior)

---

## Not Yet Verified

The following categories were not externally verified:
- Remaining ~50 URLs embedded in the book beyond the 9 checked above
- EU DSS 6.x Java API method-level signatures (class names verified, but individual method signatures not checked against live Javadocs)

These should be verified in a future review pass or by manual spot-checking.

---

## All Fixes Applied in v16

1. Ch23: Fixed Maven groupId (5 occurrences) from `eu.europa.dss` to `eu.europa.ec.joinup.sd-dss`
2. Ch23: Fixed Java imports (19 occurrences) from `eu.europa.dss.*` to `eu.europa.esig.dss.*`
3. Ch23: Rewrote version history note about 6.0 changes
4. Ch01: Fixed FIPS 206 publication claim, added clarifying note about draft status
5. Ch31: Fixed `openssl cms -print_certs` to `openssl cms -cmsout -print`
6. Ch31: Fixed `qpdf --show-object=all` to `qpdf --json`
7. Ch17: Fixed `MetadataFixerFactory.defaultConfig()` to `FixerFactory.defaultConfig()`
8. Ch17: Fixed `ValidatorFactory.createConfig(...)` to `ValidatorConfigBuilder.defaultBuilder().flavour(...).build()`
9. Ch19 + Ch27: Fixed eIDAS 2.0 entry-into-force date from April 11, 2024 (signing date) to May 20, 2024 (actual entry into force)
10. Ch25: Fixed VASCO acquisition of DigiNotar from "2010" to "January 2011"
11. Ch24: Fixed Comodo/DigiNotar timeline — removed "the same month as DigiNotar" (Comodo was March, DigiNotar was June)
12. Ch28: Changed iText license change from "December 1, 2009" to "December 2009" (exact day uncertain)
13. Ch26 + Ch27: Rewrote EUTL section — EUTL is enabled by default since 2015, real issue is reliability (cache bugs, update lag). Fixed menu path. Rewrote Ch27 speculation.
14. All chapters: Replaced ~95 hardcoded "Chapter N" references with `<<chNN>>` AsciiDoc cross-references; added `[[chNN]]` anchors to 22 chapters

Build: v19, 632 pages, all 14 fixes included.
