package dev.earbuds.popup;

/** Serializable values for one device-model popup profile. */
public final class PopupConfig {
    public int position = 2;
    public int offsetDp = 18;
    public int widthPercent = 92;
    public int mediaHeightDp = 220;
    public int radiusDp = 32;
    public int backgroundColor = 0xFFFFFFFF;
    public int backgroundAlpha = 100;
    /** Legacy profile value retained for stored-configuration compatibility. */
    public int durationMs = 5000;
    public float videoSpeed = 1.0f;
    public boolean fitCrop = false;
    public boolean showBattery = true;
    public String assetId = "";

    public PopupConfig() {}

    public PopupConfig copy() {
        PopupConfig value = new PopupConfig();
        value.position = position;
        value.offsetDp = offsetDp;
        value.widthPercent = widthPercent;
        value.mediaHeightDp = mediaHeightDp;
        value.radiusDp = radiusDp;
        value.backgroundColor = backgroundColor;
        value.backgroundAlpha = backgroundAlpha;
        value.durationMs = durationMs;
        value.videoSpeed = videoSpeed;
        value.fitCrop = fitCrop;
        value.showBattery = showBattery;
        value.assetId = assetId;
        return value;
    }

    /** Returns a validated copy, leaving the editable source object untouched. */
    public PopupConfig sanitized() {
        PopupConfig value = copy();
        value.position = clamp(position, 0, 2, 2);
        value.offsetDp = clamp(offsetDp, -160, 160, 18);
        value.widthPercent = clamp(widthPercent, 65, 100, 92);
        value.mediaHeightDp = clamp(mediaHeightDp, 140, 300, 220);
        value.radiusDp = clamp(radiusDp, 0, 48, 32);
        value.backgroundColor = 0xFF000000 | (backgroundColor & 0x00FFFFFF);
        value.backgroundAlpha = clamp(backgroundAlpha, 60, 100, 100);
        value.durationMs = clamp(durationMs, 2000, 15000, 5000);
        value.videoSpeed = finite(videoSpeed) && videoSpeed >= 0.5f && videoSpeed <= 2f
                ? videoSpeed : 1.0f;
        value.assetId = safeAssetId(assetId);
        return value;
    }

    public static PopupConfig defaults(String modelId) {
        // Public builds start with no bundled product artwork for any model.
        return new PopupConfig();
    }

    private static int clamp(int value, int min, int max, int fallback) {
        return value < min || value > max ? fallback : value;
    }

    private static boolean finite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }

    private static String safeAssetId(String value) {
        if (value == null) return "";
        String trimmed = value.trim();
        if (trimmed.length() == 0 || trimmed.length() > 256) return "";
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (Character.isISOControl(c)) return "";
        }
        return trimmed;
    }
}
