/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.viewmodel

import android.os.Parcel
import android.os.Parcelable
import androidx.databinding.BaseObservable
import androidx.databinding.Bindable
import androidx.databinding.Observable
import androidx.databinding.Observable.OnPropertyChangedCallback
import androidx.databinding.ObservableList
import com.wireguard.android.BR
import com.wireguard.config.Attribute
import com.wireguard.config.BadConfigException
import com.wireguard.config.Peer
import java.lang.ref.WeakReference

class PeerProxy : BaseObservable, Parcelable {
    private val dnsRoutes: MutableList<String?> = ArrayList()
    private var allowedIpsState = AllowedIpsState.INVALID
    private var interfaceDnsListener: InterfaceDnsListener? = null
    private var peerListListener: PeerListListener? = null
    private var owner: ConfigProxy? = null
    private var totalPeers = 0

    @get:Bindable
    var allowedIps: String = ""
        set(value) {
            field = value
            notifyPropertyChanged(BR.allowedIps)
            calculateAllowedIpsState()
        }

    @get:Bindable
    var endpoint: String = ""
        set(value) {
            field = value
            notifyPropertyChanged(BR.endpoint)
            notifyPropertyChanged(BR.wsEndpoint)
        }

    @get:Bindable
    var wsMode: String = ""
        set(value) {
            field = value
            notifyPropertyChanged(BR.wsMode)
        }

    @get:Bindable
    var wstunnelTarget: String = ""
        set(value) {
            field = value
            notifyPropertyChanged(BR.wstunnelTarget)
        }

    @get:Bindable
    var wsBearer: String = ""
        set(value) {
            field = value
            notifyPropertyChanged(BR.wsBearer)
        }

    @get:Bindable
    var wsMask: Boolean = false
        set(value) {
            field = value
            notifyPropertyChanged(BR.wsMask)
        }

    @get:Bindable
    var wsTlsCa: String = ""
        set(value) {
            field = value
            notifyPropertyChanged(BR.wsTlsCa)
        }

    @get:Bindable
    var wsTlsCert: String = ""
        set(value) {
            field = value
            notifyPropertyChanged(BR.wsTlsCert)
        }

    @get:Bindable
    var wsTlsKey: String = ""
        set(value) {
            field = value
            notifyPropertyChanged(BR.wsTlsKey)
        }

    @get:Bindable
    var wsTlsInsecure: Boolean = false
        set(value) {
            field = value
            notifyPropertyChanged(BR.wsTlsInsecure)
        }

    @get:Bindable
    var wsPingInterval: String = ""
        set(value) {
            field = value
            notifyPropertyChanged(BR.wsPingInterval)
        }

    @get:Bindable
    var wsBackoffMin: String = ""
        set(value) {
            field = value
            notifyPropertyChanged(BR.wsBackoffMin)
        }

    @get:Bindable
    var wsBackoffMax: String = ""
        set(value) {
            field = value
            notifyPropertyChanged(BR.wsBackoffMax)
        }

    @get:Bindable
    val isWsEndpoint: Boolean
        get() = endpoint.startsWith("ws://", true) || endpoint.startsWith("wss://", true)

    @get:Bindable
    var persistentKeepalive: String = ""
        set(value) {
            field = value
            notifyPropertyChanged(BR.persistentKeepalive)
        }

    @get:Bindable
    var preSharedKey: String = ""
        set(value) {
            field = value
            notifyPropertyChanged(BR.preSharedKey)
        }

    @get:Bindable
    var publicKey: String = ""
        set(value) {
            field = value
            notifyPropertyChanged(BR.publicKey)
        }

    @get:Bindable
    val isAbleToExcludePrivateIps: Boolean
        get() = allowedIpsState == AllowedIpsState.CONTAINS_IPV4_PUBLIC_NETWORKS || allowedIpsState == AllowedIpsState.CONTAINS_IPV4_WILDCARD

    @get:Bindable
    val isExcludingPrivateIps: Boolean
        get() = allowedIpsState == AllowedIpsState.CONTAINS_IPV4_PUBLIC_NETWORKS

