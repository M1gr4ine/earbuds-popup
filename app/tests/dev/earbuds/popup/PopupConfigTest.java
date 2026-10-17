package dev.earbuds.popup;

public final class PopupConfigTest {
    private static void expect(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void equal(int expected, int actual, String message) {
        if (expected != actual) throw new AssertionError(message + " expected=" + expected + " actual=" + actual);
    }

    private static void equal(String expected, String actual, String message) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(message + " expected=" + expected + " actual=" + actual);
        }
    }

    public static void main(String[] args) {
        PopupConfig sixI = PopupConfig.defaults(DeviceModel.MODEL_FREEBUDS_6I);
        PopupConfig unknown = PopupConfig.defaults(DeviceModel.MODEL_UNKNOWN);
        equal("", sixI.assetId, "Public 6i defaults start without bundled model artwork");
        expect(!sixI.showEarbuds, "Body cutouts stay hidden by default for old profiles");
        equal("", sixI.earbudAssetId, "Profiles start without an imported body cutout");
        equal(32, sixI.radiusDp, "Card radius uses the agreed 32 dp default");
        equal("", unknown.assetId, "Unknown models start without borrowed art");
        for (String modelId : DeviceModel.availableModelIds()) {
            equal("", PopupConfig.defaults(modelId).assetId,
                    "Public defaults never bundle model artwork for " + modelId);
        }
        PopupConfig legacyStored = PopupConfig.defaults(DeviceModel.MODEL_UNKNOWN);
        legacyStored.durationMs = 9000;
        equal(9000, legacyStored.copy().durationMs,
                "Legacy duration remains copy-compatible");
        equal(9000, legacyStored.sanitized().durationMs,
                "Legacy duration remains storage-compatible");
        sixI.widthPercent = 65;
        equal(92, PopupConfig.defaults(DeviceModel.MODEL_FREEBUDS_6I).widthPercent,
                "Each device profile gets an independent default config");

        PopupConfig invalid = PopupConfig.defaults(DeviceModel.MODEL_UNKNOWN);
        invalid.position = 9;
        invalid.offsetDp = 900;
        invalid.widthPercent = 10;
        invalid.mediaHeightDp = 900;
        invalid.radiusDp = -7;
        invalid.backgroundAlpha = 5;
        invalid.durationMs = 100;
        invalid.videoSpeed = Float.NaN;
        invalid.assetId = null;
        invalid.earbudAssetId = "  media:\nbody  ";
        PopupConfig safe = invalid.sanitized();

        equal(2, safe.position, "Invalid position returns to the bottom default");
        equal(18, safe.offsetDp, "Out-of-range offset returns to its default");
        equal(92, safe.widthPercent, "Out-of-range width returns to its default");
        equal(220, safe.mediaHeightDp, "Out-of-range media height returns to its default");
        equal(32, safe.radiusDp, "Out-of-range radius returns to its default");
        equal(100, safe.backgroundAlpha, "Out-of-range opacity returns to its default");
        equal(5000, safe.durationMs, "Out-of-range duration returns to its default");
        expect(Math.abs(1f - safe.videoSpeed) < 0.0001f, "NaN speed returns to normal speed");
        equal("", safe.assetId, "Missing media becomes the empty selection");
        equal("", safe.earbudAssetId, "Unsafe body media IDs become the empty selection");
        equal(10, invalid.widthPercent, "Sanitizing does not mutate its source");

        PopupConfig speed = PopupConfig.defaults(DeviceModel.MODEL_UNKNOWN);
        speed.videoSpeed = Float.POSITIVE_INFINITY;
        expect(Math.abs(1f - speed.sanitized().videoSpeed) < 0.0001f,
                "Infinite speed returns to normal speed");

        PopupConfig copy = sixI.copy();
        copy.assetId = "custom:one";
        copy.showEarbuds = true;
        copy.earbudAssetId = "media:body-one";
        equal("", sixI.assetId, "copy is independent from its source");
        expect(!sixI.showEarbuds, "Body visibility copies are independent from their source");
        equal("", sixI.earbudAssetId, "Body image copies are independent from their source");

        PopupConfig configuredCutout = new PopupConfig();
        configuredCutout.showEarbuds = true;
        configuredCutout.earbudAssetId = "  media:body-two  ";
        PopupConfig safeCutout = configuredCutout.sanitized();
        expect(safeCutout.showEarbuds, "Body visibility survives sanitization");
        equal("media:body-two", safeCutout.earbudAssetId,
                "Body media IDs are trimmed during sanitization");
        System.out.println("PASS: profile defaults, independent copies, bounds, NaN, and infinity");
    }
}
