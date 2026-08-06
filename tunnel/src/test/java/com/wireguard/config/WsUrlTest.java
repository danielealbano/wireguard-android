/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.wireguard.config;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class WsUrlTest {

    @Test
    public void WsUrl_parse_acceptsWssHostPortPath() throws ParseException {
        final WsUrl url = WsUrl.parse("wss://vpn.example.com:8443/wg");
        assertEquals("host parsed", "vpn.example.com", url.getHost());
        assertEquals("port parsed", 8443, url.getPort());
        assertEquals("verbatim URL preserved", "wss://vpn.example.com:8443/wg", url.toString());
        assertEquals("endpoint is host:port", "vpn.example.com:8443", url.toInetEndpoint().toString());
    }

    @Test
    public void WsUrl_parse_acceptsNoPath() throws ParseException {
        final WsUrl url = WsUrl.parse("wss://203.0.113.7:8443");
        assertEquals("host parsed", "203.0.113.7", url.getHost());
        assertEquals("port parsed", 8443, url.getPort());
        assertEquals("endpoint resolved from literal host", "203.0.113.7:8443", url.toInetEndpoint().toString());
    }

    @Test
    public void WsUrl_parse_rejectsMissingPortSchemeHost() {
        expectReject("wss://vpn.example.com/wg");
        expectReject("http://vpn.example.com:8443");
        expectReject("vpn.example.com:8443");
        expectReject("wss:///wg");
    }

    @Test
    public void WsUrl_parse_ipv6BracketHost() throws ParseException {
        final WsUrl url = WsUrl.parse("wss://[2001:db8::1]:8443/x");
        assertEquals("bare IPv6 host", "2001:db8::1", url.getHost());
        assertEquals("endpoint brackets IPv6 once", "[2001:db8::1]:8443", url.toInetEndpoint().toString());
    }

    private static void expectReject(final String value) {
        try {
            WsUrl.parse(value);
            fail("WsUrl.parse should reject " + value);
        } catch (final ParseException ignored) {
        }
    }
}
