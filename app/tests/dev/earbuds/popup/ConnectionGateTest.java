package dev.earbuds.popup;
public final class ConnectionGateTest {
    private static void expect(boolean value,String message) { if(!value) throw new AssertionError(message); }
    public static void main(String[] args) {
        ConnectionGate gate=new ConnectionGate();
        expect(gate.connected("headset",1000),"First connection must display a popup");
        expect(!gate.connected("headset",1200),"ACL and audio-profile events must not display duplicate popups");
        gate.disconnected("headset");
        expect(!gate.connected("headset",1400),"Bouncing link must not flash a second popup");
        gate.disconnected("headset");
        expect(gate.connected("headset",5000),"A later genuine reconnection must display again");
        expect(gate.connected("other",5100),"Different selected device must not inherit stale connection state");
        expect(!gate.connected(null,6000),"Missing device identity must not trigger a popup");
        expect(!gate.connected("",6000),"Empty device identity must not trigger a popup");
        System.out.println("PASS: first connection, duplicate profiles, bouncing link, reconnection, and device identity");
    }
}