    private constructor(parcel: Parcel) {
        allowedIps = parcel.readString() ?: ""
        endpoint = parcel.readString() ?: ""
        persistentKeepalive = parcel.readString() ?: ""
        preSharedKey = parcel.readString() ?: ""
        publicKey = parcel.readString() ?: ""
        wsMode = parcel.readString() ?: ""
        wstunnelTarget = parcel.readString() ?: ""
        wsBearer = parcel.readString() ?: ""
        wsMask = parcel.readInt() != 0
        wsTlsCa = parcel.readString() ?: ""
        wsTlsCert = parcel.readString() ?: ""
        wsTlsKey = parcel.readString() ?: ""
        wsTlsInsecure = parcel.readInt() != 0
        wsPingInterval = parcel.readString() ?: ""
        wsBackoffMin = parcel.readString() ?: ""
        wsBackoffMax = parcel.readString() ?: ""
    }

    constructor(other: Peer) {
        allowedIps = Attribute.join(other.allowedIps)
        // A WS peer carries its ws:// URL verbatim on the endpoint field; a UDP peer its host:port.
        endpoint = other.wsUrl.map { it.toString() }.orElseGet { other.endpoint.map { it.toString() }.orElse("") }
        persistentKeepalive = other.persistentKeepalive.map { it.toString() }.orElse("")
        preSharedKey = other.preSharedKey.map { it.toBase64() }.orElse("")
        publicKey = other.publicKey.toBase64()
        // getName() (the lowercase wire form), NOT Kotlin's it.name (the enum constant name).
        wsMode = other.wsMode.map { it.getName() }.orElse("")
        wstunnelTarget = other.wstunnelTarget.orElse("")
        wsBearer = other.wsBearer.orElse("")
        wsMask = other.wsMask
        wsTlsCa = other.wsTlsCa.orElse("")
        wsTlsCert = other.wsTlsCert.orElse("")
        wsTlsKey = other.wsTlsKey.orElse("")
        wsTlsInsecure = other.wsTlsInsecure
        wsPingInterval = other.wsPingIntervalMs.map { it.toString() }.orElse("")
        wsBackoffMin = other.wsBackoffMinMs.map { it.toString() }.orElse("")
        wsBackoffMax = other.wsBackoffMaxMs.map { it.toString() }.orElse("")
    }

    constructor()

    fun bind(owner: ConfigProxy) {
        val interfaze: InterfaceProxy = owner.`interface`
        val peers = owner.peers
        if (interfaceDnsListener == null) interfaceDnsListener = InterfaceDnsListener(this)
        interfaze.addOnPropertyChangedCallback(interfaceDnsListener!!)
        setInterfaceDns(interfaze.dnsServers)
        if (peerListListener == null) peerListListener = PeerListListener(this)
        peers.addOnListChangedCallback(peerListListener)
        setTotalPeers(peers.size)
        this.owner = owner
    }

    private fun calculateAllowedIpsState() {
        val newState: AllowedIpsState
        newState = if (totalPeers == 1) {
            // String comparison works because we only care if allowedIps is a superset of one of
            // the above sets of (valid) *networks*. We are not checking for a superset based on
            // the individual addresses in each set.
            val networkStrings: Collection<String> = getAllowedIpsSet()
            // If allowedIps contains both the wildcard and the public networks, then private
            // networks aren't excluded!
            if (networkStrings.containsAll(IPV4_WILDCARD))
                AllowedIpsState.CONTAINS_IPV4_WILDCARD
            else if (networkStrings.containsAll(IPV4_PUBLIC_NETWORKS))
                AllowedIpsState.CONTAINS_IPV4_PUBLIC_NETWORKS
            else
                AllowedIpsState.OTHER
        } else {
            AllowedIpsState.INVALID
        }
        if (newState != allowedIpsState) {
            allowedIpsState = newState
            notifyPropertyChanged(BR.ableToExcludePrivateIps)
            notifyPropertyChanged(BR.excludingPrivateIps)
        }
    }

    override fun describeContents() = 0

    private fun getAllowedIpsSet() = setOf(*Attribute.split(allowedIps))

