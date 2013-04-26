package com.intel.dcsg.cpg.crypto;

/**
 * Representation of a single SHA1 Digest. An SHA1 Digest is a 20-byte value.
 * 
 * Implementation is flexible about the MD5 input format
 * and allows spaces, colons, uppercase and lowercase characters
 * 
 * @since 0.5.4
 * @author jbuhacoff
 */
public class Sha1Digest extends AbstractMessageDigest {
    public Sha1Digest() {
        super("SHA-1", 20);
    }
    
    public Sha1Digest(byte[] value) {
        this();
        setBytes(value);
    }
    
    public Sha1Digest(String hex) {
        this();
        setHex(hex);
    }
    
    public static Sha1Digest valueOf(byte[] message) {
        return valueOf(Sha1Digest.class, message);
    }

}
