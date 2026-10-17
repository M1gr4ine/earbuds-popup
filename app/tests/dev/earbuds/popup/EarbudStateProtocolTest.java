package dev.earbuds.popup;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class EarbudStateProtocolTest {
    private static void expect(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static byte[] frame(int commandHigh, int commandLow, byte... tlvs) {
        byte[] body = new byte[2 + tlvs.length];
        body[0] = (byte) commandHigh;
        body[1] = (byte) commandLow;
        System.arraycopy(tlvs, 0, body, 2, tlvs.length);
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
                crc = (crc & 0x8000) != 0 ? ((crc << 1) ^ 0x1021) : crc << 1;
                crc &= 0xffff;
            }
        }
        return crc;
    }

    private static byte[] hex(String value) {
        byte[] bytes = new byte[value.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }

    private static byte[] publicQueryPacket() {
        return EarbudStateProtocol.queryPacket();
    }

    private static List<EarbudStateProtocol.State> feed(
            EarbudStateProtocol.Decoder decoder, byte[] bytes) {
        return decoder.feed(bytes, 0, bytes.length);
    }

    public static void main(String[] args) {
        byte[] bothSides = frame(0x2b, 0x03,
                (byte) 0x08, (byte) 0x01, (byte) 0x01,
                (byte) 0x09, (byte) 0x01, (byte) 0x00);
        EarbudStateProtocol.Decoder decoder = new EarbudStateProtocol.Decoder();
        List<EarbudStateProtocol.State> states = feed(decoder, bothSides);
        expect(states.size() == 1, "2B/03 state packet must decode once");
        expect(states.get(0).leftWorn == 1 && states.get(0).rightWorn == 0,
                "TLV 08 is left and TLV 09 is right; one means in-ear and zero means out");
        expect(states.get(0).anyWorn(), "anyWorn must be true when either known side is in-ear");

        byte[] expectedQuery = hex("5a0005002b250300082d");
        byte[] query = publicQueryPacket();
        expect(Arrays.equals(expectedQuery, query),
                "2B/25 read request must be framed with the exact XMODEM CRC");
        int queryCrc = ((query[query.length - 2] & 0xff) << 8) | (query[query.length - 1] & 0xff);
        expect(crc16Xmodem(query, query.length - 2) == queryCrc,
                "2B/25 read request CRC must validate");
        query[0] = 0;
        expect(Arrays.equals(expectedQuery, publicQueryPacket()),
                "queryPacket must return a defensive copy");

        byte[][] real2b25Frames = {
                hex("5a000f002b25010101020100030100040101495b"),
                hex("5a000f002b25010100020100030100040101261e"),
                hex("5a000f002b2501010002010003010104010150aa")
        };
        int[][] expected2b25Wear = {{1, 0}, {0, 0}, {0, 0}};
        EarbudStateProtocol.Decoder twoB25Decoder = new EarbudStateProtocol.Decoder();
        List<EarbudStateProtocol.State> last2b25States = Collections.emptyList();
        for (int i = 0; i < real2b25Frames.length; i++) {
            last2b25States = feed(twoB25Decoder, real2b25Frames[i]);
            expect(last2b25States.size() == 1, "each real 2B/25 device response must decode once");
            expect(last2b25States.get(0).leftWorn == expected2b25Wear[i][0]
                            && last2b25States.get(0).rightWorn == expected2b25Wear[i][1],
                    "2B/25 TLV 01/02 are left/right wear state");
        }
        expect(!last2b25States.get(0).anyWorn(),
                "2B/25 TLV 03/04 reporting both earbuds in the box must not imply worn or lid state");

        EarbudStateProtocol.Decoder fragmented2b25 = new EarbudStateProtocol.Decoder();
        expect(feed(fragmented2b25, Arrays.copyOfRange(real2b25Frames[0], 0, 7)).isEmpty(),
                "partial 2B/25 frame must not emit state");
        List<EarbudStateProtocol.State> completed2b25 = feed(fragmented2b25,
                Arrays.copyOfRange(real2b25Frames[0], 7, real2b25Frames[0].length));
        expect(completed2b25.size() == 1 && completed2b25.get(0).leftWorn == 1
                        && completed2b25.get(0).rightWorn == 0,
                "fragmented 2B/25 response must decode after the final bytes arrive");

        byte[] mixedCommands = new byte[real2b25Frames[0].length + bothSides.length];
        System.arraycopy(real2b25Frames[0], 0, mixedCommands, 0, real2b25Frames[0].length);
        System.arraycopy(bothSides, 0, mixedCommands, real2b25Frames[0].length, bothSides.length);
        List<EarbudStateProtocol.State> mixedStates = feed(new EarbudStateProtocol.Decoder(), mixedCommands);
        expect(mixedStates.size() == 2 && mixedStates.get(0).leftWorn == 1
                        && mixedStates.get(0).rightWorn == 0 && mixedStates.get(1).leftWorn == 1,
                "concatenated 2B/25 and 2B/03 events must retain their command-specific mappings");

        byte[] bad2b25Crc = real2b25Frames[0].clone();
        bad2b25Crc[bad2b25Crc.length - 1] ^= 1;
        expect(feed(new EarbudStateProtocol.Decoder(), bad2b25Crc).isEmpty(),
                "bad-CRC 2B/25 frame must not produce state");
        expect(feed(new EarbudStateProtocol.Decoder(), frame(0x2b, 0x25,
                        (byte) 0x01, (byte) 0x02, (byte) 0x00, (byte) 0x01)).isEmpty(),
                "known 2B/25 TLV with an unknown length must not produce state");
        expect(feed(new EarbudStateProtocol.Decoder(), frame(0x2b, 0x25,
                        (byte) 0x01, (byte) 0x01, (byte) 0x02)).isEmpty(),
                "unknown 2B/25 wear value must not be interpreted as worn or out");
        expect(feed(new EarbudStateProtocol.Decoder(), frame(0x2b, 0x25,
                        (byte) 0x03, (byte) 0x01, (byte) 0x01,
                        (byte) 0x04, (byte) 0x01, (byte) 0x01)).isEmpty(),
                "box-only TLVs must not create a wear or lid state");
        List<EarbudStateProtocol.State> unknownTlvState = feed(new EarbudStateProtocol.Decoder(),
                frame(0x2b, 0x25,
                        (byte) 0x01, (byte) 0x01, (byte) 0x01,
                        (byte) 0x02, (byte) 0x01, (byte) 0x00,
                        (byte) 0x7f, (byte) 0x02, (byte) 0x55, (byte) 0x66,
                        (byte) 0x03, (byte) 0x01, (byte) 0x01,
                        (byte) 0x04, (byte) 0x01, (byte) 0x01));
        expect(unknownTlvState.size() == 1 && unknownTlvState.get(0).leftWorn == 1
                        && unknownTlvState.get(0).rightWorn == 0,
                "unknown TLVs must be skipped without changing 2B/25 wear mapping");

        byte[] leftOnly = frame(0x2b, 0x03,
                (byte) 0x08, (byte) 0x01, (byte) 0x00);
        EarbudStateProtocol.Decoder fragmented = new EarbudStateProtocol.Decoder();
        expect(feed(fragmented, Arrays.copyOfRange(leftOnly, 0, 5)).isEmpty(),
                "partial frame must not emit state");
        List<EarbudStateProtocol.State> leftState = feed(fragmented,
                Arrays.copyOfRange(leftOnly, 5, leftOnly.length));
        expect(leftState.size() == 1 && leftState.get(0).leftWorn == 0
                        && leftState.get(0).rightWorn == -1 && !leftState.get(0).anyWorn(),
                "a missing side must stay unknown and must not imply a worn state");

        byte[] rightOnly = frame(0x2b, 0x03,
                (byte) 0x09, (byte) 0x01, (byte) 0x01);
        byte[] concatenated = new byte[bothSides.length + rightOnly.length + 2];
        concatenated[0] = 0x00;
        concatenated[1] = 0x7f;
        System.arraycopy(bothSides, 0, concatenated, 2, bothSides.length);
        System.arraycopy(rightOnly, 0, concatenated, 2 + bothSides.length, rightOnly.length);
        EarbudStateProtocol.Decoder stream = new EarbudStateProtocol.Decoder();
        List<EarbudStateProtocol.State> streamStates = feed(stream, concatenated);
        expect(streamStates.size() == 2, "noise and concatenated frames must resynchronize and decode");
        expect(streamStates.get(1).leftWorn == -1 && streamStates.get(1).rightWorn == 1
                        && streamStates.get(1).anyWorn(),
                "right-only event must not fabricate a left value");

        byte[] falseStart = new byte[6 + bothSides.length];
        falseStart[0] = 0x5a;
        falseStart[1] = 0;
        falseStart[2] = 0x40;
        falseStart[3] = 0;
        falseStart[4] = 0x2b;
        falseStart[5] = 0x03;
        System.arraycopy(bothSides, 0, falseStart, 6, bothSides.length);
        List<EarbudStateProtocol.State> recovered = feed(
                new EarbudStateProtocol.Decoder(), falseStart);
        expect(recovered.size() == 1 && recovered.get(0).leftWorn == 1,
                "a false incomplete frame header must not block a later complete state event");

        byte[] batteryCommand = frame(0x01, 0x27,
                (byte) 0x08, (byte) 0x01, (byte) 0x01);
        byte[] badCrc = leftOnly.clone();
        badCrc[badCrc.length - 1] ^= 1;
        byte[] wrongFrames = new byte[batteryCommand.length + badCrc.length];
        System.arraycopy(batteryCommand, 0, wrongFrames, 0, batteryCommand.length);
        System.arraycopy(badCrc, 0, wrongFrames, batteryCommand.length, badCrc.length);
        expect(feed(new EarbudStateProtocol.Decoder(), wrongFrames).isEmpty(),
                "wrong command and bad CRC must never produce a wear state");

        expect(feed(new EarbudStateProtocol.Decoder(), frame(0x2b, 0x03,
                        (byte) 0x08, (byte) 0x01, (byte) 0x02)).isEmpty(),
                "unknown state values must not be interpreted as in or out");
        expect(feed(new EarbudStateProtocol.Decoder(), frame(0x2b, 0x03,
                        (byte) 0x08, (byte) 0x02, (byte) 0x00, (byte) 0x01)).isEmpty(),
                "malformed known TLV lengths must not produce a state");
        expect(feed(new EarbudStateProtocol.Decoder(), frame(0x2b, 0x03)).isEmpty(),
                "2B/03 with no known state fields must not imply a state or lid status");

        EarbudStateProtocol.Decoder invalidInput = new EarbudStateProtocol.Decoder();
        expect(invalidInput.feed(null, 0, 0).isEmpty(), "null input must be ignored");
        expect(invalidInput.feed(leftOnly, -1, 1).isEmpty(), "invalid offset must be ignored");
        expect(invalidInput.feed(leftOnly, 0, leftOnly.length + 1).isEmpty(),
                "invalid input range must be ignored");

        System.out.println("PASS: 2B/03 and 2B/25 wear TLVs, query CRC/copy, malformed fields, and stream framing");
        System.out.println("NOTE: 2B/25 TLV 03/04 are in-box flags; the packet does not establish case-lid state");
    }
}
