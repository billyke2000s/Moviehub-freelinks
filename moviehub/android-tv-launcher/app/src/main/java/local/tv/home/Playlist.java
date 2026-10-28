package local.tv.home;

import java.net.URI;
import java.util.*;
import java.util.regex.*;

/** Provider playlist data is never executable input. */
public final class Playlist {

    public static final class Channel {

        public final String name, url, group, logo, epgId, sourceLabel;

        public Channel(String name, String url, String group, String logo, String epgId) {
            this(name, url, group, logo, epgId, "");
        }

        public Channel(String name, String url, String group, String logo, String epgId, String sourceLabel) {
            this.name = name;
            this.url = url;
            this.group = group;
            this.logo = logo;
            this.epgId = epgId;
            this.sourceLabel = sourceLabel == null ? "" : sourceLabel;
        }
    }

    private static final Pattern ATTR = Pattern.compile("([\\w-]+)=\"([^\"]*)\"");

    public static String networkUrl(String value, boolean allowHttp) {
        URI u = URI.create(value.trim());
        String scheme = u.getScheme();
        if (
            u.getHost() == null ||
            u.getUserInfo() != null ||
            !("https".equalsIgnoreCase(scheme) || (allowHttp && "http".equalsIgnoreCase(scheme)))
        ) throw new IllegalArgumentException("Use an HTTPS media URL");
        return u.toASCIIString();
    }

    /** Receives channels as they are parsed, so large playlists are never held in memory. */
    public interface Sink {
        void accept(Channel channel) throws Exception;
    }

    public static List<Channel> parse(String text, String source, boolean allowHttp) {
        List<Channel> result = new ArrayList<>();
        try {
            parse(new java.io.StringReader(text), source, allowHttp, result::add);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return result;
    }

    /** Streams an M3U playlist. Returns the number of accepted channels. */
    public static int parse(java.io.Reader reader, String source, boolean allowHttp, Sink sink)
        throws Exception {
        URI base = URI.create(networkUrl(source, allowHttp));
        java.io.BufferedReader lines = new java.io.BufferedReader(reader, 65536);
        int count = 0;
        String name = "",
            group = "",
            logo = "",
            id = "",
            raw;
        while ((raw = lines.readLine()) != null) {
            String line = raw.replace("\uFEFF", "").trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#EXTINF:")) {
                int comma = -1;
                boolean quoted = false;
                for (int i = 0; i < line.length(); i++) {
                    if (line.charAt(i) == '\"') quoted = !quoted;
                    if (line.charAt(i) == ',' && !quoted) {
                        comma = i;
                        break;
                    }
                }
                name = comma < 0 ? "Channel" : line.substring(comma + 1).trim();
                group = "";
                logo = "";
                id = "";
                Matcher m = ATTR.matcher(comma < 0 ? line : line.substring(0, comma));
                while (m.find()) {
                    switch (m.group(1)) {
                        case "group-title":
                            group = m.group(2);
                            break;
                        case "tvg-logo":
                            logo = m.group(2);
                            break;
                        case "tvg-id":
                            id = m.group(2);
                            break;
                    }
                }
            } else if (line.startsWith("#EXTGRP:")) {
                group = line.substring(8).trim();
            } else if (!line.startsWith("#")) {
                String url = null;
                try {
                    url = networkUrl(base.resolve(line).toString(), allowHttp);
                } catch (IllegalArgumentException ignored) {}
                if (url != null) {
                    count++;
                    sink.accept(
                        new Channel(name.isEmpty() ? "Channel " + count : name, url, group, logo, id)
                    );
                }
                name = "";
                group = "";
                logo = "";
                id = "";
            }
        }
        return count;
    }
}
