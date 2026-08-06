/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.wireguard.config;

import com.wireguard.util.NonNullForAll;

import java.util.Locale;

/** The WebSocket carrier mode of a {@link Peer}: standard WebSocket or a wstunnel relay. */
@NonNullForAll
public enum WsMode {
    WEBSOCKET("websocket"),
    WSTUNNEL("wstunnel");

    private final String name;

    WsMode(final String name) {
        this.name = name;
    }

    /**
     * Parses a {@code WSMode} value.
     *
     * @param value {@code websocket} or {@code wstunnel} (case-insensitive)
     * @return the matching {@code WsMode}
     * @throws ParseException if the value is neither
     */
    public static WsMode parse(final String value) throws ParseException {
        final String lower = value.toLowerCase(Locale.ENGLISH);
        for (final WsMode mode : values())
            if (mode.name.equals(lower))
                return mode;
        throw new ParseException(WsMode.class, value, "Expected 'websocket' or 'wstunnel'");
    }

    /**
     * Returns the wire form of this mode.
     *
     * @return {@code websocket} or {@code wstunnel}
     */
    public String getName() {
        return name;
    }
}
