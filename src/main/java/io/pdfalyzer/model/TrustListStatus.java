package io.pdfalyzer.model;

import java.util.ArrayList;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrustListStatus {
    private String listType;             // EUTL, AATL
    private String status;               // NOT_LOADED, LOADING, LOADED, ERROR
    private String statusMessage;
    @Builder.Default
    private List<String> loadedCountries = new ArrayList<>();
    private int totalTrustAnchors;
    private String loadedAt;             // ISO timestamp
    private String listNextUpdate;       // from TSL header
    private long ageMinutes;
    // Progress tracking
    private String currentlyFetching;    // e.g. "AT - Austria"
    private int fetchedCount;
    private int totalToFetch;

    // Per-country detail for UI
    @Builder.Default
    private List<String> failedCountries = new ArrayList<>();   // countries whose TSL fetch failed
    @Builder.Default
    private List<String> skippedCountries = new ArrayList<>();  // countries not in LOTL
}
