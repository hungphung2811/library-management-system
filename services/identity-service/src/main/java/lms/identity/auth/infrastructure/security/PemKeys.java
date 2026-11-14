package lms.identity.auth.infrastructure.security;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/** Đọc/ghi khóa RSA dạng PEM (PKCS#8 cho private, X.509 cho public). */
public final class PemKeys {

    private PemKeys() {
    }

    public static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Không sinh được khóa RSA", e);
        }
    }

    public static RSAPrivateKey parsePrivate(String pem) {
        try {
            return (RSAPrivateKey) KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(decode(pem)));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Private key PEM (PKCS#8) không hợp lệ", e);
        }
    }

    public static RSAPublicKey parsePublic(String pem) {
        try {
            return (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(decode(pem)));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Public key PEM (X.509) không hợp lệ", e);
        }
    }

    public static String toPem(RSAPrivateKey key) {
        return wrap("PRIVATE KEY", key.getEncoded());
    }

    public static String toPem(RSAPublicKey key) {
        return wrap("PUBLIC KEY", key.getEncoded());
    }

    private static byte[] decode(String pem) {
        String body = pem.replaceAll("-----(BEGIN|END) [A-Z ]+-----", "").replaceAll("\\s", "");
        return Base64.getDecoder().decode(body);
    }

    private static String wrap(String label, byte[] der) {
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der);
        return "-----BEGIN " + label + "-----\n" + base64 + "\n-----END " + label + "-----\n";
    }
}
