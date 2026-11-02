package com.itsmart.datecs.client.device

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbManager
import android.util.Log
import com.itsmart.datecs.client.data.model.ConnectionType

data class ScannedDevice(
    val name: String,
    val address: String,
    val type: ConnectionType,
    val isPaired: Boolean = false,
    val vendorId: Int = 0,
    val info: String = ""
)

class DeviceScanner(private val context: Context) {

    companion object {
        private const val TAG = "DeviceScanner"
        const val DATECS_USB_VID = 65520  // 0xFFF0
        const val FTDI_USB_VID = 1027     // 0x0403
        // Casele Datecs pe USB si terminalul BluePad au acelasi VID/PID (0xFFF0/0x0100): nu se pot deosebi dupa ID,
        // de aceea filtrul de pornire automata are doar FTDI, iar casa se conecteaza din aplicatie (Rescaneaza).
    }

    fun scanUsbDevices(): List<ScannedDevice> {
        return try {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
                ?: return emptyList()
            val allDevices = usbManager.deviceList
            Log.d(TAG, "scanUsbDevices: ${allDevices.size} total USB devices")
            allDevices.values.forEach { d ->
                try {
                    Log.d(TAG, "  USB: ${d.deviceName} VID=${d.vendorId} PID=${d.productId}")
                } catch (_: Exception) {}
            }
            val filtered = allDevices.values
                .filter { it.vendorId == DATECS_USB_VID || it.vendorId == FTDI_USB_VID }
            Log.d(TAG, "scanUsbDevices: ${filtered.size} Datecs/FTDI devices after filter")
            filtered.map { device ->
                val friendlyName = when {
                    device.vendorId == DATECS_USB_VID -> "Datecs Fiscal Device"
                    device.vendorId == FTDI_USB_VID -> "Datecs Fiscal Device (FTDI)"
                    else -> try { device.productName ?: "USB Device" } catch (_: Exception) { "USB Device" }
                }
                ScannedDevice(
                    name = friendlyName,
                    address = device.deviceName,
                    type = ConnectionType.USB,
                    vendorId = device.vendorId,
                    info = "USB ${device.deviceName.substringAfterLast("/")}"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "scanUsbDevices exception: ${e.message}", e)
            emptyList()
        }
    }

    @SuppressLint("MissingPermission")
    fun scanBluetoothDevices(): List<ScannedDevice> {
        return try {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return emptyList()
            if (!adapter.isEnabled) return emptyList()
            adapter.bondedDevices?.map { device ->
                ScannedDevice(
                    name = try { device.name ?: "Unknown" } catch (_: Exception) { "Unknown" },
                    address = device.address,
                    type = ConnectionType.BLUETOOTH,
                    isPaired = true,
                    info = when (device.type) {
                        BluetoothDevice.DEVICE_TYPE_LE -> "BLE"
                        BluetoothDevice.DEVICE_TYPE_CLASSIC -> "SPP"
                        BluetoothDevice.DEVICE_TYPE_DUAL -> "Dual"
                        else -> ""
                    }
                )
            } ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "scanBluetoothDevices exception: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Detecteaza fiscal-ul intern (BC50, DP-25X, etc) via com.android.fiscal.FiscalManager.
     * Returneaza maxim un device cu address "internal".
     */
    fun scanInternalFiscal(): List<ScannedDevice> {
        return try {
            val avail = InternalFiscalBridge.isAvailable()
            val status = InternalFiscalBridge.getStatus(context)
            Log.d(TAG, "scanInternalFiscal: available=$avail status=$status")
            if (!avail) return emptyList()
            val info = InternalFiscalBridge.getInfo(context)
            val serial = info?.serialNumber?.takeIf { it.isNotBlank() } ?: ""
            val fw = info?.firmwareName?.takeIf { it.isNotBlank() } ?: "Fiscal intern"
            val infoLine = listOfNotNull(
                info?.firmwareVersion?.takeIf { it.isNotBlank() }?.let { "FW $it" },
                info?.fiscalMemory?.takeIf { it.isNotBlank() }?.let { "FM $it" }
            ).joinToString(" • ").ifEmpty { "Memorie fiscala interna" }
            listOf(
                ScannedDevice(
                    name = if (serial.isNotEmpty()) "$fw ($serial)" else fw,
                    address = "internal",
                    type = ConnectionType.INTERNAL,
                    isPaired = true,
                    info = infoLine
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "scanInternalFiscal exception: ${e.message}", e)
            emptyList()
        }
    }

    fun scanAll(): List<ScannedDevice> {
        val internal = try { scanInternalFiscal() } catch (_: Exception) { emptyList() }
        val usb = try { scanUsbDevices() } catch (_: Exception) { emptyList() }
        val bt = try { scanBluetoothDevices() } catch (_: Exception) { emptyList() }
        // Fiscal intern primul (cel mai relevant pe BC50)
        return internal + usb + bt
    }
}
