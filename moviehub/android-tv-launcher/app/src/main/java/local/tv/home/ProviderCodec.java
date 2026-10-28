package local.tv.home;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.zip.*;

/** Bounded pure-data Node/Web compatibility. No files, networking or app state. */
final class ProviderCodec {

    private ProviderCodec() {}

    static String call(String operation, String input) throws Exception {
        if (input.length() > ProviderScript.MAX_BYTES) throw new IOException("Codec input limit");
        byte[] bytes = input.getBytes(StandardCharsets.ISO_8859_1);
        switch (operation) {
            case "aesGcmEncrypt":
                String[] fields = input.split("\\n", -1);
                if (fields.length != 4) throw new IOException("Invalid AES-GCM fields");
                byte[][] decoded = new byte[4][];
                for (int i = 0; i < 4; i++) decoded[i] = Base64.getDecoder().decode(fields[i]);
                if (
                    decoded[0].length != 32 ||
                    decoded[1].length != 12 ||
                    decoded[2].length > 8192 ||
                    decoded[3].length > 8192
                ) throw new IOException("Invalid AES-GCM bounds");
                javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(
                    javax.crypto.Cipher.ENCRYPT_MODE,
                    new javax.crypto.spec.SecretKeySpec(decoded[0], "AES"),
                    new javax.crypto.spec.GCMParameterSpec(128, decoded[1])
                );
                cipher.updateAAD(decoded[2]);
                return new String(cipher.doFinal(decoded[3]), StandardCharsets.ISO_8859_1);
            case "ipFamily":
                try {
                    literal(input);
                    return input.contains(":") ? "6" : "4";
                } catch (Exception e) {
                    return "0";
                }
            case "ipMaskMatch":
                String[] rule = input.split("\\n", -1);
                if (rule.length != 3) throw new IOException("Invalid address rule");
                byte[] address = literal(rule[0]),
                    network = literal(rule[1]);
                int prefix = Integer.parseInt(rule[2]);
                if (
                    address.length != network.length || prefix < 0 || prefix > address.length * 8
                ) return "false";
                for (int bit = 0; bit < prefix; bit++) if (
                    ((address[bit / 8] ^ network[bit / 8]) & (1 << (7 - (bit % 8)))) != 0
                ) return "false";
                return "true";
            case "hexencode":
                StringBuilder encoded = new StringBuilder();
                for (byte b : bytes) encoded.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
                return encoded.toString();
            case "sha256":
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    input.getBytes(StandardCharsets.UTF_8)
                );
                StringBuilder hex = new StringBuilder();
                for (byte b : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
                return hex.toString();
            case "base64encode":
                return Base64.getEncoder().encodeToString(bytes);
            case "base64decode":
                return new String(Base64.getDecoder().decode(input), StandardCharsets.ISO_8859_1);
            case "utf8encode":
                return new String(input.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
            case "utf8decode":
                return new String(bytes, StandardCharsets.UTF_8);
            case "gunzip":
            case "inflate":
                try (
                    InputStream in = operation.equals("gunzip")
                        ? new GZIPInputStream(new ByteArrayInputStream(bytes))
                        : new InflaterInputStream(new ByteArrayInputStream(bytes));
                    ByteArrayOutputStream out = new ByteArrayOutputStream()
                ) {
                    byte[] buffer = new byte[8192];
                    int n;
                    while ((n = in.read(buffer)) != -1) {
                        if (out.size() + n > ProviderScript.MAX_BYTES) throw new IOException(
                            "Inflated limit"
                        );
                        out.write(buffer, 0, n);
                    }
                    return out.toString(StandardCharsets.ISO_8859_1.name());
                }
            default:
                throw new IOException("Unsupported codec");
        }
    }

    private static byte[] literal(String address) throws IOException {
        if (address.length() > 64 || !address.matches("[0-9a-fA-F:.]+")) throw new IOException(
            "Invalid IP literal"
        );
        if (!address.contains(":")) {
            if (!address.matches("[0-9]{1,3}(?:\\.[0-9]{1,3}){3}")) throw new IOException(
                "Invalid IPv4 literal"
            );
            for (String part : address.split("\\."))
                if (Integer.parseInt(part) > 255) throw new IOException("Invalid IPv4 literal");
        }
        byte[] bytes = java.net.InetAddress.getByName(address).getAddress();
        if (address.contains(":") && bytes.length == 4) {
            byte[] mapped = new byte[16];
            mapped[10] = (byte) 255;
            mapped[11] = (byte) 255;
            System.arraycopy(bytes, 0, mapped, 12, 4);
            return mapped;
        }
        return bytes;
    }
}
