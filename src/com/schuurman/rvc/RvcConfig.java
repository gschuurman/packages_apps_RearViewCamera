package com.schuurman.rvc;

import android.os.SystemProperties;
import android.util.Size;

import androidx.preference.PreferenceDataStore;

/**
 * Rear view camera settings, kept in system properties (persist.rvc.*).
 *
 * The settings screen runs in the driver's user (it is opened from CarSettings), while the camera
 * can be started in another user (the persistent gear monitor runs in the system user), so per-user
 * storage like SharedPreferences would not be shared between them.
 */
final class RvcConfig {
    private static final String PREFIX = "persist.rvc.";

    /** Camera2 id of the camera to show; empty: the first external camera. */
    static final String KEY_CAMERA_ID = "camera_id";
    /** Stream size as "WxH"; empty: automatic. */
    static final String KEY_STREAM_SIZE = "stream_size";
    /** Mirror the image horizontally (a rear camera usually already outputs a mirrored image). */
    static final String KEY_MIRROR = "mirror";
    /** How the image is scaled into the screen, one of the SCALE_* values. */
    static final String KEY_SCALE = "scale";

    static final String SCALE_FIT = "fit";
    static final String SCALE_FILL = "fill";
    static final String SCALE_STRETCH = "stretch";

    private RvcConfig() {}

    static String getCameraId() {
        return get(KEY_CAMERA_ID, "");
    }

    /** @return the configured stream size, or null for automatic */
    static Size getStreamSize() {
        final String value = get(KEY_STREAM_SIZE, "");
        if (value.isEmpty()) {
            return null;
        }
        try {
            return Size.parseSize(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static boolean isMirrored() {
        return SystemProperties.getBoolean(PREFIX + KEY_MIRROR, false);
    }

    static String getScale() {
        return get(KEY_SCALE, SCALE_FIT);
    }

    static String toString(Size size) {
        return size.getWidth() + "x" + size.getHeight();
    }

    private static String get(String key, String def) {
        return SystemProperties.get(PREFIX + key, def);
    }

    /** Stores the preferences of the settings screen in the properties above. */
    static final class DataStore extends PreferenceDataStore {
        @Override
        public void putString(String key, String value) {
            SystemProperties.set(PREFIX + key, value == null ? "" : value);
        }

        @Override
        public String getString(String key, String defValue) {
            return get(key, defValue == null ? "" : defValue);
        }

        @Override
        public void putBoolean(String key, boolean value) {
            SystemProperties.set(PREFIX + key, Boolean.toString(value));
        }

        @Override
        public boolean getBoolean(String key, boolean defValue) {
            return SystemProperties.getBoolean(PREFIX + key, defValue);
        }
    }
}
