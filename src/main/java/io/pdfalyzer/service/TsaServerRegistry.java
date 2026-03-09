package io.pdfalyzer.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.stereotype.Component;

import io.pdfalyzer.model.TsaServer;

/**
 * Registry of known public TSA (Time Stamp Authority) servers.
 * Curated list of free/public endpoints for RFC 3161 timestamping.
 */
@Component
public class TsaServerRegistry {

    private final List<TsaServer> servers;

    public TsaServerRegistry() {
        List<TsaServer> list = new ArrayList<>();

        // ═══════════════════════════════════════════════════════════════════
        // QUALIFIED TSA (eIDAS EU Trusted List)
        // These are operated by Qualified Trust Service Providers (QTSPs)
        // and can produce Qualified Electronic Time Stamps per eIDAS Art. 42
        // ═══════════════════════════════════════════════════════════════════

        list.add(TsaServer.builder()
                .id("digicert-qualified")
                .url("http://timestamp.digicert.com")
                .name("DigiCert Timestamp")
                .provider("DigiCert Inc.")
                .country("US")
                .category("qualified")
                .qualifiedEidas(true)
                .freeTier(true)
                .info("DigiCert (formerly Symantec/VeriSign). One of the most widely used TSA endpoints globally. Qualified under eIDAS via QuoVadis (Swiss subsidiary). Long-term operational since ~2004.")
                .build());

        list.add(TsaServer.builder()
                .id("sectigo-qualified")
                .url("http://timestamp.sectigo.com")
                .name("Sectigo Timestamp")
                .provider("Sectigo Ltd.")
                .country("GB")
                .category("qualified")
                .qualifiedEidas(true)
                .freeTier(true)
                .info("Sectigo (formerly Comodo CA). eIDAS qualified TSP. Extremely high availability, used by millions of code signing certificates. Operational since ~2002 under Comodo brand.")
                .build());

        list.add(TsaServer.builder()
                .id("globalsign-qualified")
                .url("http://timestamp.globalsign.com/tsa/r6advanced1")
                .name("GlobalSign Advanced TSA")
                .provider("GlobalSign nv-sa")
                .country("BE")
                .category("qualified")
                .qualifiedEidas(true)
                .freeTier(true)
                .info("GlobalSign (GMO Internet Group). Belgian QTSP on the EU Trusted List. R6 advanced timestamp service. Operational since ~2006.")
                .build());

        list.add(TsaServer.builder()
                .id("quovadis-qualified")
                .url("http://ts.quovadisglobal.com/eu")
                .name("QuoVadis EU TSA")
                .provider("QuoVadis Trustlink (DigiCert)")
                .country("CH")
                .category("qualified")
                .qualifiedEidas(true)
                .freeTier(true)
                .info("QuoVadis (now DigiCert Switzerland). Swiss QTSP, eIDAS qualified. Issues qualified timestamps for EU digital signatures. Operational since ~2010.")
                .build());

        list.add(TsaServer.builder()
                .id("entrust-qualified")
                .url("http://timestamp.entrust.net/TSS/RFC3161sha2TS")
                .name("Entrust Timestamp (SHA-2)")
                .provider("Entrust Datacard")
                .country("US")
                .category("qualified")
                .qualifiedEidas(true)
                .freeTier(true)
                .info("Entrust (formerly managed by Datacard). Qualified TSP. SHA-2 based RFC 3161 timestamps. Long-term operation since ~2008.")
                .build());

        list.add(TsaServer.builder()
                .id("swisssign-qualified")
                .url("http://tsa.swisssign.net")
                .name("SwissSign TSA")
                .provider("SwissSign AG")
                .country("CH")
                .category("qualified")
                .qualifiedEidas(true)
                .freeTier(true)
                .info("SwissSign (Swiss Post subsidiary). Swiss QTSP and eIDAS-qualified. Issues qualified timestamps. Operational since ~2005. Highly trusted in Swiss/EU regulatory contexts.")
                .build());

        list.add(TsaServer.builder()
                .id("buypass-qualified")
                .url("http://timestamp.buypass.no/2")
                .name("Buypass TSA")
                .provider("Buypass AS")
                .country("NO")
                .category("qualified")
                .qualifiedEidas(true)
                .freeTier(true)
                .info("Buypass (Norwegian QTSP). eIDAS-qualified trust service provider. Widely used in Scandinavian digital signature ecosystems. Operational since ~2008.")
                .build());

        list.add(TsaServer.builder()
                .id("certum-qualified")
                .url("http://time.certum.pl")
                .name("Certum TSA")
                .provider("Asseco Data Systems (Certum)")
                .country("PL")
                .category("qualified")
                .qualifiedEidas(true)
                .freeTier(true)
                .info("Certum (Asseco Group, formerly Unizeto). Polish QTSP on the EU Trusted List. Issues eIDAS-qualified timestamps. Operational since ~2002.")
                .build());

        list.add(TsaServer.builder()
                .id("izenpe-qualified")
                .url("http://tsa.izenpe.com")
                .name("Izenpe TSA")
                .provider("Izenpe S.A.")
                .country("ES")
                .category("qualified")
                .qualifiedEidas(true)
                .freeTier(true)
                .info("Izenpe (Basque Government CA). Spanish QTSP, eIDAS-qualified. Operated by the Basque Government's trust service infrastructure. Operational since ~2006.")
                .build());

        list.add(TsaServer.builder()
                .id("baltstamp-qualified")
                .url("http://tsa.baltstamp.lt")
                .name("BalTstamp TSA")
                .provider("BalTstamp UAB")
                .country("LT")
                .category("qualified")
                .qualifiedEidas(true)
                .freeTier(true)
                .info("BalTstamp (Lithuanian QTSP). eIDAS-qualified TSA used in Baltic states. Registered on the Lithuanian Trusted List. Operational since ~2010.")
                .build());

        list.add(TsaServer.builder()
                .id("sk-qualified")
                .url("http://tsa.sk.ee")
                .name("SK ID Solutions TSA")
                .provider("SK ID Solutions AS")
                .country("EE")
                .category("qualified")
                .qualifiedEidas(true)
                .freeTier(true)
                .info("SK ID Solutions (Estonia). Estonian QTSP, backbone of Estonia's e-ID infrastructure. eIDAS-qualified. Powers Estonian digital signatures and ID-card ecosystem. Operational since ~2002.")
                .build());

        list.add(TsaServer.builder()
                .id("postsignum-qualified")
                .url("http://tsa.postsignum.cz")
                .name("PostSignum TSA")
                .provider("Ceska posta s.p.")
                .country("CZ")
                .category("qualified")
                .qualifiedEidas(true)
                .freeTier(true)
                .info("PostSignum (Czech Post). Czech QTSP on the EU Trusted List. eIDAS-qualified timestamp service. Operational since ~2005.")
                .build());

        list.add(TsaServer.builder()
                .id("microsec-qualified")
                .url("http://tsa.e-szigno.hu")
                .name("Microsec e-Szigno TSA")
                .provider("Microsec Ltd.")
                .country("HU")
                .category("qualified")
                .qualifiedEidas(true)
                .freeTier(true)
                .info("Microsec (Hungarian QTSP). eIDAS-qualified trust service provider. e-Szigno is Hungary's primary qualified TSA. Operational since ~2004.")
                .build());

        list.add(TsaServer.builder()
                .id("atos-qualified")
                .url("http://tsa.atos.net/tsa")
                .name("Atos TSA")
                .provider("Atos SE (Eviden)")
                .country("FR")
                .category("qualified")
                .qualifiedEidas(true)
                .freeTier(true)
                .info("Atos/Eviden (formerly Bull/Evidian). French QTSP, eIDAS-qualified. Major EU IT services provider with long-standing TSA infrastructure.")
                .build());

        // ═══════════════════════════════════════════════════════════════════
        // COMMERCIAL TSA (established CAs, free tier)
        // Operated by recognized Certificate Authorities. Not eIDAS-qualified
        // but widely trusted for code signing and document timestamping.
        // ═══════════════════════════════════════════════════════════════════

        list.add(TsaServer.builder()
                .id("digicert-sha2")
                .url("http://sha256timestamp.ws.symantec.com/sha256/timestamp")
                .name("DigiCert SHA-256 (legacy Symantec)")
                .provider("DigiCert Inc.")
                .country("US")
                .category("commercial")
                .freeTier(true)
                .info("Legacy Symantec/VeriSign SHA-256 endpoint now operated by DigiCert. Still active and widely referenced in older code signing scripts. Operational since ~2013.")
                .build());

        list.add(TsaServer.builder()
                .id("globalsign-standard")
                .url("http://rfc3161timestamp.globalsign.com/advanced")
                .name("GlobalSign RFC 3161")
                .provider("GlobalSign nv-sa")
                .country("BE")
                .category("commercial")
                .freeTier(true)
                .info("GlobalSign standard RFC 3161 timestamp endpoint. Separate from their qualified service. Widely used for code signing. Operational since ~2008.")
                .build());

        list.add(TsaServer.builder()
                .id("comodoca-legacy")
                .url("http://timestamp.comodoca.com")
                .name("Comodo CA Timestamp (legacy)")
                .provider("Sectigo Ltd.")
                .country("GB")
                .category("commercial")
                .freeTier(true)
                .info("Legacy Comodo CA endpoint, now operated by Sectigo. Still functional and widely referenced. Operational since ~2002.")
                .build());

        list.add(TsaServer.builder()
                .id("ssl-com")
                .url("http://ts.ssl.com")
                .name("SSL.com Timestamp")
                .provider("SSL.com")
                .country("US")
                .category("commercial")
                .freeTier(true)
                .info("SSL.com public TSA. Rapidly growing CA with WebTrust and eIDAS audits. Free tier available. Operational since ~2016.")
                .build());

        list.add(TsaServer.builder()
                .id("starfieldtech")
                .url("http://tsa.starfieldtech.com")
                .name("Starfield Technologies TSA")
                .provider("Starfield Technologies (GoDaddy)")
                .country("US")
                .category("commercial")
                .freeTier(true)
                .info("Starfield Technologies (GoDaddy subsidiary). Public TSA endpoint. Used primarily for code signing timestamps. Operational since ~2008.")
                .build());

        list.add(TsaServer.builder()
                .id("usertrust")
                .url("http://timestamp.usertrust.com")
                .name("UserTrust Timestamp")
                .provider("Sectigo (UserTrust Network)")
                .country("US")
                .category("commercial")
                .freeTier(true)
                .info("UserTrust RSA (Sectigo). Alternative Sectigo endpoint under the UserTrust brand. Commonly used in Authenticode signing. Operational since ~2010.")
                .build());

        list.add(TsaServer.builder()
                .id("tsa-safecreative")
                .url("http://tsa.safecreative.org")
                .name("SafeCreative TSA")
                .provider("Safe Creative S.L.")
                .country("ES")
                .category("commercial")
                .freeTier(true)
                .info("Safe Creative (Spanish IP registry). Public TSA for intellectual property timestamping. Oriented toward creative works registration. Operational since ~2012.")
                .build());

        list.add(TsaServer.builder()
                .id("certifytheweb")
                .url("http://timestamp.certifytheweb.com")
                .name("Certify The Web TSA")
                .provider("Webprofusion Pty Ltd")
                .country("AU")
                .category("commercial")
                .freeTier(true)
                .info("Certify The Web (Australian provider). Free public TSA endpoint. Primarily used for ACME/Let's Encrypt certificate management tool's timestamping needs.")
                .build());

        list.add(TsaServer.builder()
                .id("d-trust")
                .url("http://tsa.pki.dfn.de")
                .name("DFN-PKI TSA")
                .provider("DFN-Verein / D-Trust")
                .country("DE")
                .category("commercial")
                .freeTier(true)
                .info("DFN-PKI (German Research Network). Operated with D-Trust (Bundesdruckerei subsidiary). TSA for German academic and research institutions. Operational since ~2007.")
                .build());

        list.add(TsaServer.builder()
                .id("harica")
                .url("http://timestamp.harica.gr")
                .name("HARICA TSA")
                .provider("HARICA (Hellenic Academic and Research Institutions CA)")
                .country("GR")
                .category("commercial")
                .freeTier(true)
                .info("HARICA (Greek academic CA). EU trusted CA with WebTrust audit. Free TSA for academic and research use. Recently became eIDAS-qualified CA. Operational since ~2011.")
                .build());

        list.add(TsaServer.builder()
                .id("wisekey")
                .url("http://timestamping.wisekey.com")
                .name("WISeKey Timestamp")
                .provider("WISeKey SA")
                .country("CH")
                .category("commercial")
                .freeTier(true)
                .info("WISeKey (Swiss cybersecurity firm). Swiss-based CA with OISTE/WISeKey root. Provides timestamping for IoT and enterprise. Operational since ~2012.")
                .build());

        list.add(TsaServer.builder()
                .id("keynectis")
                .url("http://tsa.dhimyotis.com/TSS/HttpTspServer")
                .name("Dhimyotis TSA")
                .provider("Dhimyotis (formerly Keynectis)")
                .country("FR")
                .category("commercial")
                .freeTier(true)
                .info("Dhimyotis (formerly Keynectis/OpenTrust, now part of Certigna). French CA. TSA used in French e-government and EU cross-border services. Operational since ~2006.")
                .build());

        list.add(TsaServer.builder()
                .id("seiko")
                .url("http://tsa.secomtrust.net/tsa/timestamp")
                .name("SECOM Trust Systems TSA")
                .provider("SECOM Trust Systems Co., Ltd.")
                .country("JP")
                .category("commercial")
                .freeTier(true)
                .info("SECOM Trust Systems (Japanese security company). TSA for Asian markets. Part of SECOM Group (major Japanese security conglomerate). Operational since ~2005.")
                .build());

        list.add(TsaServer.builder()
                .id("atstamp")
                .url("http://tsa.atstamp.com/tsa")
                .name("ATStamp TSA")
                .provider("ATStamp S.L.")
                .country("ES")
                .category("commercial")
                .freeTier(true)
                .info("ATStamp (Spanish timestamping service). Focused on certified timestamping for legal compliance in Spain and Latin America. Operational since ~2014.")
                .build());

        list.add(TsaServer.builder()
                .id("firmaprofesional")
                .url("http://tss.firmaprofesional.com:8316/tsa")
                .name("Firma Profesional TSA")
                .provider("Firmaprofesional S.A.")
                .country("ES")
                .category("commercial")
                .qualifiedEidas(true)
                .freeTier(true)
                .info("Firmaprofesional (Spanish QTSP). eIDAS-qualified, part of CaixaBank Group. Provides qualified timestamps for Spanish public administration. Operational since ~2003.")
                .build());

        list.add(TsaServer.builder()
                .id("accv")
                .url("http://tss.accv.es:8318/tsa")
                .name("ACCV TSA")
                .provider("Agencia de Tecnologia y Certificacion Electronica (ACCV)")
                .country("ES")
                .category("qualified")
                .qualifiedEidas(true)
                .freeTier(true)
                .info("ACCV (Valencia regional government CA). eIDAS-qualified Spanish QTSP. Provides qualified timestamps for Valencian public administration and citizens. Operational since ~2004.")
                .build());

        // ═══════════════════════════════════════════════════════════════════
        // COMMUNITY / OPEN-SOURCE TSA
        // Free, publicly available. Best-effort uptime, no SLA guarantees.
        // Suitable for testing and non-critical use.
        // ═══════════════════════════════════════════════════════════════════

        list.add(TsaServer.builder()
                .id("freetsa")
                .url("https://freetsa.org/tsr")
                .name("FreeTSA.org")
                .provider("FreeTSA Project")
                .country("DE")
                .category("community")
                .freeTier(true)
                .info("FreeTSA.org (community-run). Free RFC 3161 TSA operated by volunteers. No SLA, best-effort availability. Popular for testing and open-source projects. Operational since ~2013.")
                .build());

        list.add(TsaServer.builder()
                .id("zeitstempel")
                .url("http://zeitstempel.dfn.de")
                .name("DFN Zeitstempel")
                .provider("DFN-Verein")
                .country("DE")
                .category("community")
                .freeTier(true)
                .info("DFN Zeitstempel (German Research Network). Free TSA for academic use. Part of the DFN-PKI infrastructure. Operational since ~2005.")
                .build());

        list.add(TsaServer.builder()
                .id("opentsa")
                .url("http://tsa.opentsa.org")
                .name("OpenTSA")
                .provider("OpenTSA Project")
                .country("DE")
                .category("community")
                .freeTier(true)
                .info("OpenTSA (open-source timestamping). Community-operated TSA based on open-source software. Designed for testing and evaluation. Best-effort availability.")
                .build());

        list.add(TsaServer.builder()
                .id("signfiles")
                .url("http://tsa.signfiles.com/tsa/get.aspx")
                .name("SignFiles TSA")
                .provider("SignFiles.com")
                .country("US")
                .category("community")
                .freeTier(true)
                .info("SignFiles.com free TSA. Community-oriented timestamp service. Primarily used for testing digital signature workflows. Limited availability guarantees.")
                .build());

        list.add(TsaServer.builder()
                .id("infocert")
                .url("http://timestamp.infocert.it/TSS/HttpTspServer")
                .name("InfoCert TSA")
                .provider("InfoCert S.p.A.")
                .country("IT")
                .category("qualified")
                .qualifiedEidas(true)
                .freeTier(true)
                .info("InfoCert (Tinexta Group). Italian QTSP, largest qualified trust service provider in Italy. eIDAS-qualified timestamps. Widely used in Italian e-invoicing (FatturaPA). Operational since ~2006.")
                .build());

        list.add(TsaServer.builder()
                .id("aruba")
                .url("http://timestamp.arubapec.it/TSS/HttpTspServer")
                .name("Aruba PEC TSA")
                .provider("Aruba PEC S.p.A.")
                .country("IT")
                .category("qualified")
                .qualifiedEidas(true)
                .freeTier(true)
                .info("Aruba PEC (Aruba Group). Italian QTSP and PEC (certified email) provider. eIDAS-qualified timestamps. Major Italian trust service provider. Operational since ~2008.")
                .build());

        list.add(TsaServer.builder()
                .id("intesigroup")
                .url("http://tsa.intesigroup.com")
                .name("Intesi Group TSA")
                .provider("Intesi Group S.p.A.")
                .country("IT")
                .category("qualified")
                .qualifiedEidas(true)
                .freeTier(true)
                .info("Intesi Group (Italian QTSP). eIDAS-qualified. Specializes in remote digital signatures and timestamping for enterprise. Operational since ~2010.")
                .build());

        list.add(TsaServer.builder()
                .id("siths")
                .url("http://timestamp.identrust.com")
                .name("IdenTrust TSA")
                .provider("IdenTrust Inc.")
                .country("US")
                .category("commercial")
                .freeTier(true)
                .info("IdenTrust (HID Global). Major CA that cross-signs Let's Encrypt root. TSA service for enterprise and government. Operational since ~2010.")
                .build());

        list.add(TsaServer.builder()
                .id("catcert")
                .url("http://psis.catcert.net/psis/catcert/tsp")
                .name("CATCert TSA")
                .provider("Consorci AOC (CATCert)")
                .country("ES")
                .category("qualified")
                .qualifiedEidas(true)
                .freeTier(true)
                .info("CATCert (Catalonia government CA). eIDAS-qualified Spanish QTSP. Provides qualified timestamps for Catalan public administration. Operational since ~2003.")
                .build());

        // ═══════════════════════════════════════════════════════════════════
        // GOVERNMENT / NATIONAL TSA
        // Operated by government bodies or national PKI infrastructure.
        // ═══════════════════════════════════════════════════════════════════

        list.add(TsaServer.builder()
                .id("e-boks-dk")
                .url("http://timestamp.apple.com/ts01")
                .name("Apple Timestamp")
                .provider("Apple Inc.")
                .country("US")
                .category("commercial")
                .freeTier(true)
                .info("Apple Inc. public TSA. Used for Apple code signing and notarization. High availability enterprise infrastructure. Operational since ~2012.")
                .build());

        list.add(TsaServer.builder()
                .id("microsoft-tsa")
                .url("http://timestamp.acs.microsoft.com")
                .name("Microsoft Authenticode TSA")
                .provider("Microsoft Corporation")
                .country("US")
                .category("commercial")
                .freeTier(true)
                .info("Microsoft Authenticode timestamp service. Primary TSA for Windows code signing. Extremely high availability. Operational since ~2005.")
                .build());

        list.add(TsaServer.builder()
                .id("microsoft-rfc3161")
                .url("http://timestamp.acs.microsoft.com/rfc3161")
                .name("Microsoft RFC 3161 TSA")
                .provider("Microsoft Corporation")
                .country("US")
                .category("commercial")
                .freeTier(true)
                .info("Microsoft RFC 3161 compliant timestamp endpoint. For modern code signing with SHA-2. Very high availability. Operational since ~2015.")
                .build());

        list.add(TsaServer.builder()
                .id("datum-tsa")
                .url("http://ts.quovadisglobal.com/ch")
                .name("QuoVadis CH TSA")
                .provider("QuoVadis Trustlink (DigiCert)")
                .country("CH")
                .category("qualified")
                .qualifiedEidas(true)
                .freeTier(true)
                .info("QuoVadis Swiss TSA endpoint. Alternative to the EU endpoint. Swiss-regulated qualified timestamp service. Operational since ~2010.")
                .build());

        list.add(TsaServer.builder()
                .id("e-tugra")
                .url("http://ts.e-tugra.com")
                .name("E-Tugra TSA")
                .provider("E-Tugra EBG A.S.")
                .country("TR")
                .category("commercial")
                .freeTier(true)
                .info("E-Tugra (Turkish CA). Provides timestamping for Turkish electronic signature requirements. WebTrust audited. Operational since ~2013.")
                .build());

        list.add(TsaServer.builder()
                .id("kpn-tsa")
                .url("http://tsa.kpn.com/tsa")
                .name("KPN TSA")
                .provider("KPN B.V.")
                .country("NL")
                .category("qualified")
                .qualifiedEidas(true)
                .freeTier(true)
                .info("KPN (Dutch telecom). Netherlands QTSP, eIDAS-qualified. Provides qualified timestamps for Dutch government and enterprise. Operational since ~2006.")
                .build());

        list.add(TsaServer.builder()
                .id("tsa-belgium")
                .url("http://tsa.belgium.be/connect")
                .name("Belgian Federal TSA")
                .provider("FPS BOSA (Belgian Government)")
                .country("BE")
                .category("government")
                .qualifiedEidas(true)
                .freeTier(true)
                .info("Belgian Federal Public Service. Government-operated TSA for Belgian e-government services. eIDAS-qualified. Operational since ~2008.")
                .build());

        list.add(TsaServer.builder()
                .id("fnmt-tsa")
                .url("http://tsa.fnmt.es/tsa/tss")
                .name("FNMT TSA")
                .provider("Fabrica Nacional de Moneda y Timbre (FNMT-RCM)")
                .country("ES")
                .category("government")
                .qualifiedEidas(true)
                .freeTier(true)
                .info("FNMT-RCM (Spanish Royal Mint). Government-operated QTSP. Issues Spain's electronic DNI certificates and qualified timestamps. Operational since ~2004.")
                .build());

        list.add(TsaServer.builder()
                .id("ancert-tsa")
                .url("http://tsa.ancert.com/tsa")
                .name("ANCERT TSA")
                .provider("Agencia Notarial de Certificacion (ANCERT)")
                .country("ES")
                .category("qualified")
                .qualifiedEidas(true)
                .freeTier(true)
                .info("ANCERT (Spanish Notary Association CA). eIDAS-qualified QTSP. Provides timestamps for Spanish notarial acts and legal documents. Operational since ~2004.")
                .build());

        // ═══════════════════════════════════════════════════════════════════
        // ACADEMIC / RESEARCH TSA
        // ═══════════════════════════════════════════════════════════════════

        list.add(TsaServer.builder()
                .id("rediris-tsa")
                .url("http://tsa.rediris.es/tsa")
                .name("RedIRIS TSA")
                .provider("RedIRIS (Spanish Research Network)")
                .country("ES")
                .category("academic")
                .freeTier(true)
                .info("RedIRIS (Spanish National Research and Education Network). Academic TSA for Spanish universities and research institutions. Operated by Red.es. Operational since ~2007.")
                .build());

        list.add(TsaServer.builder()
                .id("ntp-timestamp")
                .url("http://tsa.aloaha.com")
                .name("Aloaha TSA")
                .provider("Aloaha Software")
                .country("DE")
                .category("community")
                .freeTier(true)
                .info("Aloaha Software (German developer). Free TSA for testing and development. Part of the Aloaha PDF signer toolchain. Best-effort availability.")
                .build());

        // ═══════════════════════════════════════════════════════════════════
        // MORE COMMERCIAL / REGIONAL TSA
        // ═══════════════════════════════════════════════════════════════════

        list.add(TsaServer.builder()
                .id("certsign-ro")
                .url("http://tsa.certsign.ro")
                .name("certSIGN TSA")
                .provider("certSIGN S.A.")
                .country("RO")
                .category("qualified")
                .qualifiedEidas(true)
                .freeTier(true)
                .info("certSIGN (Romanian QTSP). eIDAS-qualified trust service provider. Largest Romanian CA. Powers Romanian e-government digital signatures. Operational since ~2005.")
                .build());

        list.add(TsaServer.builder()
                .id("disig-sk")
                .url("http://tsa.disig.sk/tsa/tss")
                .name("Disig TSA")
                .provider("Disig a.s.")
                .country("SK")
                .category("qualified")
                .qualifiedEidas(true)
                .freeTier(true)
                .info("Disig (Slovak QTSP). eIDAS-qualified. Major trust service provider for Slovak government and banking sector. Operational since ~2006.")
                .build());

        list.add(TsaServer.builder()
                .id("halcom-si")
                .url("http://tsa.halcom.si")
                .name("Halcom TSA")
                .provider("Halcom d.d.")
                .country("SI")
                .category("qualified")
                .qualifiedEidas(true)
                .freeTier(true)
                .info("Halcom (Slovenian QTSP). eIDAS-qualified. Leading Slovenian CA for banking and e-government. Operational since ~2004.")
                .build());

        list.add(TsaServer.builder()
                .id("nica-tsa")
                .url("http://tsa.sinpe.fi.cr/tsaHttp/")
                .name("SINPE TSA (Costa Rica)")
                .provider("Banco Central de Costa Rica")
                .country("CR")
                .category("government")
                .freeTier(true)
                .info("SINPE (Costa Rica Central Bank). Government-operated TSA for Costa Rican digital signatures (Firma Digital). Operational since ~2010.")
                .build());

        list.add(TsaServer.builder()
                .id("sigen-ar")
                .url("http://tsa.sigen.gov.ar/tsa/tss")
                .name("SIGEN TSA (Argentina)")
                .provider("Sindicatura General de la Nacion")
                .country("AR")
                .category("government")
                .freeTier(true)
                .info("SIGEN (Argentine government audit agency). Government-operated TSA for Argentine digital signature framework. Operational since ~2009.")
                .build());

        list.add(TsaServer.builder()
                .id("serpro-br")
                .url("http://act.serpro.gov.br/tss")
                .name("Serpro TSA (Brazil)")
                .provider("Serpro (Brazilian Federal Data Processing Service)")
                .country("BR")
                .category("government")
                .freeTier(true)
                .info("Serpro (Brazilian government IT). Government-operated TSA under ICP-Brasil PKI. Part of Brazil's national digital signature infrastructure. Operational since ~2008.")
                .build());

        list.add(TsaServer.builder()
                .id("nimc-tsa")
                .url("http://tsa.pki.gob.mx/tsa")
                .name("Mexico PKI TSA")
                .provider("SAT (Mexican Tax Administration)")
                .country("MX")
                .category("government")
                .freeTier(true)
                .info("SAT/PKI Mexico. Government-operated TSA for Mexican digital signature (e.firma) infrastructure. Used for electronic invoicing (CFDI). Operational since ~2010.")
                .build());

        list.add(TsaServer.builder()
                .id("netlock-hu")
                .url("http://tsa.netlock.hu")
                .name("NetLock TSA")
                .provider("NetLock Kft.")
                .country("HU")
                .category("qualified")
                .qualifiedEidas(true)
                .freeTier(true)
                .info("NetLock (Hungarian CA). eIDAS-qualified QTSP. One of Hungary's original qualified trust service providers. Operational since ~2003.")
                .build());

        list.add(TsaServer.builder()
                .id("trustedbird")
                .url("http://tsa.trustedbird.com")
                .name("TrustedBird TSA")
                .provider("TrustedBird / Cassidian")
                .country("FR")
                .category("community")
                .freeTier(true)
                .info("TrustedBird project (originally Cassidian/EADS). Open-source email security project with public TSA. Best-effort availability.")
                .build());

        this.servers = Collections.unmodifiableList(list);
    }

    public List<TsaServer> getAllServers() {
        return servers;
    }

    public TsaServer getById(String id) {
        for (TsaServer server : servers) {
            if (server.getId().equals(id)) {
                return server;
            }
        }
        return null;
    }

    public int size() {
        return servers.size();
    }
}
