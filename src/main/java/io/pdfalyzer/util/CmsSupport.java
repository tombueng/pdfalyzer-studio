package io.pdfalyzer.util;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import org.bouncycastle.asn1.ASN1InputStream;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.cms.ContentInfo;
import org.bouncycastle.cms.CMSException;
import org.bouncycastle.cms.CMSSignedData;

import lombok.experimental.UtilityClass;

/**
 * Helpers for reading CMS/PKCS#7 SignedData structures from raw bytes.
 */
@UtilityClass
public class CmsSupport {

    /**
     * Parse a CMS {@code SignedData} structure from bytes that may contain
     * trailing padding.
     *
     * <p>PDF signature {@code /Contents} strings are hex blobs zero-padded to a
     * reserved length, so the DER object is followed by filler bytes. The
     * {@link CMSSignedData#CMSSignedData(byte[])} constructor in BouncyCastle
     * 1.85+ rejects such trailing bytes with {@code "IOException reading
     * content"}. Reading a single, self-delimiting ASN.1 object and discarding
     * the remainder parses the SignedData correctly regardless of padding.
     *
     * @param der CMS SignedData bytes, optionally followed by padding
     * @return the parsed {@link CMSSignedData}
     * @throws CMSException if the bytes do not contain a CMS structure
     * @throws IOException  if the ASN.1 content cannot be read
     */
    public CMSSignedData parseSignedData(byte[] der) throws CMSException, IOException {
        try (ASN1InputStream ais = new ASN1InputStream(new ByteArrayInputStream(der))) {
            ASN1Primitive obj = ais.readObject();
            if (obj == null) {
                throw new CMSException("No ASN.1 content found in signature bytes");
            }
            return new CMSSignedData(ContentInfo.getInstance(obj));
        }
    }
}
