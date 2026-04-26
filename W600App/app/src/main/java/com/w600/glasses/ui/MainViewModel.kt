package com.w600.glasses.ui

import android.app.Application
import android.bluetooth.BluetoothDevice
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.w600.glasses.bluetooth.GlassesManager
import com.w600.glasses.model.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

    val manager = GlassesManager.get(app)

    val connectionState: StateFlow<ConnectionState> = manager.connectionState
    val deviceInfo: StateFlow<DeviceInfo?> = manager.deviceInfo
    val battery: StateFlow<BatteryInfo?> = manager.battery
    val mediaCount: StateFlow<MediaCount?> = manager.mediaCount
    val mediaList: StateFlow<List<MediaFile>> = manager.mediaList
    val previewFrames: SharedFlow<ByteArray> = manager.previewFrames
    val aiFrames: SharedFlow<ByteArray> = manager.aiFrames
    val aiStatus: StateFlow<String> = manager.aiStatus
    val aiTriggers: SharedFlow<Unit> = manager.aiTriggers
    val events: SharedFlow<String> = manager.events

    fun pairedGlasses(): List<BluetoothDevice> = manager.pairedGlasses()
    fun allPairedDevices(): List<BluetoothDevice> = manager.allPairedDevices()

    fun connect(device: BluetoothDevice) = manager.connect(device)
    fun disconnect() = manager.disconnect()

    fun refresh() {
        manager.sendDeviceInfoRequest()
        manager.sendBatteryRequest()
        manager.sendMediaCountRequest()
    }

    fun syncTime() = manager.sendTimeSync()
    fun loadMediaList(page: Int = 0) = manager.sendMediaListRequest(page)
    fun startPreview() = manager.startPreview()
    fun stopPreview() = manager.stopPreview()
    fun deleteMedia(id: String) = manager.deleteMedia(id)
    fun downloadMedia(id: String) = manager.downloadMedia(id)
    fun reboot() = manager.reboot()
    fun powerOff() = manager.powerOff()
}