    // Replace the first instance of the wildcard with the public network list, or vice versa.
    // DNS servers only need to handled specially when we're excluding private IPs.
    fun setExcludingPrivateIps(excludingPrivateIps: Boolean) {
        if (!isAbleToExcludePrivateIps || isExcludingPrivateIps == excludingPrivateIps) return
        val oldNetworks = if (excludingPrivateIps) IPV4_WILDCARD else IPV4_PUBLIC_NETWORKS
        val newNetworks = if (excludingPrivateIps) IPV4_PUBLIC_NETWORKS else IPV4_WILDCARD
        val input: Collection<String> = getAllowedIpsSet()
        val outputSize = input.size - oldNetworks.size + newNetworks.size
        val output: MutableCollection<String?> = LinkedHashSet(outputSize)
        var replaced = false
        // Replace the first instance of the wildcard with the public network list, or vice versa.
        for (network in input) {
            if (oldNetworks.contains(network)) {
                if (!replaced) {
                    for (replacement in newNetworks) if (!output.contains(replacement)) output.add(replacement)
                    replaced = true
                }
            } else if (!output.contains(network)) {
                output.add(network)
            }
        }
        // DNS servers only need to handled specially when we're excluding private IPs.
        if (excludingPrivateIps) output.addAll(dnsRoutes) else output.removeAll(dnsRoutes)
        allowedIps = Attribute.join(output)
        allowedIpsState = if (excludingPrivateIps) AllowedIpsState.CONTAINS_IPV4_PUBLIC_NETWORKS else AllowedIpsState.CONTAINS_IPV4_WILDCARD
        notifyPropertyChanged(BR.allowedIps)
        notifyPropertyChanged(BR.excludingPrivateIps)
    }

    @Throws(BadConfigException::class)
    fun resolve(): Peer {
        val builder = Peer.Builder()
        if (allowedIps.isNotEmpty()) builder.parseAllowedIPs(allowedIps)
        // parseEndpoint routes a ws:// URL to the WS endpoint parser.
        if (endpoint.isNotEmpty()) builder.parseEndpoint(endpoint)
        if (persistentKeepalive.isNotEmpty()) builder.parsePersistentKeepalive(persistentKeepalive)
        if (preSharedKey.isNotEmpty()) builder.parsePreSharedKey(preSharedKey)
        if (publicKey.isNotEmpty()) builder.parsePublicKey(publicKey)
        if (wsMode.isNotEmpty()) builder.parseWsMode(wsMode)
        if (wstunnelTarget.isNotEmpty()) builder.parseWstunnelTarget(wstunnelTarget)
        if (wsBearer.isNotEmpty()) builder.parseWsBearer(wsBearer)
        if (wsMask) builder.parseWsMask("true")
        if (wsTlsCa.isNotEmpty()) builder.parseWsTlsCa(wsTlsCa)
        if (wsTlsCert.isNotEmpty()) builder.parseWsTlsCert(wsTlsCert)
        if (wsTlsKey.isNotEmpty()) builder.parseWsTlsKey(wsTlsKey)
        if (wsTlsInsecure) builder.parseWsTlsInsecure("true")
        if (wsPingInterval.isNotEmpty()) builder.parseWsPingInterval(wsPingInterval)
        if (wsBackoffMin.isNotEmpty()) builder.parseWsBackoffMin(wsBackoffMin)
        if (wsBackoffMax.isNotEmpty()) builder.parseWsBackoffMax(wsBackoffMax)
        return builder.build()
    }

    fun setWsFile(kind: WsFileKind, path: String) {
        when (kind) {
            WsFileKind.CA -> wsTlsCa = path
            WsFileKind.CERT -> wsTlsCert = path
            WsFileKind.KEY -> wsTlsKey = path
        }
    }

    enum class WsFileKind { CA, CERT, KEY }

    private fun setInterfaceDns(dnsServers: CharSequence) {
        val newDnsRoutes = Attribute.split(dnsServers).filter { !it.contains(":") }.map { "$it/32" }
        if (allowedIpsState == AllowedIpsState.CONTAINS_IPV4_PUBLIC_NETWORKS) {
            val input = getAllowedIpsSet()
            // Yes, this is quadratic in the number of DNS servers, but most users have 1 or 2.
            val output = input.filter { !dnsRoutes.contains(it) || newDnsRoutes.contains(it) }.plus(newDnsRoutes).distinct()
            // None of the public networks are /32s, so this cannot change the AllowedIPs state.
            allowedIps = Attribute.join(output)
            notifyPropertyChanged(BR.allowedIps)
        }
        dnsRoutes.clear()
        dnsRoutes.addAll(newDnsRoutes)
    }

    private fun setTotalPeers(totalPeers: Int) {
        if (this.totalPeers == totalPeers) return
        this.totalPeers = totalPeers
        calculateAllowedIpsState()
    }

