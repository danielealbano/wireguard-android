/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.wireguard.config;

import com.wireguard.config.BadConfigException.Location;
import com.wireguard.config.BadConfigException.Reason;
import com.wireguard.config.BadConfigException.Section;
import com.wireguard.crypto.Key;
import com.wireguard.crypto.KeyFormatException;
import com.wireguard.util.NonNullForAll;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import androidx.annotation.Nullable;

/**
 * Represents the configuration for a WireGuard peer (a [Peer] block). Peers must have a public key,
 * and may optionally have several other attributes.
 * <p>
 * Instances of this class are immutable.
 */
@NonNullForAll
public final class Peer {
    private final Set<InetNetwork> allowedIps;
    private final Optional<InetEndpoint> endpoint;
    private final Optional<Integer> persistentKeepalive;
    private final Optional<Key> preSharedKey;
    private final Key publicKey;
    private final Optional<WsMode> wsMode;
    private final Optional<WsUrl> wsUrl;
    private final Optional<String> wstunnelTarget;
    private final Optional<String> wsBearer;
    private final boolean wsMask;
    private final Optional<String> wsTlsCa;
    private final Optional<String> wsTlsCert;
    private final Optional<String> wsTlsKey;
    private final boolean wsTlsInsecure;
    private final Optional<Integer> wsPingIntervalMs;
    private final Optional<Integer> wsBackoffMinMs;
    private final Optional<Integer> wsBackoffMaxMs;

    private Peer(final Builder builder) {
        // Defensively copy to ensure immutability even if the Builder is reused.
        allowedIps = Collections.unmodifiableSet(new LinkedHashSet<>(builder.allowedIps));
        endpoint = builder.endpoint;
        persistentKeepalive = builder.persistentKeepalive;
        preSharedKey = builder.preSharedKey;
        publicKey = Objects.requireNonNull(builder.publicKey, "Peers must have a public key");
        wsMode = builder.wsMode;
        wsUrl = builder.wsUrl;
        wstunnelTarget = builder.wstunnelTarget;
        wsBearer = builder.wsBearer;
        wsMask = builder.wsMask.orElse(false);
        wsTlsCa = builder.wsTlsCa;
        wsTlsCert = builder.wsTlsCert;
        wsTlsKey = builder.wsTlsKey;
        wsTlsInsecure = builder.wsTlsInsecure.orElse(false);
        // A UAPI ws_* timing of 0 means "use the default", so it is not carried or emitted.
        wsPingIntervalMs = builder.wsPingIntervalMs.filter(v -> v != 0);
        wsBackoffMinMs = builder.wsBackoffMinMs.filter(v -> v != 0);
        wsBackoffMaxMs = builder.wsBackoffMaxMs.filter(v -> v != 0);
    }

    /**
     * Parses an series of "KEY = VALUE" lines into a {@code Peer}. Throws {@link ParseException} if
     * the input is not well-formed or contains unknown attributes.
     *
     * @param lines an iterable sequence of lines, containing at least a public key attribute
     * @return a {@code Peer} with all of its attributes set from {@code lines}
     */
    public static Peer parse(final Iterable<? extends CharSequence> lines)
            throws BadConfigException {
        final Builder builder = new Builder();
        for (final CharSequence line : lines) {
            final Attribute attribute = Attribute.parse(line).orElseThrow(() ->
                    new BadConfigException(Section.PEER, Location.TOP_LEVEL,
                            Reason.SYNTAX_ERROR, line));
            switch (attribute.getKey().toLowerCase(Locale.ENGLISH)) {
                case "allowedips":
                    builder.parseAllowedIPs(attribute.getValue());
                    break;
                case "endpoint":
                    builder.parseEndpoint(attribute.getValue());
                    break;
                case "persistentkeepalive":
                    builder.parsePersistentKeepalive(attribute.getValue());
                    break;
                case "presharedkey":
                    builder.parsePreSharedKey(attribute.getValue());
                    break;
                case "publickey":
                    builder.parsePublicKey(attribute.getValue());
                    break;
                case "wsmode":
                    builder.parseWsMode(attribute.getValue());
                    break;
                case "wstunneltarget":
                    builder.parseWstunnelTarget(attribute.getValue());
                    break;
                case "wsbearer":
                    builder.parseWsBearer(attribute.getValue());
                    break;
                case "wsmask":
                    builder.parseWsMask(attribute.getValue());
                    break;
                case "wstlsca":
                    builder.parseWsTlsCa(attribute.getValue());
                    break;
                case "wstlscert":
                    builder.parseWsTlsCert(attribute.getValue());
                    break;
                case "wstlskey":
                    builder.parseWsTlsKey(attribute.getValue());
                    break;
                case "wstlsinsecure":
                    builder.parseWsTlsInsecure(attribute.getValue());
                    break;
                case "wspinginterval":
                    builder.parseWsPingInterval(attribute.getValue());
                    break;
                case "wsbackoffmin":
                    builder.parseWsBackoffMin(attribute.getValue());
                    break;
                case "wsbackoffmax":
                    builder.parseWsBackoffMax(attribute.getValue());
                    break;
                default:
                    throw new BadConfigException(Section.PEER, Location.TOP_LEVEL,
                            Reason.UNKNOWN_ATTRIBUTE, attribute.getKey());
            }
        }
        return builder.build();
    }

