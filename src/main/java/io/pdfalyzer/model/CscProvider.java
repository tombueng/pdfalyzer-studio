package io.pdfalyzer.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CscProvider {
    private String id;
    private String name;
    private String headquarters;
    private String baseUrl;         // CSC API base (e.g. https://cs-try.ssl.com/csc/v0)
    private String authUrl;         // OAuth2 authorization endpoint (if separate from baseUrl)
    private String tokenUrl;        // OAuth2 token endpoint
    private String clientId;
    private String clientSecret;
    private String scalLevels;      // "SCAL1", "SCAL2", "SCAL1,SCAL2"
    private String description;
    private String docsUrl;         // Link to provider's developer docs

    @Builder.Default
    private boolean builtIn = false;   // true = shipped with app, false = user-added

    @Builder.Default
    private String apiVersion = "v2"; // CSC API version

    @Builder.Default
    private String status = "unknown"; // unknown, connected, error
    private String statusMessage;
}
