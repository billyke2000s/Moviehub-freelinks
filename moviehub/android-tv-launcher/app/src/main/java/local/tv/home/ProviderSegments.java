package local.tv.home;

/** Port of Masqueradarr proxy/src/tsseg.rs disguise_prefix_len (MIT, historical upstream SHA in NOTICE). */
final class ProviderSegments {

    static final int HEAD = 4096 + 5 * 188;

    private ProviderSegments() {}

    static int prefix(byte[] head, int length) {
        if (length == 0 || head[0] == 0x47) return 0;
        if (length >= 12 && ascii(head, 0, "RIFF") && ascii(head, 8, "WEBP")) {
            long pos = 12;
            while (pos < 4096 && pos + 8 <= length) {
                int at = (int) pos;
                long size =
                    (head[at + 4] & 255L) |
                    ((head[at + 5] & 255L) << 8) |
                    ((head[at + 6] & 255L) << 16) |
                    ((head[at + 7] & 255L) << 24);
                int payload = at + 8;
                if (ascii(head, at, "EXIF")) {
                    if (
                        payload <= 4096 &&
                        payload < length &&
                        size > 0 &&
                        size % 188 == 0 &&
                        head[payload] == 0x47
                    ) return payload;
                    break;
                }
                pos = payload + size + (size & 1);
            }
        }
        for (int at = 1; at <= 4096 && at + 4 * 188 < length; at++) {
            boolean proven = true;
            for (int packet = 0; packet < 5; packet++) if (head[at + packet * 188] != 0x47) {
                proven = false;
                break;
            }
            if (proven) return at;
        }
        return 0;
    }

    private static boolean ascii(byte[] bytes, int at, String value) {
        for (int i = 0; i < value.length(); i++) if (bytes[at + i] != (byte) value.charAt(i)) return false;
        return true;
    }
}
