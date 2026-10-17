package dev.earbuds.popup;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/** Read-only battery queries and popup-scoped passive status observation. */
public final class BatteryClient {
    private static final String TAG = "EarbudsBattery";
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");
    private static final long DEADLINE_MILLIS = 5_000L;
    private static final int MAX_RESPONSE_BYTES = 8 * 1024;

    public interface Callback {
        void onBattery(BatterySnapshot snapshot);
        void onUnavailable(String reason);
    }

    public interface ObserverCallback extends Callback {
        /** Raw read-only control notifications; a defensive copy is delivered on main. */
        void onData(byte[] bytes);
    }

    public interface Query {
        void cancel();
    }

    private BatteryClient() { }

    public static Query query(Context context, String address, Callback callback) {
        if (context == null) throw new IllegalArgumentException("context is required");
        if (callback == null) throw new IllegalArgumentException("callback is required");
        QueryImpl query = new QueryImpl(context.getApplicationContext(), address, callback, false);
        query.start();
        return query;
    }

    /** Keeps the SPP stream only until the popup owner cancels it. No settings are sent. */
    public static Query observe(Context context, String address, ObserverCallback callback) {
        if (context == null || callback == null) throw new IllegalArgumentException("context and callback required");
        QueryImpl query = new QueryImpl(context.getApplicationContext(), address, callback, true);
        query.start();
        return query;
    }

    private static final class QueryImpl implements Query, Runnable {
        private final Context context;
        private final String address;
        private final Callback callback;
        private final boolean observing;
        private final Handler main = new Handler(Looper.getMainLooper());
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean finished = new AtomicBoolean();
        private final Object socketLock = new Object();
        private volatile BluetoothSocket socket;
        private volatile Thread worker;
        private final Runnable deadline = new Runnable() {
            @Override public void run() {
                closeSocket();
                finishUnavailable("timeout");
            }
        };

        QueryImpl(Context context, String address, Callback callback, boolean observing) {
            this.context = context;
            this.address = address;
            this.callback = callback;
            this.observing = observing;
        }

        void start() {
            main.postDelayed(deadline, DEADLINE_MILLIS);
            worker = new Thread(this, "EarbudsBatteryQuery");
            worker.setDaemon(true);
            worker.start();
        }

