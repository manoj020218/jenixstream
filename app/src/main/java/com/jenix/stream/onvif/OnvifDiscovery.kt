package com.jenix.stream.onvif

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.jenix.stream.data.model.Camera
import kotlinx.coroutines.*
import java.net.*
import java.util.concurrent.ConcurrentHashMap

class OnvifDiscovery(private val context: Context) {

    companion object {
        const val TAG = "OnvifDiscovery"
        const val WS_DISCOVERY_PORT = 3702
        const val MULTICAST_ADDRESS = "239.255.255.250"
        const val SCAN_TIMEOUT_MS = 10000L

        val STREAM_PATHS = listOf(
            Pair("/stream1", "Generic (most cameras)"),
            Pair("/stream2", "Sub-stream"),
            Pair("/Streaming/Channels/1", "Hikvision"),
            Pair("/cam/realmonitor?channel=1&subtype=0", "Dahua"),
            Pair("/h264/ch1/main/av_stream", "Foscam"),
            Pair("/live/main", "Generic live"),
            Pair("/live", "Generic"),
            Pair("/video.h264", "Generic H.264")
        )

        val WS_PROBE = """<?xml version="1.0" encoding="UTF-8"?>
<e:Envelope xmlns:e="http://www.w3.org/2003/05/soap-envelope"
  xmlns:w="http://schemas.xmlsoap.org/ws/2004/08/addressing"
  xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery"
  xmlns:dn="http://www.onvif.org/ver10/network/wsdl">
  <e:Header>
    <w:MessageID>uuid:${java.util.UUID.randomUUID()}</w:MessageID>
    <w:To>urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To>
    <w:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action>
  </e:Header>
  <e:Body>
    <d:Probe><d:Types>dn:NetworkVideoTransmitter</d:Types></d:Probe>
  </e:Body>
</e:Envelope>"""
    }

    private val found = ConcurrentHashMap<String, Camera>()
    var onCameraFound: ((Camera) -> Unit)? = null
    var onProgress: ((String) -> Unit)? = null

    suspend fun discover(): List<Camera> = withContext(Dispatchers.IO) {
        found.clear()
        val localIp = getLocalIpAddress()
        val subnet = localIp?.let { ip -> ip.substringBeforeLast(".") } ?: "192.168.1"

        onProgress?.invoke("Local IP: $localIp — Scanning $subnet.x")
        Log.d(TAG, "Starting discovery on subnet $subnet")

        // Phase 1: ONVIF WS-Discovery UDP multicast (2.5s)
        val multicastJob = launch { sendWsDiscovery(localIp) }

        // Phase 2: TCP port scan runs in parallel
        delay(500)
        val portScanJob = launch { portScan(subnet) { camera ->
            if (found.putIfAbsent(camera.ip, camera) == null) {
                onCameraFound?.invoke(camera)
            }
        }}

        delay(SCAN_TIMEOUT_MS)
        multicastJob.cancel()
        portScanJob.cancel()

        onProgress?.invoke("Scan complete: ${found.size} device(s) found")
        found.values.toList()
    }

    private suspend fun sendWsDiscovery(localIp: String?) = withContext(Dispatchers.IO) {
        // Acquire multicast lock (required on Android)
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val multicastLock = wifi.createMulticastLock("jenix_onvif").apply {
            setReferenceCounted(true)
            acquire()
        }

        try {
            val socket = MulticastSocket(WS_DISCOVERY_PORT).apply {
                reuseAddress = true
                soTimeout = 4000
                timeToLive = 4
                try {
                    localIp?.let { joinGroup(InetAddress.getByName(MULTICAST_ADDRESS)) }
                } catch(e: Exception) { Log.w(TAG, "Multicast join failed: ${e.message}") }
            }

            val probe = WS_PROBE.toByteArray(Charsets.UTF_8)
            // Send to multicast and broadcast
            listOf(MULTICAST_ADDRESS, "255.255.255.255").forEach { addr ->
                try {
                    socket.send(DatagramPacket(probe, probe.size, InetAddress.getByName(addr), WS_DISCOVERY_PORT))
                    Log.d(TAG, "WS-Discovery probe sent to $addr")
                    onProgress?.invoke("WS-Discovery probe sent...")
                } catch(e: Exception) { Log.w(TAG, "Send to $addr failed: ${e.message}") }
            }

            // Listen for responses
            val buf = ByteArray(4096)
            val responsePacket = DatagramPacket(buf, buf.size)
            val deadline = System.currentTimeMillis() + 4000

            while (System.currentTimeMillis() < deadline) {
                try {
                    socket.receive(responsePacket)
                    val response = String(responsePacket.data, 0, responsePacket.length)
                    val ip = responsePacket.address.hostAddress ?: continue
                    if (ip == localIp) continue

                    val camera = parseOnvifResponse(ip, response)
                    if (found.putIfAbsent(ip, camera) == null) {
                        Log.d(TAG, "ONVIF device found: $ip — ${camera.name}")
                        onProgress?.invoke("Found via ONVIF: $ip (${camera.name})")
                        onCameraFound?.invoke(camera)
                    }
                } catch(e: SocketTimeoutException) { break }
                catch(e: Exception) { break }
            }

            socket.close()
        } finally {
            try { multicastLock.release() } catch(e: Exception) {}
        }
    }

