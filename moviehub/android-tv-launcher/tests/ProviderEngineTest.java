package local.tv.home;

import java.nio.file.*;
import java.security.*;

/** Desktop JVM tests execute the same interpreter and signature verifier used on Android. */
public final class ProviderEngineTest {

    private static int checks;

    private static void check(boolean ok) {
        if (!ok) throw new AssertionError();
        checks++;
    }

    private static void rejected(Attempt action) throws Exception {
        try {
            action.run();
            throw new AssertionError("Accepted invalid input");
        } catch (java.io.IOException | GeneralSecurityException expected) {
            checks++;
        }
    }

    interface Attempt {
        void run() throws Exception;
    }

    public static void main(String[] args) throws Exception {
        String source = Files.readString(Path.of(args[0]));
        // This MUST be the first interpreter call in this fresh JVM: Rhino caches SPI discovery.
        Thread thread = Thread.currentThread();
        ClassLoader original = thread.getContextClassLoader();
        ClassLoader binderLike = new ClassLoader(null) {};
        try {
            thread.setContextClassLoader(binderLike);
            check(ProviderScript.evaluate(source, "metadata", "").contains("freelivesports"));
            check(thread.getContextClassLoader() == binderLike);
        } finally {
            thread.setContextClassLoader(original);
        }

        check(
            ProviderScript.evaluate(
                source,
                "catalogue",
                "[{\"_id\":\"a\",\"name\":\"Sport\",\"url\":\"https://example.com/a.m3u8\"}]"
            ).contains("Sport")
        );
        check(
            !ProviderScript.evaluate(source, "resolve", "https://example.com/a.m3u8?cb=[CB]").contains("[CB]")
        );
        check(
            ProviderScript.evaluate(
                "function invoke(){return {java:typeof java, packages:typeof Packages, network:typeof fetch};}",
                "",
                ""
            ).equals("{\"java\":\"undefined\",\"packages\":\"undefined\",\"network\":\"undefined\"}")
        );
        rejected(() ->
            ProviderScript.evaluate(
                "function invoke(){return java.lang.Runtime.getRuntime().exec('true');}",
                "",
                ""
            )
        );
        rejected(() ->
            ProviderScript.evaluate(
                "function invoke(){while(true) {try { for(var i=0;i<100000;i++){} } catch(e) {} }}",
                "",
                ""
            )
        );
        rejected(() -> ProviderScript.evaluate(source, "shell", ""));
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(3072);
        KeyPair keys = generator.generateKeyPair();
        byte[] payload = "fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(keys.getPrivate());
        signer.update(payload);
        byte[] signature = signer.sign();
        ProviderSignature.verify(payload, signature, keys.getPublic().getEncoded());
        checks++;
        byte[] altered = payload.clone();
        altered[0] ^= 1;
        rejected(() -> ProviderSignature.verify(altered, signature, keys.getPublic().getEncoded()));
        signature[0] ^= 1;
        rejected(() -> ProviderSignature.verify(payload, signature, keys.getPublic().getEncoded()));
        rejected(() ->
            ProviderSignature.verify(
                new byte[ProviderSignature.MAX_PACKAGE + 1],
                signature,
                keys.getPublic().getEncoded()
            )
        );
        generator.initialize(2048);
        KeyPair weak = generator.generateKeyPair();
        rejected(() -> ProviderSignature.verify(payload, signature, weak.getPublic().getEncoded()));
        check(
            ProviderUrls.playback("https://example.com/a.m3u8?ua=Mozilla Linux").endsWith(
                "ua=Mozilla%20Linux"
            )
        );
        rejected(() -> ProviderUrls.playback("http://example.com/a.m3u8"));
        rejected(() -> ProviderUrls.playback("https://user:pass@example.com/a.m3u8"));
        rejected(() -> ProviderUrls.playback("https://example.com/a.m3u8?ua=bad\r\nheader"));
        check(ProviderUrls.playback("https://example.com/a.m3u8?macro={GPP}").contains("%7BGPP%7D"));
        byte[] segment = new byte[ProviderSegments.HEAD];
        for (int packet = 0; packet < 10; packet++) segment[42 + packet * 188] = 0x47;
        check(ProviderSegments.prefix(segment, segment.length) == 42);
        segment[0] = 0x47;
        check(ProviderSegments.prefix(segment, segment.length) == 0);
        check(ProviderSegments.prefix(new byte[1024], 1024) == 0);
        System.out.println(checks + " provider engine checks passed");
    }
}
