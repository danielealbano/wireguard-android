/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.wireguard.config;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ConfigTest {

    @Test(expected = BadConfigException.class)
    public void invalid_config_throws() throws IOException, BadConfigException {
        try (final InputStream is = Objects.requireNonNull(getClass().getClassLoader()).getResourceAsStream("broken.conf")) {
            Config.parse(is);
        }
    }

    @Test
    public void valid_config_parses_correctly() throws IOException, ParseException {
        Config config = null;
        final Collection<InetNetwork> expectedAllowedIps = new HashSet<>(Arrays.asList(InetNetwork.parse("0.0.0.0/0"), InetNetwork.parse("::0/0")));
        try (final InputStream is = Objects.requireNonNull(getClass().getClassLoader()).getResourceAsStream("working.conf")) {
            config = Config.parse(is);
        } catch (final BadConfigException e) {
            fail("'working.conf' should never fail to parse");
        }
        assertNotNull("config cannot be null after parsing", config);
        assertTrue(
                "No applications should be excluded by default",
                config.getInterface().getExcludedApplications().isEmpty()
        );
        assertEquals("Test config has exactly one peer", 1, config.getPeers().size());
        assertEquals("Test config's allowed IPs are 0.0.0.0/0 and ::0/0", config.getPeers().get(0).getAllowedIps(), expectedAllowedIps);
        assertEquals("Test config has one DNS server", 1, config.getInterface().getDnsServers().size());
    }

    @Test
    public void websocket_config_round_trips() throws IOException, BadConfigException {
        final String body = "[Interface]\n"
                + "PrivateKey = yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=\n"
                + "Address = 10.8.0.2/24\n"
                + "[Peer]\n"
                + "PublicKey = xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=\n"
                + "Endpoint = wss://203.0.113.7:8443/v1\n"
                + "WSMode = wstunnel\n"
                + "WSTunnelTarget = 127.0.0.1:51820\n"
                + "AllowedIPs = 192.168.178.0/24\n";
        final Config config = Config.parse(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        assertTrue("config has a WebSocket peer", config.hasWebSocketPeers());
        // KeyPair has no value equals(), so compare the idempotent serialized form.
        final String once = config.toWgQuickString();
        final Config reparsed = Config.parse(new ByteArrayInputStream(once.getBytes(StandardCharsets.UTF_8)));
        assertEquals("WebSocket config round-trips through wg-quick form", once, reparsed.toWgQuickString());
    }
}
