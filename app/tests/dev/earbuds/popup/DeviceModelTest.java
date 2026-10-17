package dev.earbuds.popup;

public final class DeviceModelTest {
    private static void equal(String expected, String actual, String message) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(message + " expected=" + expected + " actual=" + actual);
        }
    }

    private static void expect(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        equal(DeviceModel.MODEL_FREEBUDS_6I,
                DeviceModel.resolveModelId(" HUAWEI   FreeBuds 6i "),
                "Whitespace and case normalize an exact 6i alias");
        equal(DeviceModel.MODEL_FREEBUDS_6I,
                DeviceModel.resolveModelId("FreeBuds-6i"),
                "A punctuation-normalized exact alias is recognized");
        equal(DeviceModel.MODEL_FREEBUDS_PRO_5,
                DeviceModel.resolveModelId("HUAWEI FreeBuds Pro 5"),
                "The reserved Pro 5 profile can be selected exactly");
        equal(DeviceModel.MODEL_UNKNOWN,
                DeviceModel.resolveModelId("Huawei FreeBuds 7"),
                "A different Huawei model is not guessed");
        equal(DeviceModel.MODEL_UNKNOWN,
                DeviceModel.resolveModelId("Renamed Earbuds"),
                "Renamed devices stay unknown");

        String first = DeviceModel.unknownProfileId("AA:BB:CC:DD:EE:FF");
        equal(first, DeviceModel.unknownProfileId("aa:bb:cc:dd:ee:ff"),
                "Unknown profile IDs are stable across address casing");
        expect(!first.contains("AA:BB:CC:DD:EE:FF"),
                "Unknown profile keys do not contain the device address");
        expect(!first.equals(DeviceModel.unknownProfileId("11:22:33:44:55:66")),
                "Different devices get separate unknown profiles");
        System.out.println("PASS: exact model aliases, unknown fallback, and address-scoped IDs");
    }
}