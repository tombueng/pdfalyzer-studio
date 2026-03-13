package io.pdfalyzer.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import io.pdfalyzer.model.CscProvider;
import lombok.extern.slf4j.Slf4j;

/**
 * Registry of known CSC (Cloud Signature Consortium) remote signing providers.
 * Ships with well-known providers (metadata only, no credentials).
 * Users can add custom providers at runtime.
 */
@Service
@Slf4j
public class CscProviderRegistry {

    private final Map<String, CscProvider> providers = new ConcurrentHashMap<>();

    public CscProviderRegistry() {
        registerBuiltInProviders();
    }

    public List<CscProvider> listAll() {
        var list = new ArrayList<>(providers.values());
        list.sort((a, b) -> {
            if (a.isBuiltIn() != b.isBuiltIn()) return a.isBuiltIn() ? -1 : 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });
        return Collections.unmodifiableList(list);
    }

    public CscProvider get(String id) {
        return providers.get(id);
    }

    public CscProvider addOrUpdate(CscProvider provider) {
        if (provider.getId() == null || provider.getId().isBlank()) {
            provider.setId("custom-" + System.currentTimeMillis());
        }
        providers.put(provider.getId(), provider);
        log.info("CSC provider registered: {} ({})", provider.getName(), provider.getId());
        return provider;
    }

    public boolean remove(String id) {
        var removed = providers.remove(id);
        if (removed != null && removed.isBuiltIn()) {
            // Re-add built-in providers if accidentally removed
            providers.put(id, removed);
            return false;
        }
        return removed != null;
    }