    @Override
    public boolean equals(final Object obj) {
        if (!(obj instanceof Peer))
            return false;
        final Peer other = (Peer) obj;
        return allowedIps.equals(other.allowedIps)
                && endpoint.equals(other.endpoint)
                && persistentKeepalive.equals(other.persistentKeepalive)
                && preSharedKey.equals(other.preSharedKey)
                && publicKey.equals(other.publicKey)
                && wsMode.equals(other.wsMode)
                && wsUrl.equals(other.wsUrl)
                && wstunnelTarget.equals(other.wstunnelTarget)
                && wsBearer.equals(other.wsBearer)
                && wsMask == other.wsMask
                && wsTlsCa.equals(other.wsTlsCa)
                && wsTlsCert.equals(other.wsTlsCert)
                && wsTlsKey.equals(other.wsTlsKey)
                && wsTlsInsecure == other.wsTlsInsecure
                && wsPingIntervalMs.equals(other.wsPingIntervalMs)
                && wsBackoffMinMs.equals(other.wsBackoffMinMs)
                && wsBackoffMaxMs.equals(other.wsBackoffMaxMs);
    }

    /**
     * Returns the peer's set of allowed IPs.
     *
     * @return the set of allowed IPs
     */
    public Set<InetNetwork> getAllowedIps() {
        // The collection is already immutable.
        return allowedIps;
    }

    /**
     * Returns the peer's endpoint.
     *
     * @return the endpoint, or {@code Optional.empty()} if none is configured
     */
    public Optional<InetEndpoint> getEndpoint() {
        return endpoint;
    }

    /**
     * Returns the peer's persistent keepalive.
     *
     * @return the persistent keepalive, or {@code Optional.empty()} if none is configured
     */
    public Optional<Integer> getPersistentKeepalive() {
        return persistentKeepalive;
    }

    /**
     * Returns the peer's pre-shared key.
     *
     * @return the pre-shared key, or {@code Optional.empty()} if none is configured
     */
    public Optional<Key> getPreSharedKey() {
        return preSharedKey;
    }

    /**
     * Returns the peer's public key.
     *
     * @return the public key
     */
    public Key getPublicKey() {
        return publicKey;
    }

    /**
     * Returns the peer's WebSocket transport mode.
     *
     * @return the mode, or {@code Optional.empty()} for a plain UDP peer
     */
    public Optional<WsMode> getWsMode() {
        return wsMode;
    }

    /**
     * Returns the peer's WebSocket URL.
     *
     * @return the URL, or {@code Optional.empty()} if none is configured (UDP or inbound WS peer)
     */
    public Optional<WsUrl> getWsUrl() {
        return wsUrl;
    }

    /**
     * Returns the wstunnel relay's inner target endpoint.
     *
     * @return the {@code host:port} target, or {@code Optional.empty()} if none is configured
     */
    public Optional<String> getWstunnelTarget() {
        return wstunnelTarget;
    }

