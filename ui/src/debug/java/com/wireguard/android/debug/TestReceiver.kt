/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.debug

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import com.wireguard.android.Application
import com.wireguard.android.backend.Tunnel
import com.wireguard.android.util.applicationScope
import com.wireguard.config.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Debug-only broadcast receiver that lets the on-device e2e script ([scripts/e2e-android.sh]) drive
 * the app over adb: import a config, bring a tunnel up/down, query its state/handshake/traffic, and
 * generate in-VPN traffic (HTTP GET, ping). It is present only in the debug build type and is
 * reachable only from the adb shell (guarded by android.permission.DUMP in the debug manifest).
 * Results are returned via the ordered-broadcast result data as {@code OK:<payload>} / {@code ERR:<msg>}.
 */
class TestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val pending = goAsync()
        Application.get().applicationScope.launch(Dispatchers.IO) {
            val result = try {
                withTimeout(TIMEOUT_MS) { handle(action, intent) }
            } catch (e: Throwable) {
                "ERR:${e.message ?: e.javaClass.simpleName}"
            }
            pending.setResult(Activity.RESULT_OK, result, null)
            pending.finish()
        }
    }

    private suspend fun handle(action: String, intent: Intent): String = when (action) {
        ACTION_IMPORT -> importConfig(intent)
        ACTION_UP -> setState(intent, Tunnel.State.UP)
        ACTION_DOWN -> setState(intent, Tunnel.State.DOWN)
        ACTION_STATE -> getState(intent)
        ACTION_HTTP -> httpGet(intent)
        ACTION_PING -> ping(intent)
        else -> "ERR:unknown_action"
    }

    private suspend fun importConfig(intent: Intent): String {
        val name = validName(intent.getStringExtra("name")) ?: return ERR_ARG
        val b64 = intent.getStringExtra("config_b64") ?: return ERR_ARG
        val config = Config.parse(ByteArrayInputStream(Base64.decode(b64, Base64.DEFAULT)))
        val manager = Application.getTunnelManager()
        manager.getTunnels()[name]?.let { manager.delete(it) }
        manager.create(name, config)
        return "OK:imported"
    }

    private suspend fun setState(intent: Intent, state: Tunnel.State): String {
        val name = validName(intent.getStringExtra("name")) ?: return ERR_ARG
        val tunnel = Application.getTunnelManager().getTunnels()[name] ?: return "ERR:no_tunnel"
        val newState = Application.getTunnelManager().setTunnelState(tunnel, state)
        return "OK:state=$newState"
    }

    private suspend fun getState(intent: Intent): String {
        val name = validName(intent.getStringExtra("name")) ?: return ERR_ARG
        val tunnel = Application.getTunnelManager().getTunnels()[name] ?: return "ERR:no_tunnel"
        val backend = Application.getBackend()
        val state = backend.getState(tunnel)
        val stats = backend.getStatistics(tunnel)
        var handshake = 0L
        for (key in stats.peers()) {
            val peer = stats.peer(key) ?: continue
            if (peer.latestHandshakeEpochMillis > handshake)
                handshake = peer.latestHandshakeEpochMillis
        }
        return "OK:state=$state handshake_epoch_ms=$handshake rx=${stats.totalRx()} tx=${stats.totalTx()}"
    }

    private fun httpGet(intent: Intent): String {
        val url = intent.getStringExtra("url") ?: return ERR_ARG
        if (!url.startsWith("http://") && !url.startsWith("https://"))
            return ERR_ARG
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = HTTP_TIMEOUT_MS
            connection.readTimeout = HTTP_TIMEOUT_MS
            connection.requestMethod = "GET"
            val body = connection.inputStream.bufferedReader().use { it.readLine() ?: "" }
            "OK:${body.trim()}"
        } finally {
            connection.disconnect()
        }
    }

    private fun ping(intent: Intent): String {
        val host = validHost(intent.getStringExtra("host")) ?: return ERR_ARG
        // Array form: the host is passed as a single argv element, never through a shell.
        val process = Runtime.getRuntime().exec(arrayOf("/system/bin/ping", "-c", "3", "-W", "5", host))
        process.inputStream.bufferedReader().use { it.readText() }
        val exit = process.waitFor()
        return "OK:exit=$exit"
    }

    private fun validName(value: String?): String? =
        if (value != null && NAME_PATTERN.matches(value)) value else null

    private fun validHost(value: String?): String? =
        if (value != null && HOST_PATTERN.matches(value)) value else null

    companion object {
        private const val ACTION_IMPORT = "com.wireguard.android.debug.IMPORT_CONFIG"
        private const val ACTION_UP = "com.wireguard.android.debug.TUNNEL_UP"
        private const val ACTION_DOWN = "com.wireguard.android.debug.TUNNEL_DOWN"
        private const val ACTION_STATE = "com.wireguard.android.debug.GET_STATE"
        private const val ACTION_HTTP = "com.wireguard.android.debug.HTTP_GET"
        private const val ACTION_PING = "com.wireguard.android.debug.PING"
        private const val ERR_ARG = "ERR:bad_argument"
        private const val TIMEOUT_MS = 30_000L
        private const val HTTP_TIMEOUT_MS = 10_000
        private val NAME_PATTERN = Regex("^[A-Za-z0-9_=+.-]{1,60}$")
        private val HOST_PATTERN = Regex("^[A-Za-z0-9_.:-]{1,255}$")
    }
}
