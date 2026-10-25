package com.purpuragenda.adapters.in.rest;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Resolves the throttling address. The proxy header is honored only from an exactly allowlisted
 * socket peer and only as one literal IP; every other case falls back to the socket peer. Forwarded,
 * X-Forwarded-* and hostnames are never consulted, and no DNS lookup can occur.
 */
public final class ClientAddress {
    public static final String HEADER="X-Purpuragenda-Client-IP";
    private static final String OCTET="(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)";
    private static final Pattern IPV4=Pattern.compile(OCTET+"(\\."+OCTET+"){3}");
    private static final Pattern IPV6=Pattern.compile("[0-9A-Fa-f:.]{2,45}");
    private final Set<String> trustedPeers;
    public ClientAddress(Collection<String> trustedPeers) {
        var normalized=new HashSet<String>();
        for(String peer:trustedPeers) normalized.add(literal(peer).orElseThrow(()->new IllegalArgumentException("INVALID_TRUSTED_PROXY_PEER")));
        this.trustedPeers=Set.copyOf(normalized);
    }
    public static Optional<String> literal(String value) {
        if(value==null || value.length()>45) return Optional.empty();
        try {
            boolean v4=IPV4.matcher(value).matches(),v6=!v4 && IPV6.matcher(value).matches() && value.chars().filter(c->c==':').count()>=2;
            return v4 || v6?Optional.of(InetAddress.ofLiteral(value).getHostAddress()):Optional.empty();
        } catch(IllegalArgumentException invalid) { return Optional.empty(); }
    }
    public String resolve(HttpServletRequest request) {
        String peer=literal(request.getRemoteAddr()).orElse("0.0.0.0");
        if(!trustedPeers.contains(peer)) return peer;
        var values=Collections.list(request.getHeaders(HEADER));
        return values.size()==1?literal(values.get(0)).orElse(peer):peer;
    }
    @Override public String toString() { return "ClientAddress[REDACTED]"; }
}
