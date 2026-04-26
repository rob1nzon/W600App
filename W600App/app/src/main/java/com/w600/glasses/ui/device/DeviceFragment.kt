package com.w600.glasses.ui.device

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.w600.glasses.databinding.FragmentDeviceBinding
import com.w600.glasses.model.BatteryInfo
import com.w600.glasses.model.DeviceInfo
import com.w600.glasses.ui.MainViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class DeviceFragment : Fragment() {

    private var _binding: FragmentDeviceBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _binding = FragmentDeviceBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.btnRefresh.setOnClickListener { viewModel.refresh() }
        binding.btnSyncTime.setOnClickListener {
            viewModel.syncTime()
            binding.btnSyncTime.text = "Synced ✓"
        }
        binding.btnReboot.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Reboot glasses?")
                .setPositiveButton("Reboot") { _, _ -> viewModel.reboot() }
                .setNegativeButton("Cancel", null)
                .show()
        }
        binding.btnPowerOff.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Power off glasses?")
                .setPositiveButton("Power Off") { _, _ -> viewModel.powerOff() }
                .setNegativeButton("Cancel", null)
                .show()
        }
        binding.btnDisconnect.setOnClickListener { viewModel.disconnect() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.deviceInfo.collectLatest { info -> info?.let { updateDeviceInfo(it) } }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.battery.collectLatest { b -> b?.let { updateBattery(it) } }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.mediaCount.collectLatest { mc ->
                mc?.let {
                    binding.tvPhotos.text = "Photos: ${it.photos}"
                    binding.tvVideos.text = "Videos: ${it.videos}"
                    binding.tvRecords.text = "Recordings: ${it.records}"
                }
            }
        }
    }

    private fun updateDeviceInfo(info: DeviceInfo) {
        binding.tvDeviceName.text = info.devName
        binding.tvModel.text = "Model: ${info.prodMode}"
        binding.tvFirmware.text = "Firmware: ${info.softVer}"
        binding.tvMac.text = "MAC: ${info.macAddr}"
        binding.tvScreen.text = "Screen: ${info.screen}  Preview: ${info.previewW}×${info.previewH}"
        val used = info.totalMemory - info.remainMemory
        if (info.totalMemory > 0) {
            val pct = (used * 100 / info.totalMemory).toInt()
            binding.tvStorage.text = "Storage: ${formatBytes(used)} / ${formatBytes(info.totalMemory)} ($pct%)"
            binding.storageBar.progress = pct
        }
    }

    private fun updateBattery(b: BatteryInfo) {
        val chargingStr = if (b.isCharging) " ⚡ Charging" else ""
        binding.tvBattery.text = "Battery: ${b.level}%$chargingStr"
        binding.batteryBar.progress = b.level
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1_073_741_824 -> "${"%.1f".format(bytes / 1_073_741_824.0)} GB"
            bytes >= 1_048_576    -> "${"%.1f".format(bytes / 1_048_576.0)} MB"
            bytes >= 1024         -> "${"%.1f".format(bytes / 1024.0)} KB"
            else                  -> "$bytes B"
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
