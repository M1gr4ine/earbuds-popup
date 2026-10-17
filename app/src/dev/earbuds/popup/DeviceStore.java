package dev.earbuds.popup;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Per-device association records and per-model appearance settings. */
public final class DeviceStore {
    private static final String PREFS = "popup";
    private static final String LEGACY_ADDRESS = "address";
    private static final String LEGACY_NAME = "name";
    private static final String LEGACY_ENABLED = "enabled";
    private static final String LEGACY_ASSOCIATION_ID = "companionAssociationId";
    private static final String SELECTED_ADDRESS = "device.selectedAddress";
    private static final String RECORD_PREFIX = "device.record.";
    private static final String CONFIG_PREFIX = "profile.config.";

    private DeviceStore() {}

    /** Immutable device selection and association snapshot. */
    public static final class DeviceRecord {
        public final String address;
        public final String name;
        public final String modelId;
        public final int associationId;

        public DeviceRecord(String address, String name, String modelId, int associationId) {
            this.address = address;
            this.name = name;
            this.modelId = modelId;
            this.associationId = associationId;
        }
    }

    public static List<DeviceRecord> devices(Context context) {
        SharedPreferences prefs = prefs(context);
        migrateLegacy(prefs);
        List<DeviceRecord> result = new ArrayList<>();
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith(RECORD_PREFIX) || !key.endsWith(".address")
                    || !(entry.getValue() instanceof String)) continue;
            String address = (String) entry.getValue();
            DeviceRecord record = readRecord(prefs, address, -1);
            if (record != null) result.add(record);
        }
        Collections.sort(result, new Comparator<DeviceRecord>() {
            @Override public int compare(DeviceRecord first, DeviceRecord second) {
                String a = first.name == null ? "" : first.name;
                String b = second.name == null ? "" : second.name;
                int byName = a.compareToIgnoreCase(b);
                return byName != 0 ? byName : safe(first.address).compareToIgnoreCase(safe(second.address));
            }
        });
        return Collections.unmodifiableList(result);
    }

    public static DeviceRecord selected(Context context) {
        SharedPreferences prefs = prefs(context);
        migrateLegacy(prefs);
        String address = prefs.getString(SELECTED_ADDRESS, prefs.getString(LEGACY_ADDRESS, null));
        return readRecord(prefs, address, -1);
    }

    public static DeviceRecord findByAddress(Context context, String address) {
        SharedPreferences prefs = prefs(context);
        migrateLegacy(prefs);
        return readRecord(prefs, address, -1);
    }

    public static DeviceRecord findByAssociation(Context context, int associationId) {
        if (associationId <= 0) return null;
        for (DeviceRecord record : devices(context)) {
            if (record.associationId == associationId) return record;
        }
        return null;
    }

    public static DeviceRecord upsertDevice(Context context, String address, String name,
                                            int associationId) {
        if (address == null || address.trim().length() == 0) return null;
        SharedPreferences prefs = prefs(context);
        migrateLegacy(prefs);
        DeviceRecord old = readRecord(prefs, address, -1);
        String safeName = name == null || name.trim().length() == 0
                ? (old == null ? "Bluetooth device" : old.name) : name.trim();
        String modelId;
        if (old == null) {
            modelId = modelForName(safeName, address);
        } else {
            // A manual profile choice survives future name changes and reconnects.
            modelId = old.modelId;
        }
        int savedAssociation = associationId > 0 ? associationId
                : old == null ? -1 : old.associationId;
        DeviceRecord updated = new DeviceRecord(address.trim(), safeName, modelId, savedAssociation);
        writeRecord(prefs, updated);
        return updated;
    }

    public static void select(Context context, DeviceRecord record) {
        if (record == null || record.address == null || record.address.trim().length() == 0) return;
        SharedPreferences prefs = prefs(context);
        DeviceRecord saved = upsertDevice(context, record.address, record.name, record.associationId);
        String modelId = normalizeModelId(record.modelId, record.address);
        if (!modelId.equals(saved.modelId)) {
            saved = new DeviceRecord(saved.address, saved.name, modelId, saved.associationId);
            writeRecord(prefs, saved);
        }
        SharedPreferences.Editor editor = prefs.edit()
                .putString(SELECTED_ADDRESS, saved.address)
                .putString(LEGACY_ADDRESS, saved.address)
                .putString(LEGACY_NAME, safe(saved.name));
        if (saved.associationId > 0) {
            editor.putInt(LEGACY_ASSOCIATION_ID, saved.associationId);
        } else {
            // The old global ID belongs to the previous device; its record keeps it.
            editor.remove(LEGACY_ASSOCIATION_ID);
        }
        editor.apply();
    }

    public static PopupConfig loadConfig(Context context, String modelId) {
        String key = normalizeConfigId(modelId);
        PopupConfig defaults = PopupConfig.defaults(key);
        SharedPreferences prefs = prefs(context);
        String prefix = configPrefix(key);
        PopupConfig value = defaults.copy();
        value.position = prefs.getInt(prefix + "position", defaults.position);
        value.offsetDp = prefs.getInt(prefix + "offsetDp", defaults.offsetDp);
        value.widthPercent = prefs.getInt(prefix + "widthPercent", defaults.widthPercent);
        value.mediaHeightDp = prefs.getInt(prefix + "mediaHeightDp", defaults.mediaHeightDp);
        value.radiusDp = prefs.getInt(prefix + "radiusDp", defaults.radiusDp);
        value.backgroundColor = prefs.getInt(prefix + "backgroundColor", defaults.backgroundColor);
        value.backgroundAlpha = prefs.getInt(prefix + "backgroundAlpha", defaults.backgroundAlpha);
        value.durationMs = prefs.getInt(prefix + "durationMs", defaults.durationMs);
        value.videoSpeed = prefs.getFloat(prefix + "videoSpeed", defaults.videoSpeed);
        value.fitCrop = prefs.getBoolean(prefix + "fitCrop", defaults.fitCrop);
        value.showBattery = prefs.getBoolean(prefix + "showBattery", defaults.showBattery);
        value.assetId = prefs.getString(prefix + "assetId", defaults.assetId);
        value.showEarbuds = prefs.getBoolean(prefix + "showEarbuds", defaults.showEarbuds);
        value.earbudAssetId = prefs.getString(prefix + "earbudAssetId", defaults.earbudAssetId);
        return sanitizeForModel(value, key);
    }

    public static void saveConfig(Context context, String modelId, PopupConfig config) {
        String key = normalizeConfigId(modelId);
        PopupConfig value = sanitizeForModel(config == null
                ? PopupConfig.defaults(key) : config, key);
        String prefix = configPrefix(key);
        prefs(context).edit()
                .putInt(prefix + "position", value.position)
                .putInt(prefix + "offsetDp", value.offsetDp)
                .putInt(prefix + "widthPercent", value.widthPercent)
                .putInt(prefix + "mediaHeightDp", value.mediaHeightDp)
                .putInt(prefix + "radiusDp", value.radiusDp)
                .putInt(prefix + "backgroundColor", value.backgroundColor)
                .putInt(prefix + "backgroundAlpha", value.backgroundAlpha)
                .putInt(prefix + "durationMs", value.durationMs)
                .putFloat(prefix + "videoSpeed", value.videoSpeed)
                .putBoolean(prefix + "fitCrop", value.fitCrop)
                .putBoolean(prefix + "showBattery", value.showBattery)
                .putString(prefix + "assetId", value.assetId)
                .putBoolean(prefix + "showEarbuds", value.showEarbuds)
                .putString(prefix + "earbudAssetId", value.earbudAssetId)
                .apply();
    }

    public static DeviceRecord setModel(Context context, String address, String modelId) {
        DeviceRecord record = findByAddress(context, address);
        if (record == null) return null;
        String selectedModel = normalizeModelId(modelId, record.address);
        DeviceRecord updated = new DeviceRecord(record.address, record.name, selectedModel,
                record.associationId);
        writeRecord(prefs(context), updated);
        return updated;
    }

    private static void migrateLegacy(SharedPreferences prefs) {
        String address = prefs.getString(LEGACY_ADDRESS, null);
        if (address == null || address.trim().length() == 0) return;
        DeviceRecord existing = readRecord(prefs, address, -1);
        if (existing == null) {
            String name = prefs.getString(LEGACY_NAME, "Bluetooth device");
            int associationId = prefs.getInt(LEGACY_ASSOCIATION_ID, -1);
            writeRecord(prefs, new DeviceRecord(address, safe(name),
                    modelForName(name, address), associationId));
        }
        if (!prefs.contains(SELECTED_ADDRESS)) {
            prefs.edit().putString(SELECTED_ADDRESS, address).apply();
        }
    }

    private static DeviceRecord readRecord(SharedPreferences prefs, String address,
                                           int fallbackAssociationId) {
        if (address == null || address.trim().length() == 0) return null;
        String normalizedAddress = address.trim();
        String prefix = recordPrefix(normalizedAddress);
        String storedAddress = prefs.getString(prefix + "address", null);
        if (storedAddress == null || !storedAddress.equalsIgnoreCase(normalizedAddress)) return null;
        String name = prefs.getString(prefix + "name", "Bluetooth device");
        String modelId = prefs.getString(prefix + "modelId", null);
        if (modelId == null || modelId.length() == 0) modelId = modelForName(name, normalizedAddress);
        int associationId = prefs.getInt(prefix + "associationId", fallbackAssociationId);
        return new DeviceRecord(storedAddress, name, modelId, associationId);
    }

    private static void writeRecord(SharedPreferences prefs, DeviceRecord record) {
        String prefix = recordPrefix(record.address);
        prefs.edit()
                .putString(prefix + "address", record.address)
                .putString(prefix + "name", safe(record.name))
                .putString(prefix + "modelId", normalizeModelId(record.modelId, record.address))
                .putInt(prefix + "associationId", record.associationId)
                .apply();
    }

    private static String modelForName(String name, String address) {
        String recognized = DeviceModel.resolveModelId(name);
        return DeviceModel.MODEL_UNKNOWN.equals(recognized)
                ? DeviceModel.unknownProfileId(address) : recognized;
    }

    private static String normalizeModelId(String modelId, String address) {
        if (modelId == null || modelId.length() == 0
                || DeviceModel.MODEL_UNKNOWN.equals(modelId)) {
            return DeviceModel.unknownProfileId(address);
        }
        if (DeviceModel.isKnownModelId(modelId) || isUnknownProfile(modelId)) return modelId;
        return DeviceModel.unknownProfileId(address);
    }

    private static boolean isUnknownProfile(String modelId) {
        return modelId != null && modelId.startsWith("unknown_");
    }

    private static PopupConfig sanitizeForModel(PopupConfig config, String modelId) {
        PopupConfig value = config.sanitized();
        if (!DeviceModel.MODEL_FREEBUDS_6I.equals(modelId)
                && "builtin:freebuds_6i".equals(value.assetId)) {
            value.assetId = "";
        }
        return value;
    }

    private static String normalizeConfigId(String modelId) {
        if (modelId == null || modelId.trim().length() == 0) return DeviceModel.MODEL_UNKNOWN;
        String value = modelId.trim();
        if (value.length() > 128 || containsControl(value)) return DeviceModel.MODEL_UNKNOWN;
        return value;
    }

    private static boolean containsControl(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) return true;
        }
        return false;
    }

    private static String recordPrefix(String address) {
        return RECORD_PREFIX + DeviceModel.unknownProfileId(address).substring("unknown_".length()) + ".";
    }

    private static String configPrefix(String modelId) {
        return CONFIG_PREFIX + modelId + ".";
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
