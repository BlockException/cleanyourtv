package com.gathin.tvhome;

import java.net.URI;
import java.net.URISyntaxException;

final class WallpaperSettings {
    static final int DEFAULT_INTERVAL_SECONDS = 60;
    static final int MIN_INTERVAL_SECONDS = 10;
    static final int MAX_INTERVAL_SECONDS = 86_400;
    static final int DEFAULT_CROSSFADE_SECONDS = 2;
    static final int MIN_CROSSFADE_SECONDS = 1;
    static final int MAX_CROSSFADE_SECONDS = 10;
    static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;

    private WallpaperSettings() { }

    static String validUrl(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        String candidate = value.trim();
        try {
            URI uri = new URI(candidate);
            String scheme = uri.getScheme();
            if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))
                    || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getPort() == 0 || uri.getPort() > 65_535) {
                return null;
            }
            return uri.toString();
        } catch (URISyntaxException invalid) {
            return null;
        }
    }

    static int intervalSeconds(int value) {
        return value >= MIN_INTERVAL_SECONDS && value <= MAX_INTERVAL_SECONDS
                ? value : DEFAULT_INTERVAL_SECONDS;
    }

    static int crossfadeSeconds(int value) {
        return value >= MIN_CROSSFADE_SECONDS && value <= MAX_CROSSFADE_SECONDS
                ? value : DEFAULT_CROSSFADE_SECONDS;
    }

}
