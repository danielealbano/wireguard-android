/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.wireguard.android.backend;

import com.wireguard.android.backend.Tunnel.State;
import com.wireguard.config.BadConfigException;
import com.wireguard.config.Config;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import androidx.annotation.Nullable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DispatchingBackendTest {
    private static final String PUBLIC_KEY = "xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=";
    private static final String PRIVATE_KEY = "yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=";

    @Test
    public void DispatchingBackend_setState_routesWsUpToUserspace() throws Exception {
        final FakeBackend userspace = new FakeBackend();
        final FakeBackend kernel = new FakeBackend();
        final DispatchingBackend backend = new DispatchingBackend(userspace, kernel);
        backend.setState(new FakeTunnel("t"), State.UP, wsConfig());
        assertTrue("userspace brought up", userspace.calls.contains("setState:UP"));
        assertTrue("kernel untouched", kernel.calls.isEmpty());
    }

    @Test
    public void DispatchingBackend_setState_routesUdpUpToKernelWhenPresent() throws Exception {
        final FakeBackend userspace = new FakeBackend();
        final FakeBackend kernel = new FakeBackend();
        new DispatchingBackend(userspace, kernel).setState(new FakeTunnel("t"), State.UP, udpConfig());
        assertTrue("kernel brought up", kernel.calls.contains("setState:UP"));
        assertFalse("userspace not used", userspace.calls.contains("setState:UP"));

        final FakeBackend userspaceOnly = new FakeBackend();
        new DispatchingBackend(userspaceOnly, null).setState(new FakeTunnel("t"), State.UP, udpConfig());
        assertTrue("userspace used when no kernel", userspaceOnly.calls.contains("setState:UP"));
    }

    @Test
    public void DispatchingBackend_toggle_resolvesViaState() throws Exception {
        final FakeBackend userspace = new FakeBackend();
        final FakeBackend kernel = new FakeBackend();
        // Both backends report DOWN, so TOGGLE resolves to UP and the WS config routes to userspace
        // — the Quick-tile/TV path that must never hit the kernel for a WS tunnel.
        new DispatchingBackend(userspace, kernel).setState(new FakeTunnel("t"), State.TOGGLE, wsConfig());
        assertTrue("userspace brought up", userspace.calls.contains("setState:UP"));
        assertFalse("kernel not brought up", kernel.calls.contains("setState:UP"));
    }

    @Test
    public void DispatchingBackend_upSwitchesOwner_downsOldFirst() throws Exception {
        final FakeBackend userspace = new FakeBackend();
        final FakeBackend kernel = new FakeBackend();
        final DispatchingBackend backend = new DispatchingBackend(userspace, kernel);
        final FakeTunnel tunnel = new FakeTunnel("t");
        backend.setState(tunnel, State.UP, udpConfig());   // owner = kernel
        backend.setState(tunnel, State.UP, wsConfig());     // owner switches to userspace
        assertTrue("kernel brought down first", kernel.calls.contains("setState:DOWN"));
        assertTrue("userspace brought up", userspace.calls.contains("setState:UP"));
    }

    @Test
    public void DispatchingBackend_down_routesToOwner_notKernelForWs() throws Exception {
        final FakeBackend userspace = new FakeBackend();
        final FakeBackend kernel = new FakeBackend();
        // No owner recorded; a WS config on DOWN must go straight to userspace without probing the
        // kernel (which would run ensureToolsAvailable).
        new DispatchingBackend(userspace, kernel).setState(new FakeTunnel("t"), State.DOWN, wsConfig());
        assertTrue("kernel never touched", kernel.calls.isEmpty());
        assertTrue("userspace brought down", userspace.calls.contains("setState:DOWN"));
    }

    @Test
    public void DispatchingBackend_runningNames_union_and_version() throws Exception {
        final FakeBackend userspace = new FakeBackend();
        final FakeBackend kernel = new FakeBackend();
        userspace.running.add("a");
        kernel.running.add("b");
        userspace.version = "u";
        kernel.version = "k";
        final DispatchingBackend backend = new DispatchingBackend(userspace, kernel);
        assertEquals("union of names", new HashSet<>(java.util.Arrays.asList("a", "b")), backend.getRunningTunnelNames());
        assertEquals("version composed kernel / userspace", "k / u", backend.getVersion());
    }

    @Test
    public void DispatchingBackend_getState_ownerAndUpRouting() throws Exception {
        final FakeBackend userspace = new FakeBackend();
        final FakeBackend kernel = new FakeBackend();
        final DispatchingBackend backend = new DispatchingBackend(userspace, kernel);
        final FakeTunnel tunnel = new FakeTunnel("t");
        userspace.state = State.UP;
        backend.setState(tunnel, State.UP, wsConfig());  // owner = userspace
        assertEquals("state from owner", State.UP, backend.getState(tunnel));

        kernel.state = State.UP;
        assertEquals("up backend wins when no owner", State.UP, backend.getState(new FakeTunnel("other")));
    }

    @Test
    public void DispatchingBackend_alwaysOnAndLockdown_aggregate() throws Exception {
        final FakeBackend userspace = new FakeBackend();
        final FakeBackend kernel = new FakeBackend();
        kernel.alwaysOn = true;
        userspace.lockdown = true;
        final DispatchingBackend withKernel = new DispatchingBackend(userspace, kernel);
        assertTrue("always-on OR across backends", withKernel.isAlwaysOn());
        assertTrue("lockdown OR across backends", withKernel.isLockdownEnabled());

        final FakeBackend plainUserspace = new FakeBackend();
        final DispatchingBackend withoutKernel = new DispatchingBackend(plainUserspace, null);
        assertFalse("no kernel, userspace false", withoutKernel.isAlwaysOn());
        assertFalse("no kernel, userspace false", withoutKernel.isLockdownEnabled());
    }

    private static Config wsConfig() throws BadConfigException, IOException {
        return parse("[Peer]\nPublicKey = " + PUBLIC_KEY
                + "\nEndpoint = wss://203.0.113.7:8443/v1\nWSMode = wstunnel\nWSTunnelTarget = 127.0.0.1:51820\nAllowedIPs = 0.0.0.0/0\n");
    }

    private static Config udpConfig() throws BadConfigException, IOException {
        return parse("[Peer]\nPublicKey = " + PUBLIC_KEY + "\nEndpoint = 203.0.113.7:8443\nAllowedIPs = 0.0.0.0/0\n");
    }

    private static Config parse(final String peerBody) throws BadConfigException, IOException {
        final String body = "[Interface]\nPrivateKey = " + PRIVATE_KEY + "\nAddress = 10.8.0.2/24\n" + peerBody;
        try (final InputStream is = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))) {
            return Config.parse(is);
        }
    }

    private static final class FakeTunnel implements Tunnel {
        private final String name;

        FakeTunnel(final String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public void onStateChange(final State newState) {
        }
    }

    private static final class FakeBackend implements Backend {
        final List<String> calls = new ArrayList<>();
        final Set<String> running = new HashSet<>();
        State state = State.DOWN;
        String version = "fake";
        boolean alwaysOn;
        boolean lockdown;

        @Override
        public Set<String> getRunningTunnelNames() {
            return running;
        }

        @Override
        public State getState(final Tunnel tunnel) {
            calls.add("getState");
            return state;
        }

        @Override
        public Statistics getStatistics(final Tunnel tunnel) {
            throw new UnsupportedOperationException("not exercised on the JVM");
        }

        @Override
        public String getVersion() {
            return version;
        }

        @Override
        public boolean isAlwaysOn() {
            return alwaysOn;
        }

        @Override
        public boolean isLockdownEnabled() {
            return lockdown;
        }

        @Override
        public State setState(final Tunnel tunnel, final State state, @Nullable final Config config) {
            calls.add("setState:" + state);
            this.state = state == State.UP ? State.UP : State.DOWN;
            return this.state;
        }
    }
}
