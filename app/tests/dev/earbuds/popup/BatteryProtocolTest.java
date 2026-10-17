package dev.earbuds.popup;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class BatteryProtocolTest {
    private static final byte[] SAMPLE = hex("5a0014000108010140020310203003030001000402140a1461");

    private static void expect(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static byte[] hex(String value) {
        byte[] result = new byte[value.length() / 2];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return result;
    }

    private static String hex(byte[] value) {
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte item : value) result.append(String.format("%02x", item & 0xff));
        return result.toString();
    }

    private static byte[] batteryFrame(int overall, int left, int right, int caseLevel,
                                       int leftCharging, int rightCharging, int caseCharging) {
        return batteryFrame(0x08, overall, left, right, caseLevel, leftCharging, rightCharging, caseCharging);
    }

    private static byte[] batteryFrame(int command, int overall, int left, int right, int caseLevel,
                                       int leftCharging, int rightCharging, int caseCharging) {
        byte[] body = new byte[] {
                0x01, (byte) command,
                0x01, 0x01, (byte) overall,
                0x02, 0x03, (byte) left, (byte) right, (byte) caseLevel,
                0x03, 0x03, (byte) leftCharging, (byte) rightCharging, (byte) caseCharging
        };
        byte[] packet = new byte[body.length + 6];
        packet[0] = 0x5a;
        int fieldLength = body.length + 1;
        packet[1] = (byte) (fieldLength >>> 8);
        packet[2] = (byte) fieldLength;
        packet[3] = 0;
        System.arraycopy(body, 0, packet, 4, body.length);
        int crc = crc16Xmodem(packet, packet.length - 2);
        packet[packet.length - 2] = (byte) (crc >>> 8);
        packet[packet.length - 1] = (byte) crc;
        return packet;
    }

    private static int crc16Xmodem(byte[] bytes, int count) {
        int crc = 0;
        for (int i = 0; i < count; i++) {
            crc ^= (bytes[i] & 0xff) << 8;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 0x8000) != 0 ? ((crc << 1) ^ 0x1021) : (crc << 1);
                crc &= 0xffff;
            }
        }
        return crc;
    }

    private static List<BatterySnapshot> feed(BatteryProtocol.Decoder decoder, byte[] bytes, long now) {
        return decoder.feed(bytes, 0, bytes.length, now);
    }

    public static void main(String[] args) {
        expect("5a0009000108010002000300fbb9".equals(hex(BatteryProtocol.queryPacket())),
                "query must match the reviewed read-only battery packet and CRC");

        BatteryProtocol.Decoder fragmented = new BatteryProtocol.Decoder();
        expect(feed(fragmented, Arrays.copyOfRange(SAMPLE, 0, 7), 10_000L).isEmpty(),
                "partial response must wait for the remainder");
        List<BatterySnapshot> parsed = feed(fragmented, Arrays.copyOfRange(SAMPLE, 7, SAMPLE.length), 10_000L);
        expect(parsed.size() == 1, "fragmented response must produce one snapshot");
        BatterySnapshot snapshot = parsed.get(0);
        expect(snapshot.overall == 64 && snapshot.left == 16 && snapshot.right == 32 && snapshot.caseLevel == 48,
                "generic fixture levels must map overall/L/R/case without substitution");
        expect(snapshot.leftCharging == 0 && snapshot.rightCharging == 1 && snapshot.caseCharging == 0,
                "three charging flags must remain component-specific");
        expect(snapshot.updatedAtMillis == 10_000L && snapshot.hasComponents() && snapshot.hasAny(),
                "snapshot must retain timestamp and level availability");
        expect(snapshot.isFresh(10_001L) && !snapshot.isFresh(70_001L), "freshness boundary must be bounded");

        BatteryProtocol.Decoder notify = new BatteryProtocol.Decoder();
        List<BatterySnapshot> notified = feed(notify,
                batteryFrame(0x27, 55, 44, 33, 22, 1, 0, 1), 11_000L);
        expect(notified.size() == 1 && notified.get(0).overall == 55 && notified.get(0).left == 44
                        && notified.get(0).right == 33 && notified.get(0).caseLevel == 22,
                "CRC-valid 01/27 notification must use the same battery TLV mapping");

        BatteryProtocol.Decoder concatenated = new BatteryProtocol.Decoder();
        byte[] twice = new byte[SAMPLE.length * 2];
        System.arraycopy(SAMPLE, 0, twice, 0, SAMPLE.length);
        System.arraycopy(SAMPLE, 0, twice, SAMPLE.length, SAMPLE.length);
        expect(feed(concatenated, twice, 20_000L).size() == 2, "concatenated frames must both parse");

        BatteryProtocol.Decoder withNoise = new BatteryProtocol.Decoder();
        byte[] noisy = new byte[SAMPLE.length + 3];
        noisy[0] = 0x00;
        noisy[1] = 0x11;
        noisy[2] = 0x5b;
        System.arraycopy(SAMPLE, 0, noisy, 3, SAMPLE.length);
        expect(feed(withNoise, noisy, 30_000L).size() == 1, "noise before a valid frame must be skipped");

        byte[] badCrc = SAMPLE.clone();
        badCrc[badCrc.length - 1] ^= 1;
        BatteryProtocol.Decoder crcRecovery = new BatteryProtocol.Decoder();
        byte[] crcThenValid = new byte[badCrc.length + SAMPLE.length];
        System.arraycopy(badCrc, 0, crcThenValid, 0, badCrc.length);
        System.arraycopy(SAMPLE, 0, crcThenValid, badCrc.length, SAMPLE.length);
        expect(feed(crcRecovery, crcThenValid, 40_000L).size() == 1,
                "bad CRC must be rejected while later valid frames recover");

        byte[] impossibleLength = SAMPLE.clone();
        impossibleLength[1] = 0x7f;
        impossibleLength[2] = (byte) 0xff;
        BatteryProtocol.Decoder lengthRecovery = new BatteryProtocol.Decoder();
        byte[] lengthThenValid = new byte[impossibleLength.length + SAMPLE.length];
        System.arraycopy(impossibleLength, 0, lengthThenValid, 0, impossibleLength.length);
        System.arraycopy(SAMPLE, 0, lengthThenValid, impossibleLength.length, SAMPLE.length);
        expect(feed(lengthRecovery, lengthThenValid, 50_000L).size() == 1,
                "oversize length must resynchronize instead of blocking the following frame");

        BatteryProtocol.Decoder levels = new BatteryProtocol.Decoder();
        List<BatterySnapshot> zeroHundred = feed(levels, batteryFrame(0, 100, 0, 100, 1, 0, 1), 60_000L);
        expect(zeroHundred.size() == 1 && zeroHundred.get(0).overall == 0 && zeroHundred.get(0).left == 100
                        && zeroHundred.get(0).right == 0 && zeroHundred.get(0).caseLevel == 100,
                "zero and one hundred are valid values");

        BatteryProtocol.Decoder sentinel = new BatteryProtocol.Decoder();
        List<BatterySnapshot> sentinelResult = feed(sentinel, batteryFrame(101, 255, 70, 101, 255, 0, 1), 70_000L);
        expect(sentinelResult.size() == 1, "valid charging data can accompany unavailable level fields");
        BatterySnapshot partial = sentinelResult.get(0);
        expect(partial.overall == -1 && partial.left == -1 && partial.right == 70 && partial.caseLevel == -1,
                "invalid and sentinel values must stay unknown without hiding valid siblings");
        expect(partial.leftCharging == -1 && partial.rightCharging == 0 && partial.caseCharging == 1,
                "unknown charging flags must not become false");

        BatteryProtocol.Decoder legacy = new BatteryProtocol.Decoder();
        byte[] legacyOverallOnly = hex("5a0009000108010140030100ed2e");
        List<BatterySnapshot> legacyParsed = feed(legacy, legacyOverallOnly, 80_000L);
        expect(legacyParsed.size() == 1 && legacyParsed.get(0).overall == 64
                        && legacyParsed.get(0).left == -1 && legacyParsed.get(0).right == -1
                        && legacyParsed.get(0).caseLevel == -1 && !legacyParsed.get(0).hasComponents(),
                "legacy aggregate-only fixture must not be copied into component fields");

        BatterySnapshot unknown = BatterySnapshot.unknown();
        expect(unknown.left == -1 && unknown.right == -1 && unknown.caseLevel == -1 && unknown.overall == -1
                        && unknown.leftCharging == -1 && unknown.rightCharging == -1 && unknown.caseCharging == -1
                        && !unknown.hasAny() && !unknown.isFresh(90_000L),
                "unknown snapshot must represent unavailable levels and charge states");

        System.out.println("PASS: query, CRC, 01/08 + 01/27 TLVs, fragmentation, concatenation, noise, length, sentinels, legacy fields, and freshness");
        System.out.println("NOTE: response fixtures are generic protocol fixtures, not FreeBuds 6i captures");
    }
}
