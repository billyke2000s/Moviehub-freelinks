package local.tv.home;

import java.io.IOException;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;

/** Detached signature authenticates the exact package bytes before JSON parsing or evaluation. */
final class ProviderSignature {

    static final int MAX_PACKAGE = 1024 * 1024;

    private ProviderSignature() {}

    static void verify(byte[] payload, byte[] signature, byte[] publicKey) throws Exception {
        if (payload.length == 0 || payload.length > MAX_PACKAGE || signature.length > 1024) {
            throw new IOException("Provider package size rejected");
        }
        PublicKey key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(publicKey));
        if (!(key instanceof RSAPublicKey) || ((RSAPublicKey) key).getModulus().bitLength() < 3072) {
            throw new GeneralSecurityException("Provider key rejected");
        }
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(key);
        verifier.update(payload);
        if (!verifier.verify(signature)) throw new GeneralSecurityException("Provider signature rejected");
    }
}
