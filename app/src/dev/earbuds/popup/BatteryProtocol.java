package dev.earbuds.popup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Read-only Huawei SPP battery framing and response parser. */
public final class BatteryProtocol {
    private static final int MAX_FRAME_BYTES = 8192;
    private static final byte[] QUERY = new byte[] {
            0x5a, 0x00, 0x09, 0x00, 0x01, 0x08, 0x01, 0x00, 0x02, 0x00, 0x03, 0x00,
            (byte) 0xfb, (byte) 0xb9
    };

    private BatteryProtocol() { }

    /** Returns a defensive copy of the only command this client is allowed to send. */
    public static byte[] queryPacket() {
        return QUERY.clone();
    }

    /** Incremental parser for arbitrarily fragmented or concatenated SPP frames. */
    public static final class Decoder {
        private byte[] pending = new byte[256];
        private int size;

        public List<BatterySnapshot> feed(byte[] input, int offset, int length, long nowMillis) {
            if (input == null || offset < 0 || length < 0 || offset > input.length - length) {
                return Collections.emptyList();
            }
            if (length == 0) return Collections.emptyList();
            ArrayList<BatterySnapshot> decoded = new ArrayList<>();
            int cursor = offset;
            int remaining = length;
            while (remaining > 0) {
                ensureCapacity(Math.min(MAX_FRAME_BYTES, size + remaining));
                int room = pending.length - size;
                if (room == 0) {
                    discardPrefix(Math.max(1, size - 3));
                    room = pending.length - size;
                }
                int copied = Math.min(room, remaining);
                System.arraycopy(input, cursor, pending, size, copied);
                size += copied;
                cursor += copied;
                remaining -= copied;
                parseAvailable(decoded, nowMillis);
                if (size == MAX_FRAME_BYTES) discardPrefix(Math.max(1, size - 3));
            }
            return decoded;
        }

        private void parseAvailable(ArrayList<BatterySnapshot> decoded, long nowMillis) {
            while (size > 0) {
                int marker = 0;
                while (marker < size && (pending[marker] & 0xff) != 0x5a) marker++;
                if (marker > 0) discardPrefix(marker);
                if (size < 4) return;

                if (size < 6) return;
                if (pending[3] != 0 || !isBatteryCommand(pending[4], pending[5])) {
                    discardPrefix(1);
                    continue;
                }
                int fieldLength = ((pending[1] & 0xff) << 8) | (pending[2] & 0xff);
                int totalLength = fieldLength + 5;
                if (fieldLength < 3 || totalLength < 8 || totalLength > MAX_FRAME_BYTES) {
                    discardPrefix(1);
                    continue;
                }
                if (size < totalLength) return;

                if (!hasValidCrc(pending, totalLength)) {
                    discardPrefix(1);
                    continue;
                }
                BatterySnapshot snapshot = parseBatteryFrame(pending, totalLength, nowMillis);
                if (snapshot != null) decoded.add(snapshot);
                discardPrefix(totalLength);
            }
        }

        private void ensureCapacity(int required) {
            int bounded = Math.min(MAX_FRAME_BYTES, Math.max(1, required));
            if (bounded <= pending.length) return;
            int next = Math.min(MAX_FRAME_BYTES, Math.max(bounded, pending.length * 2));
            byte[] expanded = new byte[next];
            System.arraycopy(pending, 0, expanded, 0, size);
            pending = expanded;
        }

        private void discardPrefix(int count) {
            int removed = Math.min(count, size);
            if (removed <= 0) return;
            System.arraycopy(pending, removed, pending, 0, size - removed);
            size -= removed;
        }
    }

    private static BatterySnapshot parseBatteryFrame(byte[] frame, int totalLength, long nowMillis) {
        if (totalLength < 8 || !isBatteryCommand(frame[4], frame[5])) return null;
        int crcOffset = totalLength - 2;
        int position = 6;
        int overall = -1;
        int left = -1;
        int right = -1;
        int caseLevel = -1;
        int leftCharging = -1;
        int rightCharging = -1;
        int caseCharging = -1;

        while (position < crcOffset) {
            if (position + 2 > crcOffset) return null;
            int parameter = frame[position] & 0xff;
            int valueLength = frame[position + 1] & 0xff;
            int valueOffset = position + 2;
            if (valueOffset + valueLength > crcOffset) return null;

            if (parameter == 0x01 && valueLength == 1) {
                overall = levelOrUnknown(frame[valueOffset] & 0xff);
            } else if (parameter == 0x02 && valueLength == 3) {
                left = levelOrUnknown(frame[valueOffset] & 0xff);
                right = levelOrUnknown(frame[valueOffset + 1] & 0xff);
                caseLevel = levelOrUnknown(frame[valueOffset + 2] & 0xff);
            } else if (parameter == 0x03 && valueLength == 3) {
                leftCharging = chargeOrUnknown(frame[valueOffset] & 0xff);
                rightCharging = chargeOrUnknown(frame[valueOffset + 1] & 0xff);
                caseCharging = chargeOrUnknown(frame[valueOffset + 2] & 0xff);
            }
            position = valueOffset + valueLength;
        }

        BatterySnapshot snapshot = new BatterySnapshot(left, right, caseLevel, overall,
                leftCharging, rightCharging, caseCharging, nowMillis);
        return snapshot.hasAny() ? snapshot : null;
    }

    private static int levelOrUnknown(int value) {
        return value >= 0 && value <= 100 ? value : -1;
    }

    private static boolean isBatteryCommand(byte commandHigh, byte commandLow) {
        return (commandHigh & 0xff) == 0x01
                && ((commandLow & 0xff) == 0x08 || (commandLow & 0xff) == 0x27);
    }

    private static int chargeOrUnknown(int value) {
        return value == 0 || value == 1 ? value : -1;
    }

    private static boolean hasValidCrc(byte[] frame, int totalLength) {
        int expected = ((frame[totalLength - 2] & 0xff) << 8) | (frame[totalLength - 1] & 0xff);
        return crc16Xmodem(frame, totalLength - 2) == expected;
    }

    private static int crc16Xmodem(byte[] bytes, int length) {
        int crc = 0;
        for (int index = 0; index < length; index++) {
            crc ^= (bytes[index] & 0xff) << 8;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 0x8000) != 0 ? (crc << 1) ^ 0x1021 : crc << 1;
                crc &= 0xffff;
            }
        }
        return crc;
    }
}
