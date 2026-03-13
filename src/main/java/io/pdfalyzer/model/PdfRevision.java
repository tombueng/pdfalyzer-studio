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
public class PdfRevision {
    private int revisionIndex;           // 1-based
    private long startOffset;
    private long endOffset;              // position after %%EOF + line endings
    private long eofPosition;            // byte offset of the %% in %%EOF
    private long startxrefValue;         // the value after "startxref"
    @Builder.Default
    private List<String> associatedSignatureFields = new ArrayList<>();
    private long size;                   // endOffset - startOffset
}