    /**
     * Returns the WebSocket authorization bearer/credential.
     *
     * @return the bearer, or {@code Optional.empty()} if none is configured
     */
    public Optional<String> getWsBearer() {
        return wsBearer;
    }

    /**
     * Returns whether client WebSocket frames are masked.
     *
     * @return {@code true} if masking is enabled
     */
    public boolean getWsMask() {
        return wsMask;
    }

    /**
     * Returns the path to the PEM CA bundle verifying the server.
     *
     * @return the CA path, or {@code Optional.empty()} if none is configured
     */
    public Optional<String> getWsTlsCa() {
        return wsTlsCa;
    }

    /**
     * Returns the path to the client mutual-TLS certificate.
     *
     * @return the cert path, or {@code Optional.empty()} if none is configured
     */
    public Optional<String> getWsTlsCert() {
        return wsTlsCert;
    }

    /**
     * Returns the path to the client mutual-TLS private key.
     *
     * @return the key path, or {@code Optional.empty()} if none is configured
     */
    public Optional<String> getWsTlsKey() {
        return wsTlsKey;
    }

    /**
     * Returns whether server-certificate verification is skipped.
     *
     * @return {@code true} if verification is skipped
     */
    public boolean getWsTlsInsecure() {
        return wsTlsInsecure;
    }

    /**
     * Returns the WebSocket keepalive ping interval in milliseconds.
     *
     * @return the interval, or {@code Optional.empty()} to use the default
     */
    public Optional<Integer> getWsPingIntervalMs() {
        return wsPingIntervalMs;
    }

    /**
     * Returns the minimum reconnect backoff in milliseconds.
     *
     * @return the minimum backoff, or {@code Optional.empty()} to use the default
     */
    public Optional<Integer> getWsBackoffMinMs() {
        return wsBackoffMinMs;
    }

    /**
     * Returns the maximum reconnect backoff in milliseconds.
     *
     * @return the maximum backoff, or {@code Optional.empty()} to use the default
     */
    public Optional<Integer> getWsBackoffMaxMs() {
        return wsBackoffMaxMs;
    }

    /**
     * Returns the UAPI {@code transport} value for this peer.
     *
     * @return {@code udp}, {@code websocket}, or {@code wstunnel}
     */
    public String getUapiTransport() {
        if (wsMode.isEmpty())
            return "udp";
        // Inbound peers are websocket-only (an inbound wstunnel is rejected at build()), so the
        // stored mode is authoritative here.
        return wsMode.get().getName();
    }

    @Override
    public int hashCode() {
        int hash = 1;
        hash = 31 * hash + allowedIps.hashCode();
        hash = 31 * hash + endpoint.hashCode();
        hash = 31 * hash + persistentKeepalive.hashCode();
        hash = 31 * hash + preSharedKey.hashCode();
        hash = 31 * hash + publicKey.hashCode();
        hash = 31 * hash + wsMode.hashCode();
        hash = 31 * hash + wsUrl.hashCode();
        hash = 31 * hash + wstunnelTarget.hashCode();
        hash = 31 * hash + wsBearer.hashCode();
        hash = 31 * hash + Boolean.hashCode(wsMask);
        hash = 31 * hash + wsTlsCa.hashCode();
        hash = 31 * hash + wsTlsCert.hashCode();
        hash = 31 * hash + wsTlsKey.hashCode();
        hash = 31 * hash + Boolean.hashCode(wsTlsInsecure);
        hash = 31 * hash + wsPingIntervalMs.hashCode();
        hash = 31 * hash + wsBackoffMinMs.hashCode();
        hash = 31 * hash + wsBackoffMaxMs.hashCode();
        return hash;
    }