    private fun parseOnvifResponse(ip: String, xml: String): Camera {
        val xaddrMatch = Regex("<[^>]*XAddrs[^>]*>([^<]+)<").find(xml)
        val nameMatch = Regex("<[^>]*Name[^>]*>([^<]+)<").find(xml)
            ?: Regex("hardware/([^/&<\\s]+)").find(xml)

        var port = "80"
        xaddrMatch?.groupValues?.get(1)?.trim()?.split(" ")?.firstOrNull()?.let { xaddr ->
            try {
                val u = URL(xaddr)
                if (u.port > 0) port = u.port.toString()
            } catch(e: Exception) {}
        }

        val brand = detectBrand(xml)
        val name = nameMatch?.groupValues?.get(1)?.trim()
            ?: brand.ifBlank { "ONVIF Camera" }

        return Camera(ip = ip, port = port, name = name, brand = brand,
            xaddr = xaddrMatch?.groupValues?.get(1)?.trim()?.split(" ")?.firstOrNull() ?: "")
    }

    private fun detectBrand(xml: String): String = when {
        xml.contains("hikvision", ignoreCase = true) -> "Hikvision"
        xml.contains("dahua", ignoreCase = true) -> "Dahua"
        xml.contains("axis", ignoreCase = true) -> "Axis"
        xml.contains("foscam", ignoreCase = true) -> "Foscam"
        xml.contains("reolink", ignoreCase = true) -> "Reolink"
        xml.contains("amcrest", ignoreCase = true) -> "Amcrest"
        xml.contains("hanwha", ignoreCase = true) -> "Hanwha"
        else -> ""
    }

    private suspend fun portScan(subnet: String, onFound: (Camera) -> Unit) {
        val ports = listOf(554, 80, 8080, 8554)
        val ips = (1..254).map { "$subnet.$it" }

        // Scan in batches of 30 concurrent connections
        ips.chunked(30).forEach { batch ->
            batch.map { ip ->
                CoroutineScope(Dispatchers.IO).async {
                    ports.forEach { port ->
                        try {
                            val sock = Socket()
                            sock.connect(InetSocketAddress(ip, port), 400)
                            sock.close()
                            val cam = Camera(
                                ip = ip, port = port.toString(),
                                name = if (port == 554) "IP Camera (RTSP)" else "Network Device",
                                brand = ""
                            )
                            onFound(cam)
                            onProgress?.invoke("Port scan found: $ip:$port")
                            return@async
                        } catch(e: Exception) { /* not reachable */ }
                    }
                }
            }.awaitAll()
        }
    }

    private fun getLocalIpAddress(): String? {
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ip = wifi.connectionInfo.ipAddress
            if (ip != 0) {
                return String.format("%d.%d.%d.%d",
                    ip and 0xff, ip shr 8 and 0xff,
                    ip shr 16 and 0xff, ip shr 24 and 0xff)
            }
        } catch(e: Exception) {}
        // Fallback via network interfaces
        try {
            NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { iface ->
                iface.inetAddresses.toList().forEach { addr ->
                    if (!addr.isLoopbackAddress && addr is Inet4Address) return addr.hostAddress
                }
            }
        } catch(e: Exception) {}
        return null
    }

    // Build RTSP URL from discovered camera + credentials
    fun buildRtspUrl(camera: Camera, username: String, password: String, path: String, port: String): String {
        val creds = if (password.isNotBlank()) "$username:$password@" else "$username@"
        return "rtsp://$creds${camera.ip}:$port$path"
    }

}
