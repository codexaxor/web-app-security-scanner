package com.company.secscan.auth;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;

/**
 * The client IP for rate limiting (D-098). {@code X-Forwarded-For} is honoured ONLY when the direct TCP peer is a
 * trusted proxy ({@code SECSCAN_TRUSTED_PROXIES}, a comma-separated CIDR list; default loopback only) — otherwise
 * a client could pick its own rate-limit key. The ui nginx overwrites the header with {@code $remote_addr}, so the
 * rightmost entry is the address the trusted proxy saw. A malformed entry falls back to the peer address.
 */
@Component
public class ClientIpResolver {

    private static final Pattern IP_LITERAL = Pattern.compile("^[0-9A-Fa-f:.]{2,45}$");

    private final List<IpAddressMatcher> trusted = new ArrayList<>();

    public ClientIpResolver(@Value("${SECSCAN_TRUSTED_PROXIES:127.0.0.1/32,::1/128}") String trustedProxies) {
        for (String cidr : trustedProxies.split(",")) {
            String c = cidr.trim();
            if (!c.isEmpty()) {
                if (!IP_LITERAL.matcher(c.contains("/") ? c.substring(0, c.indexOf('/')) : c).matches()) {
                    throw new IllegalArgumentException("SECSCAN_TRUSTED_PROXIES must list IPs/CIDRs only: " + c);
                }
                trusted.add(new IpAddressMatcher(c));
            }
        }
    }

    public String resolve(HttpServletRequest request) {
        String peer = request.getRemoteAddr();
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank() || !isTrusted(peer)) {
            return peer;
        }
        String[] parts = forwarded.split(",");
        String candidate = parts[parts.length - 1].trim();
        return IP_LITERAL.matcher(candidate).matches() ? candidate : peer;
    }

    boolean isTrusted(String address) {
        if (address == null || !IP_LITERAL.matcher(address).matches()) {
            return false;
        }
        for (IpAddressMatcher m : trusted) {
            try {
                if (m.matches(address)) {
                    return true;
                }
            } catch (IllegalArgumentException ignored) {
                // not comparable (e.g. IPv4 matcher vs IPv6 address)
            }
        }
        return false;
    }
}
