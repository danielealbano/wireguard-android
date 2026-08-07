/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.wireguard.config;

import com.wireguard.util.NonNullForAll;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * A per-peer WebSocket URL ({@code ws(s)://host:port[/path]}). Stored verbatim; the host and port
 * are also exposed for building the routable {@link InetEndpoint}. Instances are immutable.
 */
@NonNullForAll
public final class WsUrl {
    private final String url;
    private final String host;
    private final int port;

    private WsUrl(final String url, final String host, final int port) {
        this.url = url;
        this.host = host;
        this.port = port;
    }

    /**
     * Parses a WebSocket URL. The scheme must be {@code ws} or {@code wss} (case-insensitive), the
     * host is required, and an explicit port is required (parity with the routable endpoint). A
     * path is optional and preserved verbatim; queries are preserved (accepted for
     * byte-compatibility with wireguard-tools).
     *
     * @param value the URL
     * @return the parsed {@code WsUrl}
     * @throws ParseException if the scheme, host, or port is missing or invalid
     */
    public static WsUrl parse(final String value) throws ParseException {
        final URI uri;
        try {
            uri = new URI(value);
        } catch (final URISyntaxException e) {
            throw new ParseException(WsUrl.class, value, e);
        }
        final String scheme = uri.getScheme();
        if (scheme == null)
            throw new ParseException(WsUrl.class, value, "Missing ws/wss scheme");
        final String lowerScheme = scheme.toLowerCase(Locale.ENGLISH);
        if (!lowerScheme.equals("ws") && !lowerScheme.equals("wss"))
            throw new ParseException(WsUrl.class, value, "Scheme must be ws or wss");
        String host = uri.getHost();
        if (host == null || host.isEmpty())
            throw new ParseException(WsUrl.class, value, "Missing host");
        // java.net.URI.getHost() returns an IPv6 literal WITH its surrounding brackets; strip them
        // so getHost() is the bare literal and toInetEndpoint() brackets exactly once.
        if (host.length() > 1 && host.charAt(0) == '[' && host.charAt(host.length() - 1) == ']')
            host = host.substring(1, host.length() - 1);
        if (uri.getPort() < 0)
            throw new ParseException(WsUrl.class, value, "Missing/invalid port number");
        return new WsUrl(value, host, uri.getPort());
    }

    /**
     * Returns the verbatim URL as parsed.
     *
     * @return the URL string
     */
    public String getUrl() {
        return url;
    }

    /**
     * Returns the URL host, without brackets for an IPv6 literal.
     *
     * @return the host
     */
    public String getHost() {
        return host;
    }

    /**
     * Returns the URL port.
     *
     * @return the port
     */
    public int getPort() {
        return port;
    }

    /**
     * Builds the routable endpoint from the URL host and port, exactly as a UDP endpoint would be
     * parsed. IPv6 literal hosts are bracketed.
     *
     * @return the {@link InetEndpoint} for {@code host:port}
     * @throws ParseException if the host/port do not form a valid endpoint
     */
    public InetEndpoint toInetEndpoint() throws ParseException {
        final String hostPort = host.indexOf(':') >= 0 ? '[' + host + "]:" + port : host + ':' + port;
        return InetEndpoint.parse(hostPort);
    }

    @Override
    public boolean equals(final Object obj) {
        return obj instanceof WsUrl && url.equals(((WsUrl) obj).url);
    }

    @Override
    public int hashCode() {
        return url.hashCode();
    }

    @Override
    public String toString() {
        return url;
    }
}
