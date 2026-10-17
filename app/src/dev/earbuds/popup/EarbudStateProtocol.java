package dev.earbuds.popup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Read-only parser for Huawei SPP in-ear state notifications. */
public final class EarbudStateProtocol {
    private static final int MAX_FRAME_BYTES = 8192;
    private static final byte[] BOX_STATUS_QUERY = {
            0x5a, 0x00, 0x05, 0x00, 0x2b, 0x25, 0x03, 0x00, 0x08, 0x2d
    };

    private EarbudStateProtocol() { }

    /** Returns a framed, read-only 2B/25 box/wear status query. */
    public static byte[] queryPacket() {
        return BOX_STATUS_QUERY.clone();
    }

    /** A partial state is allowed; -1 means that side was not present in this event. */
    public static final class State {
        public final int leftWorn;
        public final int rightWorn;

        private State(int leftWorn, int rightWorn) {
            this.leftWorn = leftWorn;
            this.rightWorn = rightWorn;
        }

        /** Returns true only when a valid event says at least one side is in-ear. */
        public boolean anyWorn() {
            return leftWorn == 1 || rightWorn == 1;
        }
    }

    /** Incremental parser for fragmented and concatenated SPP frames. */
    public static final class Decoder {
        private byte[] pending = new byte[256];
        private int size;

        public List<State> feed(byte[] input, int offset, int length) {
            if (input == null || offset < 0 || length < 0 || offset > input.length - length) {
                return Collections.emptyList();
            }
            if (length == 0) return Collections.emptyList();

            ArrayList<State> decoded = new ArrayList<>();
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
                parseAvailable(decoded);

                if (size == MAX_FRAME_BYTES) discardPrefix(Math.max(1, size - 3));
            }
            return decoded;
        }

        private void parseAvailable(ArrayList<State> decoded) {
            while (size > 0) {
                int marker = 0;
                while (marker < size && (pending[marker] & 0xff) != 0x5a) marker++;
                if (marker > 0) discardPrefix(marker);
                if (size < 4) return;

                int fieldLength = ((pending[1] & 0xff) << 8) | (pending[2] & 0xff);
                int totalLength = fieldLength + 5;
                if (pending[3] != 0 || fieldLength < 3 || totalLength > MAX_FRAME_BYTES) {
                    discardPrefix(1);
                    continue;
                }
                if (size < 6) return;
                if (!isStateCommand(pending[4], pending[5])) {
                    discardPrefix(1);
                    continue;
                }
                if (size < totalLength) {
                    int laterFrame = findLaterCompleteFrame();
                    if (laterFrame < 0) return;
                    discardPrefix(laterFrame);
                    continue;
                }

                if (!hasValidCrc(pending, totalLength)) {
                    discardPrefix(1);
                    continue;
                }
                State state = parseStateFrame(pending, totalLength);
                if (state != null) decoded.add(state);
                discardPrefix(totalLength);
            }
        }

        private int findLaterCompleteFrame() {
            for (int start = 1; start + 6 <= size; start++) {
                if ((pending[start] & 0xff) != 0x5a || pending[start + 3] != 0
                        || !isStateCommand(pending[start + 4], pending[start + 5])) {
                    continue;
                }
                int fieldLength = ((pending[start + 1] & 0xff) << 8)
                        | (pending[start + 2] & 0xff);
                int totalLength = fieldLength + 5;
                if (fieldLength < 3 || totalLength > MAX_FRAME_BYTES
                        || start + totalLength > size) {
                    continue;
                }
                if (hasValidCrcAt(pending, start, totalLength)) return start;
            }
            return -1;
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
            int remaining = size - removed;
            if (remaining > 0) System.arraycopy(pending, removed, pending, 0, remaining);
            size = remaining;
        }
    }

    private static State parseStateFrame(byte[] frame, int totalLength) {
        if (totalLength < 8 || !isStateCommand(frame[4], frame[5])) return null;

        boolean boxStatus = (frame[5] & 0xff) == 0x25;
        int leftWearParameter = boxStatus ? 0x01 : 0x08;
        int rightWearParameter = boxStatus ? 0x02 : 0x09;

        int crcOffset = totalLength - 2;
        int position = 6;
        int left = -1;
        int right = -1;
        boolean leftSeen = false;
        boolean rightSeen = false;
        boolean leftBoxSeen = false;
        boolean rightBoxSeen = false;

        while (position < crcOffset) {
            if (position + 2 > crcOffset) return null;
            int parameter = frame[position] & 0xff;
            int valueLength = frame[position + 1] & 0xff;
            int valueOffset = position + 2;
            if (valueOffset + valueLength > crcOffset) return null;

            boolean knownParameter = parameter == leftWearParameter || parameter == rightWearParameter
                    || (boxStatus && (parameter == 0x03 || parameter == 0x04));
            if (knownParameter) {
                if (valueLength != 1) return null;
                int value = frame[valueOffset] & 0xff;
                if (value != 0 && value != 1) return null;
                if (parameter == leftWearParameter) {
                    if (leftSeen) return null;
                    left = value;
                    leftSeen = true;
                } else if (parameter == rightWearParameter) {
                    if (rightSeen) return null;
                    right = value;
                    rightSeen = true;
                } else if (parameter == 0x03) {
                    if (leftBoxSeen) return null;
                    leftBoxSeen = true;
                } else {
                    if (rightBoxSeen) return null;
                    rightBoxSeen = true;
                }
            }
            position = valueOffset + valueLength;
        }

        if (!leftSeen && !rightSeen) return null;
        return new State(left, right);
    }

    private static boolean isStateCommand(byte commandHigh, byte commandLow) {
        return (commandHigh & 0xff) == 0x2b
                && ((commandLow & 0xff) == 0x03 || (commandLow & 0xff) == 0x25);
    }

    private static boolean hasValidCrc(byte[] frame, int totalLength) {
        int expected = ((frame[totalLength - 2] & 0xff) << 8)
                | (frame[totalLength - 1] & 0xff);
        return crc16Xmodem(frame, 0, totalLength - 2) == expected;
    }

    private static boolean hasValidCrcAt(byte[] frame, int offset, int totalLength) {
        int crcOffset = offset + totalLength - 2;
        int expected = ((frame[crcOffset] & 0xff) << 8) | (frame[crcOffset + 1] & 0xff);
        return crc16Xmodem(frame, offset, totalLength - 2) == expected;
    }

    private static int crc16Xmodem(byte[] bytes, int offset, int length) {
        int crc = 0;
        for (int index = offset; index < offset + length; index++) {
            crc ^= (bytes[index] & 0xff) << 8;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 0x8000) != 0 ? (crc << 1) ^ 0x1021 : crc << 1;
                crc &= 0xffff;
            }
        }
        return crc;
    }
}
