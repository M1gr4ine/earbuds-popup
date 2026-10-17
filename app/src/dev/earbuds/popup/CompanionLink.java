package dev.earbuds.popup;

import android.app.Activity;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.ScanFilter;
import android.companion.AssociationInfo;
import android.companion.AssociationRequest;
import android.companion.BluetoothDeviceFilter;
import android.companion.BluetoothLeDeviceFilter;
import android.companion.CompanionDeviceManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.util.Log;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/** User-approved Companion Device Manager association and presence observation. */
public final class CompanionLink {
    public static final int REQUEST_CODE = 7401;
    public static final String PREF_ASSOCIATION_ID = "companionAssociationId";
    private static final String TAG = "EarbudsPopup";
    private static final int NO_ASSOCIATION = -1;
    private static final Map<Activity, Pending> PENDING = new WeakHashMap<>();

    private CompanionLink() {}

    private static final class Pending {
        final String address;
        final String name;
        Runnable ready;
        Runnable failure;
        boolean chooserLaunched;
        boolean completed;

        Pending(String address, String name, Runnable ready, Runnable failure) {
            this.address = address;
            this.name = name;
            this.ready = ready;
            this.failure = failure;
        }
    }

    public static boolean hasAssociation(Context context) {
        String address = prefs(context).getString("address", null);
        if (address == null || address.isEmpty() || Build.VERSION.SDK_INT < 31) return false;
        CompanionDeviceManager manager = manager(context);
        if (manager == null) return false;
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                int savedId = prefs(context).getInt(PREF_ASSOCIATION_ID, NO_ASSOCIATION);
                List<AssociationInfo> associations = manager.getMyAssociations();
                for (AssociationInfo info : associations) {
                    String associatedAddress = info.getDeviceMacAddress() == null
                            ? null : info.getDeviceMacAddress().toString();
                    if (info.getId() == savedId && (associatedAddress == null || sameAddress(address, associatedAddress))) {
                        DeviceStore.upsertDevice(context,address,prefs(context).getString("name","Bluetooth audio"),info.getId());
                        return true;
                    }
                    if (sameAddress(address, associatedAddress)) {
                        prefs(context).edit().putInt(PREF_ASSOCIATION_ID, info.getId()).apply();
                        DeviceStore.upsertDevice(context,address,prefs(context).getString("name","Bluetooth audio"),info.getId());
                        return true;
                    }
                }
                return false;
            }
            for (String associatedAddress : manager.getAssociations()) {
                if (sameAddress(address, associatedAddress)) return true;
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "CDM_ASSOCIATION_QUERY_FAILED", e);
        }
        return false;
    }

    /** Call only in response to the user's explicit device-selection action. */
    public static void requestAssociation(Activity activity, String targetAddress, String targetName, Runnable ready) {
        requestAssociation(activity, targetAddress, targetName, ready, null);
    }

    /** Call only in response to the user's explicit device-selection action. */
    public static void requestAssociation(Activity activity, String targetAddress, String targetName,
                                          Runnable ready, Runnable failure) {
        if (activity == null) {
            Log.w(TAG, "CDM_ACTIVITY_UNAVAILABLE");
            runFailure(failure);
            return;
        }
        if (Build.VERSION.SDK_INT < 31) {
            Log.w(TAG, "CDM_UNSUPPORTED sdk=" + Build.VERSION.SDK_INT);
            runFailure(failure);
            return;
        }
        boolean featureAvailable;
        try {
            featureAvailable = activity.getPackageManager()
                    .hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP);
        } catch (RuntimeException e) {
            Log.e(TAG, "CDM_FEATURE_QUERY_FAILED", e);
            runFailure(failure);
            return;
        }
        if (!featureAvailable) {
            Log.w(TAG, "CDM_FEATURE_UNAVAILABLE");
            runFailure(failure);
            return;
        }
        String address = resolveAddress(activity, targetAddress, targetName);
        if (address == null) {
            Log.w(TAG, "CDM_TARGET_ADDRESS_UNAVAILABLE");
            runFailure(failure);
            return;
        }
        String name = targetName == null || targetName.isEmpty() ? "Bluetooth audio device" : targetName;
        if (sameAddress(address,prefs(activity).getString("address",null)) && hasAssociation(activity)) {
            resumeObservation(activity);
            runReady(ready);
            return;
        }
        CompanionDeviceManager manager = manager(activity);
        if (manager == null) {
            Log.e(TAG, "CDM_SERVICE_UNAVAILABLE");
            runFailure(failure);
            return;
        }

        Pending pending = new Pending(address, name, ready, failure);
        boolean alreadyPending;
        synchronized (PENDING) {
            alreadyPending = PENDING.containsKey(activity);
            if (!alreadyPending) PENDING.put(activity, pending);
        }
        if (alreadyPending) {
            Log.w(TAG, "CDM_ASSOCIATION_ALREADY_PENDING");
            runFailure(failure);
            return;
        }
        try {
            AssociationRequest.Builder builder = new AssociationRequest.Builder()
                    .addDeviceFilter(new BluetoothDeviceFilter.Builder().setAddress(address).build())
                    .addDeviceFilter(new BluetoothLeDeviceFilter.Builder()
                            .setScanFilter(new ScanFilter.Builder().setDeviceAddress(address).build())
                            .build())
                    .setSingleDevice(true);
            if (Build.VERSION.SDK_INT >= 33) builder.setDisplayName(name);
            AssociationRequest request = builder.build();
            CompanionDeviceManager.Callback callback = new CompanionDeviceManager.Callback() {
                @Override public void onAssociationPending(IntentSender sender) {
                    launchChooser(activity, pending, sender);
                }
                @Override public void onDeviceFound(IntentSender sender) {
                    launchChooser(activity, pending, sender);
                }
                @Override public void onAssociationCreated(AssociationInfo info) {
                    finishWithInfo(activity, pending, info);
                }
                @Override public void onFailure(CharSequence error) {
                    Log.e(TAG, "CDM_ASSOCIATION_FAILED " + error);
                    finishFailure(activity, pending, "system_failure");
                }
            };
            if (Build.VERSION.SDK_INT >= 33) {
                manager.associate(request, activity.getMainExecutor(), callback);
            } else {
                manager.associate(request, callback, new Handler(Looper.getMainLooper()));
            }
        } catch (RuntimeException e) {
            finishFailure(activity, pending, "request_exception");
            Log.e(TAG, "CDM_ASSOCIATION_REQUEST_FAILED", e);
        }
    }

    private static void launchChooser(Activity activity, Pending pending, IntentSender sender) {
        synchronized (pending) {
            if (pending.completed || pending.chooserLaunched) return;
            pending.chooserLaunched = true;
        }
        try {
            activity.startIntentSenderForResult(sender, REQUEST_CODE, null, 0, 0, 0);
            Log.i(TAG, "CDM_CHOOSER_LAUNCHED");
        } catch (IntentSender.SendIntentException | RuntimeException e) {
            Log.e(TAG, "CDM_CHOOSER_FAILED", e);
            finishFailure(activity, pending, "chooser_exception");
        }
    }

    /** Delegate the matching Activity result here; returns true if it was the CDM request. */
    public static boolean handleActivityResult(Activity activity, int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_CODE) return false;
        Pending pending;
        synchronized (PENDING) { pending = PENDING.get(activity); }
        if (pending == null) {
            Log.i(TAG, "CDM_RESULT_WITHOUT_PENDING");
            return true;
        }
        if (resultCode != Activity.RESULT_OK) {
            Log.i(TAG, "CDM_ASSOCIATION_CANCELLED");
            finishFailure(activity, pending, "user_cancelled");
            return true;
        }
        try {
            if (Build.VERSION.SDK_INT >= 33 && data != null) {
                Parcelable value = data.getParcelableExtra(CompanionDeviceManager.EXTRA_ASSOCIATION);
                if (value instanceof AssociationInfo) finishWithInfo(activity, pending, (AssociationInfo) value);
                else finishWithAddress(activity, pending);
            } else {
                finishWithAddress(activity, pending);
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "CDM_RESULT_PROCESSING_FAILED", e);
            finishFailure(activity, pending, "result_exception");
        }
        return true;
    }

    private static void finishWithInfo(Context context, Pending pending, AssociationInfo info) {
        if (info == null) {
            finishFailure(context instanceof Activity ? (Activity) context : null, pending, "missing_association_info");
            return;
        }
        String observedAddress = info.getDeviceMacAddress() == null ? null : info.getDeviceMacAddress().toString();
        if (observedAddress != null && !sameAddress(pending.address, observedAddress)) {
            Log.e(TAG, "CDM_ASSOCIATION_ADDRESS_MISMATCH");
            finishFailure(context instanceof Activity ? (Activity) context : null, pending, "address_mismatch");
            return;
        }
        saveAssociation(context, pending, info.getId());
    }

    private static void finishWithAddress(Context context, Pending pending) {
        saveAssociation(context, pending, findAssociationId(context, pending.address));
    }

    private static void saveAssociation(Context context, Pending pending, int associationId) {
        Runnable ready;
        synchronized (pending) {
            if (pending.completed) return;
            pending.completed = true;
            ready = pending.ready;
            pending.ready = null;
            pending.failure = null;
        }
        if (context instanceof Activity) removePending((Activity) context, pending);
        SharedPreferences.Editor edit = prefs(context).edit()
                .putString("address", pending.address)
                .putString("name", pending.name);
        if (associationId > 0) edit.putInt(PREF_ASSOCIATION_ID, associationId);
        edit.apply();
        DeviceStore.upsertDevice(context,pending.address,pending.name,associationId);
        Log.i(TAG, "CDM_ASSOCIATION_READY id=" + associationId);
        resumeObservation(context);
        if (ready != null) {
            try {
                ready.run();
            } catch (RuntimeException e) {
                Log.e(TAG, "CDM_READY_CALLBACK_FAILED", e);
            }
        }
    }

    private static void finishFailure(Activity activity, Pending pending, String reason) {
        if (pending == null) return;
        Runnable failure;
        synchronized (pending) {
            if (pending.completed) return;
            pending.completed = true;
            failure = pending.failure;
            pending.failure = null;
            pending.ready = null;
        }
        if (activity != null) removePending(activity, pending);
        Log.w(TAG, "CDM_ASSOCIATION_ABORTED reason=" + reason);
        runFailure(failure);
    }

    private static void runFailure(Runnable failure) {
        if (failure == null) return;
        try {
            failure.run();
        } catch (RuntimeException e) {
            Log.e(TAG, "CDM_FAILURE_CALLBACK_FAILED", e);
        }
    }

    private static void runReady(Runnable ready) {
        if (ready == null) return;
        try {
            ready.run();
        } catch (RuntimeException e) {
            Log.e(TAG, "CDM_READY_CALLBACK_FAILED", e);
        }
    }

    public static boolean resumeObservation(Context context) {
        return resumeAllObservation(context);
    }

    public static boolean resumeAllObservation(Context context) {
        if(Build.VERSION.SDK_INT<31 ||
            !context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP))return false;
        CompanionDeviceManager manager=manager(context);
        if(manager==null)return false;
        boolean started=false;
        for(DeviceStore.DeviceRecord record:DeviceStore.devices(context)){
            int id=Build.VERSION.SDK_INT>=33?findAssociationId(context,record.address):record.associationId;
            if(Build.VERSION.SDK_INT>=33 && id<=0)continue;
            try{
                if(Build.VERSION.SDK_INT>=36){
                    manager.startObservingDevicePresence(new android.companion.ObservingDevicePresenceRequest.Builder()
                        .setAssociationId(id).build());
                }else{
                    boolean paired=false;
                    if(Build.VERSION.SDK_INT>=33)paired=true;
                    else for(String address:manager.getAssociations())if(sameAddress(address,record.address)){paired=true;break;}
                    if(!paired)continue;
                    manager.startObservingDevicePresence(record.address);
                }
                if(id>0)DeviceStore.upsertDevice(context,record.address,record.name,id);
                Log.i(TAG,"CDM_OBSERVATION_STARTED id="+id);started=true;
            }catch(RuntimeException e){Log.w(TAG,"CDM_OBSERVATION_FAILED id="+id,e);}
        }
        return started;
    }

    private static int findAssociationId(Context context, String address) {
        if (Build.VERSION.SDK_INT < 33) return NO_ASSOCIATION;
        try {
            for (AssociationInfo info : manager(context).getMyAssociations()) {
                String found = info.getDeviceMacAddress() == null ? null : info.getDeviceMacAddress().toString();
                if (sameAddress(address, found)) return info.getId();
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "CDM_ASSOCIATION_ID_LOOKUP_FAILED", e);
        }
        return NO_ASSOCIATION;
    }

    private static String resolveAddress(Context context, String targetAddress, String targetName) {
        if (targetAddress != null && !targetAddress.isEmpty()) return targetAddress;
        try {
            BluetoothManager bluetooth = context.getSystemService(BluetoothManager.class);
            if (bluetooth == null || bluetooth.getAdapter() == null) return null;
            for (BluetoothDevice device : bluetooth.getAdapter().getBondedDevices()) {
                String name = device.getName();
                if (name != null && targetName != null && name.equalsIgnoreCase(targetName)) return device.getAddress();
                if (name != null && targetName == null && name.toLowerCase(Locale.ROOT).contains("freebuds 6i")) return device.getAddress();
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "CDM_BONDED_DEVICE_LOOKUP_FAILED", e);
        }
        return null;
    }

    private static boolean sameAddress(String first, String second) {
        return first != null && second != null && first.equalsIgnoreCase(second);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("popup", Context.MODE_PRIVATE);
    }

    private static CompanionDeviceManager manager(Context context) {
        return context.getSystemService(CompanionDeviceManager.class);
    }

    private static void removePending(Activity activity, Pending pending) {
        synchronized (PENDING) {
            if (PENDING.get(activity) == pending) PENDING.remove(activity);
        }
    }
}