    fun unbind() {
        if (owner == null) return
        val interfaze: InterfaceProxy = owner!!.`interface`
        val peers = owner!!.peers
        if (interfaceDnsListener != null) interfaze.removeOnPropertyChangedCallback(interfaceDnsListener!!)
        if (peerListListener != null) peers.removeOnListChangedCallback(peerListListener)
        peers.remove(this)
        setInterfaceDns("")
        setTotalPeers(0)
        owner = null
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(allowedIps)
        dest.writeString(endpoint)
        dest.writeString(persistentKeepalive)
        dest.writeString(preSharedKey)
        dest.writeString(publicKey)
        dest.writeString(wsMode)
        dest.writeString(wstunnelTarget)
        dest.writeString(wsBearer)
        dest.writeInt(if (wsMask) 1 else 0)
        dest.writeString(wsTlsCa)
        dest.writeString(wsTlsCert)
        dest.writeString(wsTlsKey)
        dest.writeInt(if (wsTlsInsecure) 1 else 0)
        dest.writeString(wsPingInterval)
        dest.writeString(wsBackoffMin)
        dest.writeString(wsBackoffMax)
    }

    private enum class AllowedIpsState {
        CONTAINS_IPV4_PUBLIC_NETWORKS, CONTAINS_IPV4_WILDCARD, INVALID, OTHER
    }

    private class InterfaceDnsListener constructor(peerProxy: PeerProxy) : OnPropertyChangedCallback() {
        private val weakPeerProxy: WeakReference<PeerProxy> = WeakReference(peerProxy)
        override fun onPropertyChanged(sender: Observable, propertyId: Int) {
            val peerProxy = weakPeerProxy.get()
            if (peerProxy == null) {
                sender.removeOnPropertyChangedCallback(this)
                return
            }
            // This shouldn't be possible, but try to avoid a ClassCastException anyway.
            if (sender !is InterfaceProxy) return
            if (!(propertyId == BR._all || propertyId == BR.dnsServers)) return
            peerProxy.setInterfaceDns(sender.dnsServers)
        }
    }

    private class PeerListListener(peerProxy: PeerProxy) : ObservableList.OnListChangedCallback<ObservableList<PeerProxy?>>() {
        private val weakPeerProxy: WeakReference<PeerProxy> = WeakReference(peerProxy)
        override fun onChanged(sender: ObservableList<PeerProxy?>) {
            val peerProxy = weakPeerProxy.get()
            if (peerProxy == null) {
                sender.removeOnListChangedCallback(this)
                return
            }
            peerProxy.setTotalPeers(sender.size)
        }

        override fun onItemRangeChanged(
            sender: ObservableList<PeerProxy?>,
            positionStart: Int, itemCount: Int
        ) {
            // Do nothing.
        }

        override fun onItemRangeInserted(
            sender: ObservableList<PeerProxy?>,
            positionStart: Int, itemCount: Int
        ) {
            onChanged(sender)
        }

        override fun onItemRangeMoved(
            sender: ObservableList<PeerProxy?>,
            fromPosition: Int, toPosition: Int,
            itemCount: Int
        ) {
            // Do nothing.
        }

        override fun onItemRangeRemoved(
            sender: ObservableList<PeerProxy?>,
            positionStart: Int, itemCount: Int
        ) {
            onChanged(sender)
        }
    }

    private class PeerProxyCreator : Parcelable.Creator<PeerProxy> {
        override fun createFromParcel(parcel: Parcel): PeerProxy {
            return PeerProxy(parcel)
        }

        override fun newArray(size: Int): Array<PeerProxy?> {
            return arrayOfNulls(size)
        }
    }

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<PeerProxy> = PeerProxyCreator()
        private val IPV4_PUBLIC_NETWORKS = setOf(
            "0.0.0.0/5", "8.0.0.0/7", "11.0.0.0/8", "12.0.0.0/6", "16.0.0.0/4", "32.0.0.0/3",
            "64.0.0.0/2", "128.0.0.0/3", "160.0.0.0/5", "168.0.0.0/6", "172.0.0.0/12",
            "172.32.0.0/11", "172.64.0.0/10", "172.128.0.0/9", "173.0.0.0/8", "174.0.0.0/7",
            "176.0.0.0/4", "192.0.0.0/9", "192.128.0.0/11", "192.160.0.0/13", "192.169.0.0/16",
            "192.170.0.0/15", "192.172.0.0/14", "192.176.0.0/12", "192.192.0.0/10",
            "193.0.0.0/8", "194.0.0.0/7", "196.0.0.0/6", "200.0.0.0/5", "208.0.0.0/4"
        )
        private val IPV4_WILDCARD = setOf("0.0.0.0/0")
    }
}
