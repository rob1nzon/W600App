package com.w600.glasses.ui.scan

import android.bluetooth.BluetoothDevice
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.w600.glasses.R
import com.w600.glasses.databinding.FragmentScanBinding
import com.w600.glasses.databinding.ItemDeviceBinding
import com.w600.glasses.model.ConnectionState
import com.w600.glasses.ui.MainViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ScanFragment : Fragment() {

    private var _binding: FragmentScanBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _binding = FragmentScanBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val adapter = DeviceAdapter { device ->
            viewModel.connect(device)
            findNavController().navigate(R.id.logFragment)
        }
        binding.recycler.layoutManager = LinearLayoutManager(requireContext())
        binding.recycler.adapter = adapter

        binding.btnRefresh.setOnClickListener {
            val devices = viewModel.allPairedDevices()
            adapter.submitList(devices)
            if (devices.isEmpty()) {
                Toast.makeText(requireContext(), "No paired devices. Pair in Bluetooth settings first.", Toast.LENGTH_LONG).show()
            }
        }

        binding.btnOpenLogs.setOnClickListener {
            findNavController().navigate(R.id.logFragment)
        }

        // Auto-load
        val devices = viewModel.allPairedDevices()
        adapter.submitList(devices)

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.connectionState.collectLatest { state ->
                when (state) {
                    is ConnectionState.Connecting -> {
                        binding.statusText.text = "Connecting to ${state.deviceName}…"
                        binding.progressBar.visibility = View.VISIBLE
                    }
                    is ConnectionState.Connected -> {
                        binding.statusText.text = "Connected to ${state.deviceName}"
                        binding.progressBar.visibility = View.GONE
                    }
                    is ConnectionState.Disconnected -> {
                        binding.statusText.text = "Select a device to connect"
                        binding.progressBar.visibility = View.GONE
                    }
                    is ConnectionState.Error -> {
                        binding.statusText.text = "Error: ${state.message}"
                        binding.progressBar.visibility = View.GONE
                    }
                    else -> {}
                }
            }
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}

class DeviceAdapter(private val onClick: (BluetoothDevice) -> Unit) :
    RecyclerView.Adapter<DeviceAdapter.VH>() {

    private val items = mutableListOf<BluetoothDevice>()

    fun submitList(list: List<BluetoothDevice>) {
        items.clear(); items.addAll(list); notifyDataSetChanged()
    }

    inner class VH(val b: ItemDeviceBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, type: Int) =
        VH(ItemDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, pos: Int) {
        val d = items[pos]
        holder.b.deviceName.text = d.name ?: "Unknown"
        holder.b.deviceAddress.text = d.address
        holder.b.root.setOnClickListener { onClick(d) }
    }
}
