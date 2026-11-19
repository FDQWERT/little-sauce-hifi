package com.lcs.hifi.core

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build

data class UsbEp(
    val address: Int,
    val type: Int,
    val maxPacketSize: Int,
    val interval: Int,
    val direction: Int
) {
    val isIsocOut: Boolean
        get() = type == UsbConstants.USB_ENDPOINT_XFER_ISOC && direction == UsbConstants.USB_DIR_OUT
    val isIsoIn: Boolean
        get() = type == UsbConstants.USB_ENDPOINT_XFER_ISOC && direction == UsbConstants.USB_DIR_IN
}

data class UsbIface(
    val index: Int,
    val cls: Int,
    val sub: Int,
    val proto: Int,
    val endpoints: List<UsbEp>
) {
    val isAudioControl: Boolean get() = cls == UsbConstants.USB_CLASS_AUDIO && sub == 1
    val isAudioStreaming: Boolean get() = cls == UsbConstants.USB_CLASS_AUDIO && sub == 2
    val outEndpoint: UsbEp? get() = endpoints.firstOrNull { it.isIsocOut }
}

@Suppress("DEPRECATION")
data class RawUsb(
    val deviceName: String,
    val vendorId: Int,
    val productId: Int,
    val manufacturer: String?,
    val product: String?,
    val serial: String?,
    val interfaces: List<UsbIface>,
    val hasPermission: Boolean,
    val device: UsbDevice
) {
    val isAudio: Boolean get() = interfaces.any { it.cls == UsbConstants.USB_CLASS_AUDIO }
    val vidPid: String get() = String.format("%04X:%04X", vendorId, productId)
    val title: String get() = product?.takeIf { it.isNotBlank() } ?: manufacturer?.takeIf { it.isNotBlank() } ?: "USB 设备"

    val streamingInterface: UsbIface? get() = interfaces.firstOrNull { it.isAudioStreaming && it.outEndpoint != null }

    fun describe(): String {
        val parts = mutableListOf<String>()
        parts += "VID:PID $vidPid"
        interfaces.forEach { i ->
            val cls = when {
                i.isAudioControl -> "AudioCtl"
                i.isAudioStreaming -> "AudioStream"
                else -> "cls=${i.cls}/${i.sub}"
            }
            parts += "$cls#${i.index}"
        }
        return parts.joinToString(" · ")
    }
}

/**
 * USB 设备枚举 + 权限申请 + 打开连接。
 *
 * 注意：Android 的 UsbManager 只能看 descriptor 的摘要，拿不到 UAC 的 class-specific
 * 描述符里完整的格式表；真正的采样率能力以内核暴露给 AudioFlinger 的
 * AudioDeviceInfo.sampleRates 为准（见 AudioRouteManager）。
 */
class UsbHub(private val ctx: Context) {

    companion object {
        const val ACTION_USB_PERM = "com.lcs.hifi.USB_PERMISSION"
    }

    private val um = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
    private val listeners = HashMap<String, (Boolean) -> Unit>()
    private var receiver: BroadcastReceiver? = null

    @Suppress("DEPRECATION")
    fun devices(): List<RawUsb> {
        val list = mutableListOf<RawUsb>()
        for ((name, dev) in um.deviceList) {
            val ifaces = mutableListOf<UsbIface>()
            for (i in 0 until dev.interfaceCount) {
                val itf: UsbInterface = dev.getInterface(i)
                val eps = mutableListOf<UsbEp>()
                for (e in 0 until itf.endpointCount) {
                    val ep: UsbEndpoint = itf.getEndpoint(e)
                    eps += UsbEp(ep.address, ep.type, ep.maxPacketSize, ep.interval, ep.direction)
                }
                ifaces += UsbIface(itf.id, itf.interfaceClass, itf.interfaceSubclass, itf.interfaceProtocol, eps)
            }
            list += RawUsb(
                deviceName = name,
                vendorId = dev.vendorId,
                productId = dev.productId,
                manufacturer = safeRead { dev.manufacturerName },
                product = safeRead { dev.productName },
                serial = safeRead { dev.serialNumber },
                interfaces = ifaces,
                hasPermission = um.hasPermission(dev),
                device = dev
            )
        }
        return list.sortedBy { it.deviceName }
    }

    private inline fun safeRead(block: () -> String?): String? = try {
        block()
    } catch (t: Throwable) {
        null
    }

    fun audioDevices(): List<RawUsb> = devices().filter { it.isAudio }

    fun requestPermission(dev: UsbDevice, onResult: (Boolean) -> Unit) {
        if (um.hasPermission(dev)) {
            onResult(true)
            return
        }
        ensureReceiver()
        listeners[dev.deviceName] = onResult
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_MUTABLE else 0
        val intent = Intent(ACTION_USB_PERM).setPackage(ctx.packageName)
        val pi = PendingIntent.getBroadcast(ctx, dev.deviceName.hashCode(), intent, flags)
        um.requestPermission(dev, pi)
    }

    private fun ensureReceiver() {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != ACTION_USB_PERM) return
                @Suppress("DEPRECATION")
                val dev = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                val key = dev?.deviceName ?: ""
                val cb = listeners.remove(key)
                cb?.invoke(granted)
            }
        }
        receiver = r
        val filter = IntentFilter(ACTION_USB_PERM)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            ctx.registerReceiver(r, filter)
        }
    }

    fun open(dev: UsbDevice): UsbDeviceConnection? = try {
        um.openDevice(dev)
    } catch (t: Throwable) {
        null
    }

    fun release() {
        receiver?.let {
            try {
                ctx.unregisterReceiver(it)
            } catch (_: Throwable) {
            }
        }
        receiver = null
        listeners.clear()
    }
}
