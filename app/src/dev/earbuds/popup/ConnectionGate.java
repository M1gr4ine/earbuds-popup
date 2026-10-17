package dev.earbuds.popup;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Set;
import java.util.Map;
public final class ConnectionGate {
    private final Set<String> connected = new HashSet<>();
    private final Map<String,Long> shown = new HashMap<>();
    public synchronized boolean connected(String address, long nowMs) {
        if (address == null || address.isEmpty() || !connected.add(address)) return false;
        Long previous=shown.get(address);
        if(previous != null && nowMs-previous < 1800) return false;
        shown.put(address,nowMs);
        return true;
    }
    public synchronized void disconnected(String address) { connected.remove(address); }
}
