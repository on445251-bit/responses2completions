package io.r2c.android;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

/** Small network helpers used by the UI and the notification text. */
public final class NetUtils {

    private NetUtils() {}

    /**
     * Returns the device's first non-loopback IPv4 address, or null when offline.
     *
     * <p>Needed because when the proxy binds 0.0.0.0 the user needs a LAN address to
     * put in their client, not 127.0.0.1.
     */
    public static String localIpv4() {
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                for (InetAddress addr : Collections.list(nif.getInetAddresses())) {
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        String host = addr.getHostAddress();
                        if (host != null && !host.startsWith("127.")) return host;
                    }
                }
            }
        } catch (Exception ignored) {
            // Fall through to null: the UI then shows the loopback form.
        }
        return null;
    }
}