    /**
     * Converts the {@code Peer} into a string suitable for debugging purposes. The {@code Peer} is
     * identified by its public key and (if known) its endpoint.
     *
     * @return a concise single-line identifier for the {@code Peer}
     */
    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("(Peer ");
        sb.append(publicKey.toBase64());
        endpoint.ifPresent(ep -> sb.append(" @").append(ep));
        sb.append(')');
        return sb.toString();
    }

    /**
     * Converts the {@code Peer} into a string suitable for inclusion in a {@code wg-quick}
     * configuration file.
     *
     * @return the {@code Peer} represented as a series of "Key = Value" lines
     */
    public String toWgQuickString() {
        final StringBuilder sb = new StringBuilder();
        if (!allowedIps.isEmpty())
            sb.append("AllowedIPs = ").append(Attribute.join(allowedIps)).append('\n');
        if (wsUrl.isPresent())
            sb.append("Endpoint = ").append(wsUrl.get()).append('\n');
        else
            endpoint.ifPresent(ep -> sb.append("Endpoint = ").append(ep).append('\n'));
        persistentKeepalive.ifPresent(pk -> sb.append("PersistentKeepalive = ").append(pk).append('\n'));
        preSharedKey.ifPresent(psk -> sb.append("PreSharedKey = ").append(psk.toBase64()).append('\n'));
        sb.append("PublicKey = ").append(publicKey.toBase64()).append('\n');
        wsMode.ifPresent(m -> sb.append("WSMode = ").append(m.getName()).append('\n'));
        wstunnelTarget.ifPresent(t -> sb.append("WSTunnelTarget = ").append(t).append('\n'));
        if (wsMask)
            sb.append("WSMask = true\n");
        wsTlsCa.ifPresent(v -> sb.append("WSTLSCA = ").append(v).append('\n'));
        wsTlsCert.ifPresent(v -> sb.append("WSTLSCert = ").append(v).append('\n'));
        wsTlsKey.ifPresent(v -> sb.append("WSTLSKey = ").append(v).append('\n'));
        if (wsTlsInsecure)
            sb.append("WSTLSInsecure = true\n");
        wsPingIntervalMs.ifPresent(v -> sb.append("WSPingInterval = ").append(v).append('\n'));
        wsBackoffMinMs.ifPresent(v -> sb.append("WSBackoffMin = ").append(v).append('\n'));
        wsBackoffMaxMs.ifPresent(v -> sb.append("WSBackoffMax = ").append(v).append('\n'));
        wsBearer.ifPresent(v -> sb.append("WSBearer = ").append(v).append('\n'));
        return sb.toString();
    }

    /**
     * Serializes the {@code Peer} for use with the WireGuard cross-platform userspace API. Note
     * that not all attributes are included in this representation.
     *
     * @return the {@code Peer} represented as a series of "key=value" lines
     */
    public String toWgUserspaceString() {
        final StringBuilder sb = new StringBuilder();
        // The order here is important: public_key signifies the beginning of a new peer.
        sb.append("public_key=").append(publicKey.toHex()).append('\n');
        sb.append("transport=").append(getUapiTransport()).append('\n');
        for (final InetNetwork allowedIp : allowedIps)
            sb.append("allowed_ip=").append(allowedIp).append('\n');
        endpoint.flatMap(InetEndpoint::getResolved).ifPresent(ep -> sb.append("endpoint=").append(ep).append('\n'));
        persistentKeepalive.ifPresent(pk -> sb.append("persistent_keepalive_interval=").append(pk).append('\n'));
        preSharedKey.ifPresent(psk -> sb.append("preshared_key=").append(psk.toHex()).append('\n'));
        if (wsMode.isPresent()) {
            wsUrl.ifPresent(u -> sb.append("ws_url=").append(u).append('\n'));
            wstunnelTarget.ifPresent(t -> sb.append("wstunnel_target=").append(t).append('\n'));
            wsBearer.ifPresent(b -> sb.append("ws_bearer=").append(b).append('\n'));
            if (wsMask)
                sb.append("ws_mask=true\n");
            wsTlsCa.ifPresent(v -> sb.append("ws_tls_ca=").append(v).append('\n'));
            wsTlsCert.ifPresent(v -> sb.append("ws_tls_cert=").append(v).append('\n'));
            wsTlsKey.ifPresent(v -> sb.append("ws_tls_key=").append(v).append('\n'));
            if (wsTlsInsecure)
                sb.append("ws_tls_insecure=true\n");
            wsPingIntervalMs.ifPresent(v -> sb.append("ws_ping_interval=").append(v).append('\n'));
            wsBackoffMinMs.ifPresent(v -> sb.append("ws_backoff_min=").append(v).append('\n'));
            wsBackoffMaxMs.ifPresent(v -> sb.append("ws_backoff_max=").append(v).append('\n'));
        }
        return sb.toString();
    }

    @SuppressWarnings("UnusedReturnValue")
    public static final class Builder {
        // See wg(8)
        private static final int MAX_PERSISTENT_KEEPALIVE = 65535;

        // Defaults to an empty set.
        private final Set<InetNetwork> allowedIps = new LinkedHashSet<>();
        // Defaults to not present.
        private Optional<InetEndpoint> endpoint = Optional.empty();
        // Defaults to not present.
        private Optional<Integer> persistentKeepalive = Optional.empty();
        // Defaults to not present.
        private Optional<Key> preSharedKey = Optional.empty();
        // No default; must be provided before building.
        @Nullable private Key publicKey;
        // WebSocket/wstunnel fields. Booleans and timings are Optional so that their mere presence
        // (even a false/zero value) can be rejected on a UDP peer.
        private Optional<WsMode> wsMode = Optional.empty();
        private Optional<WsUrl> wsUrl = Optional.empty();
        private Optional<String> wstunnelTarget = Optional.empty();
        private Optional<String> wsBearer = Optional.empty();
        private Optional<Boolean> wsMask = Optional.empty();
        private Optional<String> wsTlsCa = Optional.empty();
        private Optional<String> wsTlsCert = Optional.empty();
        private Optional<String> wsTlsKey = Optional.empty();
        private Optional<Boolean> wsTlsInsecure = Optional.empty();
        private Optional<Integer> wsPingIntervalMs = Optional.empty();
        private Optional<Integer> wsBackoffMinMs = Optional.empty();
        private Optional<Integer> wsBackoffMaxMs = Optional.empty();

        public Builder addAllowedIp(final InetNetwork allowedIp) {
            allowedIps.add(allowedIp);
            return this;
        }

        public Builder addAllowedIps(final Collection<InetNetwork> allowedIps) {
            this.allowedIps.addAll(allowedIps);
            return this;
        }

        public Peer build() throws BadConfigException {
            if (publicKey == null)
                throw new BadConfigException(Section.PEER, Location.PUBLIC_KEY,
                        Reason.MISSING_ATTRIBUTE, null);
            validateWs();
            return new Peer(this);
        }

        private void validateWs() throws BadConfigException {
            final boolean isWstunnel = wsMode.filter(m -> m == WsMode.WSTUNNEL).isPresent();
            // A ws:// URL requires a mode.
            if (wsUrl.isPresent() && wsMode.isEmpty())
                throw new BadConfigException(Section.PEER, Location.WS_MODE,
                        Reason.MISSING_ATTRIBUTE, null);
            // A host:port endpoint conflicts with a WSMode.
            if (wsUrl.isEmpty() && wsMode.isPresent() && endpoint.isPresent())
                throw new BadConfigException(Section.PEER, Location.ENDPOINT,
                        Reason.FORBIDDEN_ATTRIBUTE, null);
            // An inbound peer (no URL) cannot be wstunnel — that is a client-side dialing mode.
            if (isWstunnel && wsUrl.isEmpty())
                throw new BadConfigException(Section.PEER, Location.WS_MODE,
                        Reason.FORBIDDEN_ATTRIBUTE, WsMode.WSTUNNEL.getName());
            // A dialing wstunnel peer requires an inner target.
            if (isWstunnel && wsUrl.isPresent() && wstunnelTarget.isEmpty())
                throw new BadConfigException(Section.PEER, Location.WSTUNNEL_TARGET,
                        Reason.MISSING_ATTRIBUTE, null);
            // A target is only valid for a dialing wstunnel peer.
            if (wstunnelTarget.isPresent() && !(isWstunnel && wsUrl.isPresent()))
                throw new BadConfigException(Section.PEER, Location.WSTUNNEL_TARGET,
                        Reason.FORBIDDEN_ATTRIBUTE, null);
            // Any ws_* key requires a WebSocket transport.
            if (wsMode.isEmpty()) {
                final Location offending = firstWsKeyLocation();
                if (offending != null)
                    throw new BadConfigException(Section.PEER, offending,
                            Reason.FORBIDDEN_ATTRIBUTE, null);
            }
        }

        @Nullable
        private Location firstWsKeyLocation() {
            if (wstunnelTarget.isPresent())
                return Location.WSTUNNEL_TARGET;
            if (wsBearer.isPresent())
                return Location.WS_BEARER;
            if (wsMask.isPresent())
                return Location.WS_MASK;
            if (wsTlsCa.isPresent())
                return Location.WS_TLS_CA;
            if (wsTlsCert.isPresent())
                return Location.WS_TLS_CERT;
            if (wsTlsKey.isPresent())
                return Location.WS_TLS_KEY;
            if (wsTlsInsecure.isPresent())
                return Location.WS_TLS_INSECURE;
            if (wsPingIntervalMs.isPresent())
                return Location.WS_PING_INTERVAL;
            if (wsBackoffMinMs.isPresent())
                return Location.WS_BACKOFF_MIN;
            if (wsBackoffMaxMs.isPresent())
                return Location.WS_BACKOFF_MAX;
            return null;
        }

        public Builder parseAllowedIPs(final CharSequence allowedIps) throws BadConfigException {
            try {
                for (final String allowedIp : Attribute.split(allowedIps))
                    addAllowedIp(InetNetwork.parse(allowedIp));
                return this;
            } catch (final ParseException e) {
                throw new BadConfigException(Section.PEER, Location.ALLOWED_IPS, e);
            }
        }

        public Builder parseEndpoint(final String endpoint) throws BadConfigException {
            final String lower = endpoint.toLowerCase(Locale.ENGLISH);
            if (lower.startsWith("ws://") || lower.startsWith("wss://"))
                return parseWsEndpoint(endpoint);
            try {
                return setEndpoint(InetEndpoint.parse(endpoint));
            } catch (final ParseException e) {
                throw new BadConfigException(Section.PEER, Location.ENDPOINT, e);
            }
        }

        private Builder parseWsEndpoint(final String url) throws BadConfigException {
            try {
                final WsUrl parsed = WsUrl.parse(url);
                wsUrl = Optional.of(parsed);
                return setEndpoint(parsed.toInetEndpoint());
            } catch (final ParseException e) {
                throw new BadConfigException(Section.PEER, Location.ENDPOINT, e);
            }
        }

        public Builder parsePersistentKeepalive(final String persistentKeepalive)
                throws BadConfigException {
            try {
                return setPersistentKeepalive(Integer.parseInt(persistentKeepalive));
            } catch (final NumberFormatException e) {
                throw new BadConfigException(Section.PEER, Location.PERSISTENT_KEEPALIVE,
                        persistentKeepalive, e);
            }
        }

        public Builder parsePreSharedKey(final String preSharedKey) throws BadConfigException {
            try {
                return setPreSharedKey(Key.fromBase64(preSharedKey));
            } catch (final KeyFormatException e) {
                throw new BadConfigException(Section.PEER, Location.PRE_SHARED_KEY, e);
            }
        }

        public Builder parsePublicKey(final String publicKey) throws BadConfigException {
            try {
                return setPublicKey(Key.fromBase64(publicKey));
            } catch (final KeyFormatException e) {
                throw new BadConfigException(Section.PEER, Location.PUBLIC_KEY, e);
            }
        }

        public Builder parseWsMode(final String wsMode) throws BadConfigException {
            try {
                this.wsMode = Optional.of(WsMode.parse(wsMode));
                return this;
            } catch (final ParseException e) {
                throw new BadConfigException(Section.PEER, Location.WS_MODE, e);
            }
        }

        public Builder parseWstunnelTarget(final String wstunnelTarget) throws BadConfigException {
            try {
                // Validate the host:port shape; store the raw string. The inner target is
                // resolved by the wstunnel relay, never by this app.
                InetEndpoint.parse(wstunnelTarget);
                this.wstunnelTarget = Optional.of(wstunnelTarget);
                return this;
            } catch (final ParseException e) {
                throw new BadConfigException(Section.PEER, Location.WSTUNNEL_TARGET, e);
            }
        }

        public Builder parseWsBearer(final String wsBearer) throws BadConfigException {
            // The bearer is a secret: never place its value in an exception's text.
            if (wsBearer.isEmpty())
                throw new BadConfigException(Section.PEER, Location.WS_BEARER,
                        Reason.INVALID_VALUE, null);
            this.wsBearer = Optional.of(wsBearer);
            return this;
        }

        public Builder parseWsMask(final String wsMask) throws BadConfigException {
            this.wsMask = Optional.of(parseWsBoolean(wsMask, Location.WS_MASK));
            return this;
        }

        public Builder parseWsTlsCa(final String wsTlsCa) throws BadConfigException {
            this.wsTlsCa = Optional.of(parseWsNonEmpty(wsTlsCa, Location.WS_TLS_CA));
            return this;
        }

        public Builder parseWsTlsCert(final String wsTlsCert) throws BadConfigException {
            this.wsTlsCert = Optional.of(parseWsNonEmpty(wsTlsCert, Location.WS_TLS_CERT));
            return this;
        }

        public Builder parseWsTlsKey(final String wsTlsKey) throws BadConfigException {
            this.wsTlsKey = Optional.of(parseWsNonEmpty(wsTlsKey, Location.WS_TLS_KEY));
            return this;
        }

        public Builder parseWsTlsInsecure(final String wsTlsInsecure) throws BadConfigException {
            this.wsTlsInsecure = Optional.of(parseWsBoolean(wsTlsInsecure, Location.WS_TLS_INSECURE));
            return this;
        }

        public Builder parseWsPingInterval(final String wsPingInterval) throws BadConfigException {
            this.wsPingIntervalMs = Optional.of(parseWsMillis(wsPingInterval, Location.WS_PING_INTERVAL));
            return this;
        }

        public Builder parseWsBackoffMin(final String wsBackoffMin) throws BadConfigException {
            this.wsBackoffMinMs = Optional.of(parseWsMillis(wsBackoffMin, Location.WS_BACKOFF_MIN));
            return this;
        }

        public Builder parseWsBackoffMax(final String wsBackoffMax) throws BadConfigException {
            this.wsBackoffMaxMs = Optional.of(parseWsMillis(wsBackoffMax, Location.WS_BACKOFF_MAX));
            return this;
        }

        private static boolean parseWsBoolean(final String value, final Location location)
                throws BadConfigException {
            if ("true".equals(value))
                return true;
            if ("false".equals(value))
                return false;
            throw new BadConfigException(Section.PEER, location, Reason.INVALID_VALUE, value);
        }

        private static String parseWsNonEmpty(final String value, final Location location)
                throws BadConfigException {
            if (value.isEmpty())
                throw new BadConfigException(Section.PEER, location, Reason.INVALID_VALUE, value);
            return value;
        }

        private static int parseWsMillis(final String value, final Location location)
                throws BadConfigException {
            final int millis;
            try {
                millis = Integer.parseInt(value);
            } catch (final NumberFormatException e) {
                throw new BadConfigException(Section.PEER, location, value, e);
            }
            if (millis < 0)
                throw new BadConfigException(Section.PEER, location, Reason.INVALID_VALUE, value);
            return millis;
        }

        public Builder setEndpoint(final InetEndpoint endpoint) {
            this.endpoint = Optional.of(endpoint);
            return this;
        }

        public Builder setPersistentKeepalive(final int persistentKeepalive)
                throws BadConfigException {
            if (persistentKeepalive < 0 || persistentKeepalive > MAX_PERSISTENT_KEEPALIVE)
                throw new BadConfigException(Section.PEER, Location.PERSISTENT_KEEPALIVE,
                        Reason.INVALID_VALUE, String.valueOf(persistentKeepalive));
            this.persistentKeepalive = persistentKeepalive == 0 ?
                    Optional.empty() : Optional.of(persistentKeepalive);
            return this;
        }

        public Builder setPreSharedKey(final Key preSharedKey) {
            this.preSharedKey = Optional.of(preSharedKey);
            return this;
        }

        public Builder setPublicKey(final Key publicKey) {
            this.publicKey = publicKey;
            return this;
        }
    }
}
