package com.w600.glasses.ui.log

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.w600.glasses.databinding.FragmentLogBinding
import com.w600.glasses.databinding.ItemLogBinding
import com.w600.glasses.util.AppLogger
import com.w600.glasses.util.LogEntry
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class LogFragment : Fragment() {

    private var _binding: FragmentLogBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _binding = FragmentLogBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val adapter = LogAdapter()
        val layoutManager = LinearLayoutManager(requireContext())
        binding.logRecycler.layoutManager = layoutManager
        binding.logRecycler.adapter = adapter

        binding.btnClear.setOnClickListener { AppLogger.clear() }
        binding.btnCopy.setOnClickListener {
            val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("BT Logs", AppLogger.all()))
            Toast.makeText(requireContext(), "Copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            AppLogger.flow.collectLatest { entries ->
                binding.tvCount.text = "${entries.size} entries"
                val wasAtBottom = layoutManager.findLastVisibleItemPosition() >= adapter.itemCount - 3
                adapter.submitList(entries)
                if (wasAtBottom && entries.isNotEmpty()) {
                    binding.logRecycler.scrollToPosition(entries.size - 1)
                }
            }
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}

class LogAdapter : RecyclerView.Adapter<LogAdapter.VH>() {
    private var items: List<LogEntry> = emptyList()

    fun submitList(list: List<LogEntry>) { items = list; notifyDataSetChanged() }

    inner class VH(val b: ItemLogBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, type: Int) =
        VH(ItemLogBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, pos: Int) {
        val e = items[pos]
        holder.b.tvTs.text = e.ts
        holder.b.tvLevel.text = e.level.toString()
        holder.b.tvMsg.text = "${e.tag}: ${e.msg}"
        val color = when (e.level) {
            'E' -> Color.parseColor("#FF5252")
            'W' -> Color.parseColor("#FFB300")
            'I' -> Color.parseColor("#40C4FF")
            else -> holder.b.tvMsg.currentTextColor
        }
        holder.b.tvLevel.setTextColor(color)
        holder.b.tvMsg.setTextColor(color)
    }
}
