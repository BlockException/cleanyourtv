package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class WallpaperSettingsTest {
    @Test
    public void acceptsHttpAndHttpsUrlsWithHosts() {
        assertEquals("http://192.168.1.2:3008/image?layout=landscape",
                WallpaperSettings.validUrl(" http://192.168.1.2:3008/image?layout=landscape "));
        assertEquals("https://photos.example/image.jpg",
                WallpaperSettings.validUrl("https://photos.example/image.jpg"));
    }

    @Test
    public void rejectsUnsupportedOrMalformedUrls() {
        assertNull(WallpaperSettings.validUrl(null));
        assertNull(WallpaperSettings.validUrl(""));
        assertNull(WallpaperSettings.validUrl("file:///tmp/image.jpg"));
        assertNull(WallpaperSettings.validUrl("ftp://photos.example/image.jpg"));
        assertNull(WallpaperSettings.validUrl("http:///image.jpg"));
        assertNull(WallpaperSettings.validUrl("https://user:password@photos.example/image.jpg"));
        assertNull(WallpaperSettings.validUrl("https://photos.example:70000/image.jpg"));
    }

    @Test
    public void boundsRefreshAndCrossfadeSettings() {
        assertEquals(60, WallpaperSettings.intervalSeconds(0));
        assertEquals(10, WallpaperSettings.intervalSeconds(10));
        assertEquals(86_400, WallpaperSettings.intervalSeconds(86_400));
        assertEquals(60, WallpaperSettings.intervalSeconds(86_401));
        assertEquals(2, WallpaperSettings.crossfadeSeconds(0));
        assertEquals(1, WallpaperSettings.crossfadeSeconds(1));
        assertEquals(10, WallpaperSettings.crossfadeSeconds(10));
        assertEquals(2, WallpaperSettings.crossfadeSeconds(11));
    }
}
