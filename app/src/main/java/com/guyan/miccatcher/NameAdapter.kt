package com.guyan.miccatcher

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class NameAdapter(
    private val onDelete: (Int) -> Unit,
    private val onEdit: (Int) -> Unit
) :
    RecyclerView.Adapter<NameAdapter.VH>() {

    private val data = mutableListOf<String>()

    fun submit(list: List<String>) {
        data.clear()
        data.addAll(list)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = data.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_name, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.index.text = (position + 1).toString()
        holder.name.text = data[position]
        holder.del.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onDelete(pos)
        }
        // 长按名字：OCR 认错的字可以手工改
        holder.name.setOnLongClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onEdit(pos)
            true
        }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val index: TextView = v.findViewById(R.id.tvIndex)
        val name: TextView = v.findViewById(R.id.tvName)
        val del: ImageView = v.findViewById(R.id.btnDelete)
    }
}