        @Override public void run() {
            try {
                if (cancelled.get() || finished.get()) return;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                        && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {
                    finishUnavailable("bluetooth_permission_missing");
                    return;
                }
                if (address == null || address.trim().isEmpty()) {
                    finishUnavailable("invalid_address");
                    return;
                }
                BluetoothManager manager = context.getSystemService(BluetoothManager.class);
                BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
                if (adapter == null) {
                    finishUnavailable("bluetooth_unavailable");
                    return;
                }
                if (!adapter.isEnabled()) {
                    finishUnavailable("bluetooth_disabled");
                    return;
                }

                BluetoothDevice device = adapter.getRemoteDevice(address);
                if (device.getBondState() != BluetoothDevice.BOND_BONDED) {
                    finishUnavailable("device_not_paired");
                    return;
                }
                BluetoothSocket created = device.createRfcommSocketToServiceRecord(SPP_UUID);
                if (!installSocket(created)) return;
                created.connect();
                if (cancelled.get() || finished.get()) return;
                if (observing) {
                    main.removeCallbacks(deadline); // Only establishing the connection has a deadline.
                    Log.i(TAG, "STATUS_OBSERVER_CONNECTED");
                }

                byte[] request = BatteryProtocol.queryPacket();
                Log.d(TAG, "TX " + toHex(request));
                OutputStream output = created.getOutputStream();
                output.write(request);
                output.flush();
                if (observing) {
                    byte[] stateRequest=EarbudStateProtocol.queryPacket();
                    Log.d(TAG, "TX_STATE "+toHex(stateRequest));
                    output.write(stateRequest);
                    output.flush();
                }

                InputStream input = created.getInputStream();
                BatteryProtocol.Decoder decoder = new BatteryProtocol.Decoder();
                byte[] chunk = new byte[1024];
                int totalRead = 0;
                while (!cancelled.get() && !finished.get() && (observing || totalRead < MAX_RESPONSE_BYTES)) {
                    int count = input.read(chunk, 0, Math.min(chunk.length, MAX_RESPONSE_BYTES - totalRead));
                    if (count < 0) {
                        finishUnavailable("empty_response");
                        return;
                    }
                    if (count == 0) continue;
                    if (!observing) totalRead += count;
                    Log.d(TAG, "RX bytes=" + count); // Control streams can contain personal device names.
                    for (BatterySnapshot snapshot : decoder.feed(chunk, 0, count, System.currentTimeMillis())) {
                        if (snapshot.hasAny()) {
                            if (observing) {
                                publishBattery(snapshot);
                            } else {
                                finishBattery(snapshot);
                                return;
                            }
                        }
                    }
                    if (observing) publishData(Arrays.copyOf(chunk, count));
                }
                if (!cancelled.get() && !finished.get()) finishUnavailable("response_limit");
            } catch (SecurityException e) {
                finishUnavailable("bluetooth_permission_denied");
            } catch (IllegalArgumentException e) {
                finishUnavailable("invalid_address");
            } catch (IOException e) {
                if (!cancelled.get() && !finished.get()) {
                    String detail=String.valueOf(e.getMessage()).replaceAll("(?i)(?:[0-9a-f]{2}:){5}[0-9a-f]{2}", "[device]");
                    Log.w(TAG, "SPP_READ_FAILED "+detail);
                    finishUnavailable("connection_or_read_failed");
                }
            } catch (RuntimeException e) {
                if (!cancelled.get() && !finished.get()) finishUnavailable("bluetooth_query_failed");
            } finally {
                closeSocket();
            }
        }

        private boolean installSocket(BluetoothSocket created) {
            synchronized (socketLock) {
                if (cancelled.get() || finished.get()) {
                    closeQuietly(created);
                    return false;
                }
                socket = created;
                return true;
            }
        }

        private void publishBattery(final BatterySnapshot snapshot) {
            main.post(() -> { if (!cancelled.get()) callback.onBattery(snapshot); });
        }

        private void publishData(final byte[] bytes) {
            main.post(() -> {
                if (!cancelled.get()) ((ObserverCallback) callback).onData(bytes);
            });
        }

        private void finishBattery(final BatterySnapshot snapshot) {
            if (!finished.compareAndSet(false, true)) return;
            main.removeCallbacks(deadline);
            closeSocket();
            main.post(new Runnable() {
                @Override public void run() {
                    if (!cancelled.get()) callback.onBattery(snapshot);
                }
            });
        }

        private void finishUnavailable(final String reason) {
            if (!finished.compareAndSet(false, true)) return;
            main.removeCallbacks(deadline);
            closeSocket();
            main.post(new Runnable() {
                @Override public void run() {
                    if (!cancelled.get()) callback.onUnavailable(reason);
                }
            });
        }

        @Override public void cancel() {
            cancelled.set(true);
            finished.compareAndSet(false, true);
            main.removeCallbacks(deadline);
            closeSocket();
            Thread current = worker;
            if (current != null) current.interrupt();
        }

        private void closeSocket() {
            synchronized (socketLock) {
                BluetoothSocket current = socket;
                socket = null;
                closeQuietly(current);
            }
        }
    }

    private static void closeQuietly(BluetoothSocket socket) {
        if (socket == null) return;
        try { socket.close(); } catch (IOException ignored) { }
    }

    private static String toHex(byte[] bytes) {
        return toHex(bytes, bytes.length);
    }

    private static String toHex(byte[] bytes, int length) {
        StringBuilder hex = new StringBuilder(length * 2);
        for (int i = 0; i < length; i++) {
            hex.append(Character.forDigit((bytes[i] >>> 4) & 0x0f, 16));
            hex.append(Character.forDigit(bytes[i] & 0x0f, 16));
        }
        return hex.toString();
    }
}
