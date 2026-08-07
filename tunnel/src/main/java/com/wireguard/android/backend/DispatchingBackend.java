/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.wireguard.android.backend;

import com.wireguard.android.backend.Tunnel.State;
import com.wireguard.config.Config;
import com.wireguard.util.NonNullForAll;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import androidx.annotation.Nullable;

/**
 * A {@link Backend} that routes each tunnel to the appropriate underlying backend: a configuration
 * containing any websocket/wstunnel peer always runs on the userspace backend; a pure-UDP
 * configuration uses the kernel backend when available, else the userspace backend. State and
 * statistics are routed to the backend that currently owns the tunnel.
 */
@NonNullForAll
public final class DispatchingBackend implements Backend {
    private final Backend userspaceBackend;
    @Nullable private final Backend kernelBackend;
    private final Map<String, Backend> owners = new HashMap<>();

    /**
     * @param userspaceBackend the userspace (GoBackend) backend; always present
     * @param kernelBackend    the kernel (WgQuickBackend) backend, or {@code null} when the kernel
     *                         module is unavailable
     */
    public DispatchingBackend(final Backend userspaceBackend, @Nullable final Backend kernelBackend) {
        this.userspaceBackend = userspaceBackend;
        this.kernelBackend = kernelBackend;
    }

    private Backend defaultBackend() {
        return kernelBackend != null ? kernelBackend : userspaceBackend;
    }

    private Backend upTarget(@Nullable final Config config) {
        if (config != null && config.hasWebSocketPeers())
            return userspaceBackend;
        return defaultBackend();
    }

    @Override
    public synchronized State setState(final Tunnel tunnel, State state, @Nullable final Config config) throws Exception {
        if (state == State.TOGGLE)
            state = getState(tunnel) == State.UP ? State.DOWN : State.UP;

        if (state == State.UP) {
            final Backend target = upTarget(config);
            final Backend current = owners.get(tunnel.getName());
            if (current != null && current != target)
                current.setState(tunnel, State.DOWN, null);
            final State result = target.setState(tunnel, State.UP, config);
            if (result == State.UP)
                owners.put(tunnel.getName(), target);
            else
                owners.remove(tunnel.getName());
            return result;
        }

        // DOWN: route to the recorded owner, else — for a WS config — straight to userspace WITHOUT
        // probing the kernel (probing WgQuickBackend.getState runs ensureToolsAvailable, a root-path
        // side effect a WS tunnel must never trigger); else whichever backend is up; else the
        // config's natural target; else the default backend.
        Backend target = owners.get(tunnel.getName());
        if (target == null) {
            if (config != null && config.hasWebSocketPeers())
                target = userspaceBackend;
            else if (kernelBackend != null && kernelBackend.getState(tunnel) == State.UP)
                target = kernelBackend;
            else if (userspaceBackend.getState(tunnel) == State.UP)
                target = userspaceBackend;
            else
                target = upTarget(config);
        }
        final State result = target.setState(tunnel, State.DOWN, config);
        owners.remove(tunnel.getName());
        return result;
    }

    @Override
    public synchronized State getState(final Tunnel tunnel) throws Exception {
        final Backend owner = owners.get(tunnel.getName());
        if (owner != null)
            return owner.getState(tunnel);
        if (kernelBackend != null && kernelBackend.getState(tunnel) == State.UP)
            return State.UP;
        return userspaceBackend.getState(tunnel);
    }

    @Override
    public synchronized Statistics getStatistics(final Tunnel tunnel) throws Exception {
        final Backend owner = owners.get(tunnel.getName());
        if (owner != null)
            return owner.getStatistics(tunnel);
        if (kernelBackend != null && kernelBackend.getState(tunnel) == State.UP)
            return kernelBackend.getStatistics(tunnel);
        return userspaceBackend.getStatistics(tunnel);
    }

    @Override
    public Set<String> getRunningTunnelNames() {
        final Set<String> names = new HashSet<>(userspaceBackend.getRunningTunnelNames());
        if (kernelBackend != null)
            names.addAll(kernelBackend.getRunningTunnelNames());
        return names;
    }

    @Override
    public String getVersion() throws Exception {
        if (kernelBackend != null)
            return kernelBackend.getVersion() + " / " + userspaceBackend.getVersion();
        return userspaceBackend.getVersion();
    }

    @Override
    public boolean isAlwaysOn() throws Exception {
        return userspaceBackend.isAlwaysOn() || (kernelBackend != null && kernelBackend.isAlwaysOn());
    }

    @Override
    public boolean isLockdownEnabled() throws Exception {
        return userspaceBackend.isLockdownEnabled() || (kernelBackend != null && kernelBackend.isLockdownEnabled());
    }
}
