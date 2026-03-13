package io.pdfalyzer.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Describes the encryption state of a loaded PDF session.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EncryptionInfo {

    /** True if the PDF dictionary has an Encrypt entry. */
    private boolean encrypted;

    /**
     * True when the user-open password is non-empty and the document could not
     * be opened without it.  A password prompt should be shown to the user.
     */
    private boolean requiresPassword;

    /** True when the current access token allows in-place modification. */
    private boolean canModify;

    /** True when the current access token allows printing. */
    private boolean canPrint;

    /** True when the current access token allows content extraction. */
    private boolean canExtractContent;

    /**
     * Human-readable algorithm string, e.g. "RC4-40", "RC4-128", "AES-128",
     * "AES-256".  Null when {@link #encrypted} is false.
     */
    private String algorithm;

    /** PDF encryption dictionary V value (0 when not encrypted). */
    private int version;

    /** PDF encryption dictionary R value (0 when not encrypted). */
    private int revision;

    /**
     * Key length in bits (40, 128, 256 …).  0 when not encrypted.
     */
    private int keyBits;

    /**
     * Which password was used to open the document:
     * <ul>
     *   <li>"none"       – document is not encrypted</li>
     *   <li>"empty-user" – opened with the empty string as user password</li>
     *   <li>"user"       – opened with an explicit user password</li>
     *   <li>"owner"      – opened with the owner password</li>
     * </ul>
     */
    private String passwordType;
}
