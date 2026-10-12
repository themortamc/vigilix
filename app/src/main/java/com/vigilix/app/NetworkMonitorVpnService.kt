package com.vigilix.app

import android.app.Service
import android.app.VpnService
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.channels.ReceiveChannel

/**
 * VpnService para monitor de red NonRoot.
 * Captura paquetes salientes y detecta conexiones a IPs/C2 sospechosos.
 */
class NetworkMonitorVpnService : VpnService() {

    companion object {
        private const val TAG = "NetworkMonitorVpn"
        private const val VPN_INTERFACE = "tun0"
        private const val MTU = 1400
        private const val VPN_NETWORK = "10.0.0.0/24"
        private const val VPN_GATEWAY = "10.0.0.1"
        private const val DNS_SERVER = "1.1.1.1"

        private val suspiciousPorts = setOf(4444, 6667, 8080, 8443, 9999, 31337, 12345, 54321)
        private val standardPorts = setOf(80, 443, 53, 123, 465, 587, 993, 995)

        @Volatile private var INSTANCE: NetworkMonitorVpnService? = null

        fun getInstance(): NetworkMonitorVpnService? = INSTANCE

        fun start(context: Context) {
            val intent = Intent(context, NetworkMonitorVpnService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, NetworkMonitorVpnService::class.java)
            context.stopService(intent)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val packetChannel = Channel<ByteBuffer>(1000)
    private val alerts = MutableLiveData<NetworkAlert>()
    val alertsLiveData: LiveData<NetworkAlert> = alerts

    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnInput: FileInputStream? = null
    private var vpnOutput: FileOutputStream? = null
    private var running = false

    override fun onCreate() {
        super.onCreate()
        INSTANCE = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!running) {
            startVpn()
        }
        return Service.START_STICKY
    }

    override fun onDestroy() {
        stopVpn()
        INSTANCE = null
        super.onDestroy()
    }

    private fun startVpn() {
        val builder = Builder()
            .addAddress("10.0.0.1", 24)
            .addRoute("0.0.0.0", 0)
            .setBlocking(false)
            .setMtu(MTU)
            .addDnsServer(DNS_SERVER)

        try {
            vpnInterface = builder.establish()
            if (vpnInterface == null) {
                Log.e(TAG, "Failed to establish VPN")
                return
            }

            vpnInput = FileInputStream(vpnInterface!!.fileDescriptor)
            vpnOutput = FileOutputStream(vpnInterface!!.fileDescriptor)
            running = true

            scope.launch { processPackets() }

            Log.i(TAG, "VPN started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VPN", e)
            stopVpn()
        }
    }

    private fun stopVpn() {
        running = false
        try {
            vpnInput?.close()
            vpnOutput?.close()
            vpnInterface?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error closing VPN", e)
        }
        scope.cancel()
        INSTANCE = null
    }

    private suspend fun processPackets() = withContext(Dispatchers.IO) {
        val buffer = ByteBuffer.allocate(4096).order(ByteOrder.nativeOrder())
        while (running) {
            try {
                val bytesRead = vpnInput?.read(buffer.array())
                if (bytesRead == null || bytesRead <= 0) {
                    if (!running) break
                    Thread.sleep(10)
                    continue
                }

                buffer.limit(bytesRead)
                buffer.position(0)
                parsePacket(buffer)
                buffer.clear()
            } catch (e: Exception) {
                Log.e(TAG, "Error reading packet", e)
            }
        }
    }

    private fun parsePacket(buffer: ByteBuffer) {
        // Parse IP header (simplified - real implementation needs full IP/TCP/UDP parsing)
        // For production, use a library like jNetPcap or implement full IP/TCP/UDP parsing
        // This is a simplified version that extracts basic info

        val packet = buffer.array().copyOf(buffer.limit())
        if (packet.size < 20) return // Minimum IP header

        // Parse IP header
        val versionIhl = packet[0] and 0xFF
        val ihl = (versionIhl and 0x0F) * 4
        if (ihl < 20) return

        val protocol = packet[9] and 0xFF
        val srcIp = InetAddress.getByAddress(packet.copyOfRange(12, 16)).hostAddress
        val dstIp = InetAddress.getByAddress(packet.copyOfRange(16, 20)).hostAddress

        // Check if destination IP is suspicious
        if (isSuspiciousIp(dstIp)) {
            val alert = NetworkAlert(
                timestamp = System.currentTimeMillis(),
                srcIp = srcIp,
                dstIp = dstIp,
                protocol = protocol,
                alertType = AlertType.SUSPICIOUS_IP,
                details = "Conexión a IP sospechosa: $dstIp"
            )
            alerts.postValue(alert)
        }

        // Check suspicious ports if TCP/UDP
        if (protocol == 6 || protocol == 17) { // TCP or UDP
            if (packet.size >= ihl + 4) {
                val srcPort = ((packet[ihl] and 0xFF) shl 8) or (packet[ihl + 1] and 0xFF)
                val dstPort = ((packet[ihl + 2] and 0xFF) shl 8) or (packet[ihl + 3] and 0xFF)

                if (suspiciousPorts.contains(dstPort) && !standardPorts.contains(dstPort)) {
                    val alert = NetworkAlert(
                        timestamp = System.currentTimeMillis(),
                        srcIp = srcIp,
                        dstIp = dstIp,
                        protocol = protocol,
                        alertType = AlertType.SUSPICIOUS_PORT,
                        details = "Puerto sospechoso: $dstPort (${if (protocol == 6) "TCP" else "UDP"})"
                    )
                    alerts.postValue(alert)
                }
            }
        }
    }

    private fun isSuspiciousIp(ip: String): Boolean {
        // Check against local threat intel (C2 IPs)
        val threatIntel = ThreatIntelManager.getInstance(this)
        return threatIntel.isC2Ip(ip)
    }

    data class NetworkAlert(
        val timestamp: Long,
        val srcIp: String,
        val dstIp: String,
        val protocol: Int,
        val alertType: AlertType,
        val details: String
    )

    enum class AlertType {
        SUSPICIOUS_IP,
        SUSPICIOUS_PORT,
        C2_CONNECTION,
        DATA_EXFILTRATION
    }
}