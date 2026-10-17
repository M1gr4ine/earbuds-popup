package dev.earbuds.popup;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Exact device-name aliases and model-profile identity; no brand-substring guessing. */
public final class DeviceModel {
    public static final String MODEL_FREEBUDS_6I = "freebuds_6i";
    public static final String MODEL_FREEBUDS_PRO_5 = "freebuds_pro_5";
    public static final String MODEL_UNKNOWN = "unknown";

    private static final Map<String, String> MODEL_BY_ALIAS = new HashMap<>();
    private static final String[] AVAILABLE_MODEL_IDS = {
            MODEL_FREEBUDS_6I, MODEL_FREEBUDS_PRO_5, MODEL_UNKNOWN
    };

    static {
        addAliases(MODEL_FREEBUDS_6I,
                "HUAWEI FreeBuds 6i", "FreeBuds 6i", "HUAWEI FreeBuds6i", "FreeBuds6i");
        addAliases(MODEL_FREEBUDS_PRO_5,
                "HUAWEI FreeBuds Pro 5", "FreeBuds Pro 5",
                "HUAWEI FreeBudsPro5", "FreeBudsPro5");
    }

    private DeviceModel() {}

    public static String resolveModelId(String name) {
        String normalized = normalize(name);
        String modelId = MODEL_BY_ALIAS.get(normalized);
        return modelId == null ? MODEL_UNKNOWN : modelId;
    }

    public static String displayName(String modelId) {
        if (MODEL_FREEBUDS_6I.equals(modelId)) return "HUAWEI FreeBuds 6i";
        if (MODEL_FREEBUDS_PRO_5.equals(modelId)) return "HUAWEI FreeBuds Pro 5";
        return "未知型号";
    }

    public static String[] availableModelIds() {
        return AVAILABLE_MODEL_IDS.clone();
    }

    public static boolean isKnownModelId(String modelId) {
        return MODEL_FREEBUDS_6I.equals(modelId)
                || MODEL_FREEBUDS_PRO_5.equals(modelId)
                || MODEL_UNKNOWN.equals(modelId);
    }

    /** Stable address-scoped key for a device whose model is not recognized. */
    public static String unknownProfileId(String address) {
        if (address == null || address.trim().length() == 0) return "unknown_unbound";
        String canonical = address.trim().toUpperCase(Locale.ROOT);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder key = new StringBuilder("unknown_");
            for (int i = 0; i < 12; i++) {
                int value = digest[i] & 0xFF;
                key.append(Character.forDigit((value >>> 4) & 0xF, 16));
                key.append(Character.forDigit(value & 0xF, 16));
            }
            return key.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("SHA-256 is required by the Java runtime", impossible);
        }
    }

    private static void addAliases(String modelId, String... aliases) {
        for (String alias : aliases) MODEL_BY_ALIAS.put(normalize(alias), modelId);
    }

    private static String normalize(String value) {
        if (value == null) return "";
        String lower = value.trim().toLowerCase(Locale.ROOT);
        StringBuilder normalized = new StringBuilder(lower.length());
        boolean pendingSpace = false;
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                if (pendingSpace && normalized.length() > 0) normalized.append(' ');
                normalized.append(c);
                pendingSpace = false;
            } else {
                pendingSpace = true;
            }
        }
        return normalized.toString();
    }
}