    private void registerBuiltInProviders() {
        // SSL.com eSigner — best sandbox, free test credentials
        register(CscProvider.builder()
                .id("sslcom")
                .name("SSL.com eSigner")
                .headquarters("US")
                .baseUrl("https://cs.ssl.com/csc/v0")
                .tokenUrl("https://login.ssl.com/oauth2/token")
                .scalLevels("SCAL1,SCAL2")
                .apiVersion("v0")
                .description("SSL.com eSigner cloud signing. Free sandbox available with demo credentials.")
                .docsUrl("https://www.ssl.com/guide/esigner-demo-credentials-and-certificates/")
                .builtIn(true)
                .build());

        // SSL.com eSigner Sandbox
        register(CscProvider.builder()
                .id("sslcom-sandbox")
                .name("SSL.com eSigner (Sandbox)")
                .headquarters("US")
                .baseUrl("https://cs-try.ssl.com/csc/v0")
                .tokenUrl("https://oauth-sandbox.ssl.com/oauth2/token")
                .scalLevels("SCAL1,SCAL2")
                .apiVersion("v0")
                .description("SSL.com eSigner sandbox for testing. Use demo credentials from docs.")
                .docsUrl("https://www.ssl.com/guide/esigner-demo-credentials-and-certificates/")
                .builtIn(true)
                .build());

        // DigiCert Document Trust Manager
        register(CscProvider.builder()
                .id("digicert")
                .name("DigiCert Document Trust Manager")
                .headquarters("US / Switzerland")
                .baseUrl("https://one.digicert.com/documentmanager/csc/v1")
                .tokenUrl("https://one.digicert.com/oauth2/token")
                .scalLevels("SCAL1,SCAL2")
                .apiVersion("v1")
                .description("DigiCert/QuoVadis cloud signing with AATL-listed roots. Qualified in EU.")
                .docsUrl("https://dev.digicert.com/document-trust-api.html")
                .builtIn(true)
                .build());

        // DigiCert Demo
        register(CscProvider.builder()
                .id("digicert-demo")
                .name("DigiCert Document Trust (Demo)")
                .headquarters("US / Switzerland")
                .baseUrl("https://demo.one.digicert.com/documentmanager/csc/v1")
                .tokenUrl("https://demo.one.digicert.com/oauth2/token")
                .scalLevels("SCAL1,SCAL2")
                .apiVersion("v1")
                .description("DigiCert Document Trust Manager demo/sandbox environment.")
                .docsUrl("https://dev.digicert.com/document-trust-api.html")
                .builtIn(true)
                .build());

        // ZealiD
        register(CscProvider.builder()
                .id("zealid")
                .name("ZealiD")
                .headquarters("Lithuania")
                .baseUrl("https://core-hermes.zealid.com/csc/v1")
                .scalLevels("SCAL2")
                .apiVersion("v1")
                .description("Mobile-first qualified signing. Biometric auth via smartphone app.")
                .docsUrl("https://developer.zealid.com/docs/csc-api-in-detail")
                .builtIn(true)
                .build());

        // ZealiD Test
        register(CscProvider.builder()
                .id("zealid-test")
                .name("ZealiD (Test)")
                .headquarters("Lithuania")
                .baseUrl("https://hermes-dev.zealid.com/api/v3.0/csc/v1")
                .scalLevels("SCAL2")
                .apiVersion("v1")
                .description("ZealiD development/test environment.")
                .docsUrl("https://developer.zealid.com/docs/csc-api-in-detail")
                .builtIn(true)
                .build());

        // itsme (Belgian Mobile ID)
        register(CscProvider.builder()
                .id("itsme")
                .name("itsme (Belgian Mobile ID)")
                .headquarters("Belgium")
                .baseUrl("https://sign.itsme.services/csc/v1")
                .scalLevels("SCAL2")
                .apiVersion("v1")
                .description("Belgian mobile ID. Batch signing up to 70 documents. CSC v1 compliant.")
                .docsUrl("https://belgianmobileid.github.io/doc/QES-CSC/")
                .builtIn(true)
                .build());

        // itsme E2E
        register(CscProvider.builder()
                .id("itsme-e2e")
                .name("itsme (E2E Test)")
                .headquarters("Belgium")
                .baseUrl("https://sign.e2e.itsme.services/csc/v1")
                .scalLevels("SCAL2")
                .apiVersion("v1")
                .description("itsme end-to-end test environment.")
                .docsUrl("https://belgianmobileid.github.io/doc/QES-CSC/")
                .builtIn(true)
                .build());

        // Swisscom Trust Services
        register(CscProvider.builder()
                .id("swisscom")
                .name("Swisscom Trust Services")
                .headquarters("Switzerland")
                .baseUrl("https://ais.swisscom.com/AIS-Server/rs/v1.0")
                .tokenUrl("https://auth.trustservices.swisscom.com/oauth2/token")
                .scalLevels("SCAL1,SCAL2")
                .apiVersion("v1")
                .description("Swiss qualified signing (ZertES). Mobile ID authentication. High throughput.")
                .docsUrl("https://github.com/SwisscomTrustServices/AIS-Postman-Samples")
                .builtIn(true)
                .build());

        // InfoCert — base URL requires registration; set via custom provider entry
        register(CscProvider.builder()
                .id("infocert")
                .name("InfoCert")
                .headquarters("Italy")
                .scalLevels("SCAL1,SCAL2")
                .apiVersion("v2")
                .description("Largest EU QTSP by volume. SPID integration for Italian digital identity. Base URL requires registration — configure via custom provider.")
                .docsUrl("https://developers.infocert.digital/e-signature-and-e-sealing/csc-api/")
                .builtIn(true)
                .build());

        // Namirial — base URL requires registration; set via custom provider entry
        register(CscProvider.builder()
                .id("namirial")
                .name("Namirial")
                .headquarters("Italy")
                .scalLevels("SCAL1,SCAL2")
                .apiVersion("v2")
                .description("Document workflow + remote signing platform. EU qualified. Base URL requires registration — configure via custom provider.")
                .docsUrl("https://confluence.namirial.com/display/eSign/API+Documentation")
                .builtIn(true)
                .build());

        // D-Trust (Bundesdruckerei) — sign-me service
        register(CscProvider.builder()
                .id("dtrust")
                .name("D-Trust (Bundesdruckerei)")
                .headquarters("Germany")
                .baseUrl("https://www.sign-me.de/csc/v1")
                .tokenUrl("https://www.sign-me.de/oauth2/token")
                .scalLevels("SCAL1,SCAL2")
                .apiVersion("v1")
                .description("German Federal Printing Office sign-me service. Strong in government and healthcare.")
                .builtIn(true)
                .build());

        log.info("Registered {} built-in CSC providers", providers.size());
    }

    private void register(CscProvider provider) {
        providers.put(provider.getId(), provider);
    }
}
