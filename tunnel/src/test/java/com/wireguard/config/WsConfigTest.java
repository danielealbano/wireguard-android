/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.wireguard.config;

import com.wireguard.config.BadConfigException.Location;
import com.wireguard.config.BadConfigException.Reason;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class WsConfigTest {
    private static final String PUBLIC_KEY = "xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=";
    private static final String PRIVATE_KEY = "yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=";

    private static Peer parsePeer(final String... lines) throws BadConfigException {
        final String[] all = new String[lines.length + 1];
        all[0] = "PublicKey = " + PUBLIC_KEY;
        System.arraycopy(lines, 0, all, 1, lines.length);
        return Peer.parse(Arrays.asList(all));
    }

    private static void expectReject(final Location location, final Reason reason, final String... lines) {
        try {
            parsePeer(lines);
            fail("expected rejection at " + location + '/' + reason);
        } catch (final BadConfigException e) {
            assertEquals("location", location, e.getLocation());
            assertEquals("reason", reason, e.getReason());
        }
    }

    @Test
    public void Peer_parse_wsEndpointWithMode_infersWebsocket() throws BadConfigException {
        final Peer peer = parsePeer("Endpoint = wss://203.0.113.7:8443/wg", "WSMode = websocket");
        assertEquals("mode", WsMode.WEBSOCKET, peer.getWsMode().orElseThrow());
        assertEquals("ws_url stored", "wss://203.0.113.7:8443/wg", peer.getWsUrl().orElseThrow().toString());
        assertEquals("endpoint resolved", "203.0.113.7:8443", peer.getEndpoint().orElseThrow().toString());
        assertEquals("transport", "websocket", peer.getUapiTransport());
    }

    @Test
    public void Peer_parse_wstunnelRequiresTarget() throws BadConfigException {
        expectReject(Location.WSTUNNEL_TARGET, Reason.MISSING_ATTRIBUTE,
                "Endpoint = wss://203.0.113.7:8443/v1", "WSMode = wstunnel");
        final Peer peer = parsePeer("Endpoint = wss://203.0.113.7:8443/v1", "WSMode = wstunnel",
                "WSTunnelTarget = 127.0.0.1:51820");
        assertEquals("transport", "wstunnel", peer.getUapiTransport());
        assertEquals("target", "127.0.0.1:51820", peer.getWstunnelTarget().orElseThrow());
    }

    @Test
    public void Peer_parse_websocketRejectsTarget() {
        expectReject(Location.WSTUNNEL_TARGET, Reason.FORBIDDEN_ATTRIBUTE,
                "Endpoint = wss://203.0.113.7:8443/wg", "WSMode = websocket",
                "WSTunnelTarget = 127.0.0.1:51820");
    }

    @Test
    public void Peer_parse_wsModeWithoutEndpoint_inbound() throws BadConfigException {
        final Peer peer = parsePeer("WSMode = websocket");
        assertTrue("no endpoint", peer.getEndpoint().isEmpty());
        assertTrue("no ws_url", peer.getWsUrl().isEmpty());
        assertEquals("transport", "websocket", peer.getUapiTransport());
    }

    @Test
    public void Peer_parse_inboundWstunnel_rejected() {
        expectReject(Location.WS_MODE, Reason.FORBIDDEN_ATTRIBUTE, "WSMode = wstunnel");
    }

    @Test
    public void Peer_parse_wsUrlWithoutMode_rejected() {
        expectReject(Location.WS_MODE, Reason.MISSING_ATTRIBUTE, "Endpoint = wss://203.0.113.7:8443/wg");
    }

    @Test
    public void Peer_parse_hostPortEndpointWithMode_rejected() {
        expectReject(Location.ENDPOINT, Reason.FORBIDDEN_ATTRIBUTE,
                "Endpoint = 203.0.113.7:8443", "WSMode = websocket");
    }

    @Test
    public void Peer_parse_wsKeysOnUdp_rejected() {
        // Presence of ANY ws_* key on a udp peer (host:port endpoint, no WSMode) is rejected,
        // including a false-valued mask/insecure and a zero-valued timing.
        expectReject(Location.WS_BEARER, Reason.FORBIDDEN_ATTRIBUTE,
                "Endpoint = 203.0.113.7:8443", "WSBearer = token");
        expectReject(Location.WS_MASK, Reason.FORBIDDEN_ATTRIBUTE,
                "Endpoint = 203.0.113.7:8443", "WSMask = false");
        expectReject(Location.WS_TLS_INSECURE, Reason.FORBIDDEN_ATTRIBUTE,
                "Endpoint = 203.0.113.7:8443", "WSTLSInsecure = false");
        expectReject(Location.WS_PING_INTERVAL, Reason.FORBIDDEN_ATTRIBUTE,
                "Endpoint = 203.0.113.7:8443", "WSPingInterval = 0");
    }

    @Test
    public void Peer_parse_badBoolAndMillis_rejected() {
        expectReject(Location.WS_MASK, Reason.INVALID_VALUE,
                "Endpoint = wss://203.0.113.7:8443/wg", "WSMode = websocket", "WSMask = yes");
        expectReject(Location.WS_TLS_INSECURE, Reason.INVALID_VALUE,
                "Endpoint = wss://203.0.113.7:8443/wg", "WSMode = websocket", "WSTLSInsecure = 1");
        expectReject(Location.WS_PING_INTERVAL, Reason.INVALID_NUMBER,
                "Endpoint = wss://203.0.113.7:8443/wg", "WSMode = websocket", "WSPingInterval = abc");
        expectReject(Location.WS_BACKOFF_MIN, Reason.INVALID_VALUE,
                "Endpoint = wss://203.0.113.7:8443/wg", "WSMode = websocket", "WSBackoffMin = -1");
    }

    @Test
    public void Peer_parse_invalidWsMode_rejected() {
        expectReject(Location.WS_MODE, Reason.INVALID_VALUE,
                "Endpoint = wss://203.0.113.7:8443/wg", "WSMode = foo");
    }

    @Test
    public void Peer_parse_malformedWstunnelTarget_rejected() {
        expectReject(Location.WSTUNNEL_TARGET, Reason.INVALID_VALUE,
                "Endpoint = wss://203.0.113.7:8443/v1", "WSMode = wstunnel", "WSTunnelTarget = nohostport");
    }

    @Test
    public void Peer_parse_zeroMillis_absentInEmission() throws BadConfigException {
        final Peer peer = parsePeer("Endpoint = wss://203.0.113.7:8443/wg", "WSMode = websocket",
                "WSPingInterval = 0");
        assertTrue("zero timing dropped", peer.getWsPingIntervalMs().isEmpty());
        assertFalse("not emitted", peer.toWgUserspaceString().contains("ws_ping_interval"));
    }

    @Test
    public void Peer_toWgQuickString_roundTrip() throws BadConfigException {
        final Peer original = parsePeer(
                "AllowedIPs = 0.0.0.0/0",
                "Endpoint = wss://203.0.113.7:8443/v1",
                "WSMode = wstunnel",
                "WSTunnelTarget = 127.0.0.1:51820",
                "WSBearer = user:pass",
                "WSMask = true",
                "WSTLSInsecure = true",
                "WSPingInterval = 25000",
                "WSBackoffMin = 500",
                "WSBackoffMax = 30000");
        final Peer reparsed = Peer.parse(Arrays.asList(original.toWgQuickString().split("\\n")));
        assertEquals("round-trip preserves the peer", original, reparsed);
    }

    @Test
    public void Peer_toWgUserspaceString_udpPeer() throws BadConfigException {
        final String s = parsePeer("Endpoint = 203.0.113.7:8443").toWgUserspaceString();
        assertTrue("transport=udp", s.contains("transport=udp\n"));
        assertFalse("no ws_ keys", s.contains("ws_"));
    }

    @Test
    public void Peer_toWgUserspaceString_wstunnelPeer() throws BadConfigException {
        final String s = parsePeer(
                "Endpoint = wss://203.0.113.7:8443/v1",
                "WSMode = wstunnel",
                "WSTunnelTarget = 127.0.0.1:51820").toWgUserspaceString();
        assertTrue("transport", s.contains("transport=wstunnel\n"));
        assertTrue("resolved endpoint", s.contains("endpoint=203.0.113.7:8443\n"));
        assertTrue("verbatim ws_url", s.contains("ws_url=wss://203.0.113.7:8443/v1\n"));
        assertTrue("wstunnel_target", s.contains("wstunnel_target=127.0.0.1:51820\n"));
    }

    @Test
    public void Peer_toWgUserspaceString_inboundWebsocketPeer() throws BadConfigException {
        final String s = parsePeer("WSMode = websocket").toWgUserspaceString();
        assertTrue("transport", s.contains("transport=websocket\n"));
        assertFalse("no endpoint", s.contains("endpoint="));
        assertFalse("no ws_url", s.contains("ws_url="));
    }

    @Test
    public void Peer_toString_neverContainsBearer() throws BadConfigException {
        final Peer peer = parsePeer(
                "Endpoint = wss://203.0.113.7:8443/wg",
                "WSMode = websocket",
                "WSBearer = supersecrettoken");
        assertFalse("bearer absent from toString", peer.toString().contains("supersecrettoken"));
        // A rejected bearer (empty value) must not echo the value either; here we assert the
        // successful case's toString and that the value only appears in serialized config forms.
        assertTrue("bearer present in wg-quick form for round-trip",
                peer.toWgQuickString().contains("supersecrettoken"));
    }

    @Test
    public void Peer_equalsHashCode_includeWsFields() throws BadConfigException {
        final Peer a = parsePeer("Endpoint = wss://203.0.113.7:8443/wg", "WSMode = websocket");
        final Peer b = parsePeer("Endpoint = wss://203.0.113.7:8443/wg", "WSMode = websocket", "WSMask = true");
        assertFalse("differing ws fields are not equal", a.equals(b));
    }

    @Test
    public void Config_hasWebSocketPeers() throws BadConfigException, IOException {
        assertFalse("udp-only", parseConfig(
                "Endpoint = 203.0.113.7:8443").hasWebSocketPeers());
        assertTrue("wstunnel peer", parseConfig(
                "Endpoint = wss://203.0.113.7:8443/v1\nWSMode = wstunnel\nWSTunnelTarget = 127.0.0.1:51820")
                .hasWebSocketPeers());
        assertTrue("inbound websocket peer", parseConfig("WSMode = websocket").hasWebSocketPeers());
    }

    @Test
    public void Config_roundTrip_wsPeer() throws BadConfigException, IOException {
        final Config config = parseConfig(
                "Endpoint = wss://203.0.113.7:8443/v1\nWSMode = wstunnel\nWSTunnelTarget = 127.0.0.1:51820\nAllowedIPs = 192.168.178.0/24");
        final Config reparsed = Config.parse(new ByteArrayInputStream(
                config.toWgQuickString().getBytes(StandardCharsets.UTF_8)));
        assertEquals("config round-trips", config, reparsed);
    }

    private static Config parseConfig(final String peerBody) throws BadConfigException, IOException {
        final String body = "[Interface]\nPrivateKey = " + PRIVATE_KEY + "\nAddress = 10.8.0.2/24\n"
                + "[Peer]\nPublicKey = " + PUBLIC_KEY + '\n' + peerBody + '\n';
        try (final InputStream is = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))) {
            return Config.parse(is);
        }
    }
}
