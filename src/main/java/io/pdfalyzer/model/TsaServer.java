package io.pdfalyzer.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TsaServer {
    private String id;
    private String url;
    private String name;
    private String provider;
    private String country;
    private String category; // "qualified", "commercial", "community", "academic", "government"
    private String info;
    private boolean qualifiedEidas;
    private boolean freeTier;

    // Probe status (mutable, updated by background probing)
    @Builder.Default
    private String status = "unknown"; // "online", "offline", "error", "unknown", "probing"
    private Long responseTimeMs;
    private String lastProbeError;
    private Long lastProbeAt;
}
