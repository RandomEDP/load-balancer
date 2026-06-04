package loadbalancer;

import java.net.InetSocketAddress;

/** A parsed "host:port" backend target. */
public record HostPort(String host, int port) {

    /** Parses "host:port" (e.g. "127.0.0.1:9001"). */
    public static HostPort parse(String spec) {
        int idx = spec.lastIndexOf(':');
        if (idx <= 0 || idx == spec.length() - 1) {
            throw new IllegalArgumentException("Invalid backend '" + spec + "', expected host:port");
        }
        String host = spec.substring(0, idx).trim();
        int port;
        try {
            port = Integer.parseInt(spec.substring(idx + 1).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid port in backend '" + spec + "'");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Port out of range in backend '" + spec + "'");
        }
        return new HostPort(host, port);
    }

    public InetSocketAddress toSocketAddress() {
        return new InetSocketAddress(host, port);
    }

    @Override
    public String toString() {
        return host + ":" + port;
    }
}
