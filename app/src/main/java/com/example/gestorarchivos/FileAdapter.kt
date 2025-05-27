package com.example.gestorarchivos

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu // Importar PopupMenu
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FileAdapter(
    private var files: List<File>,
    private val onItemClick: (File) -> Unit, // Para clic normal (abrir carpeta/archivo)
    private val onOptionsClick: (View, File) -> Unit // Para clic en el botón de opciones
) : RecyclerView.Adapter<FileAdapter.FileViewHolder>() {

    class FileViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val fileIcon: ImageView = itemView.findViewById(R.id.file_icon)
        val fileName: TextView = itemView.findViewById(R.id.file_name)
        val fileDetails: TextView = itemView.findViewById(R.id.file_details)
        val moreOptionsButton: ImageView = itemView.findViewById(R.id.more_options_button)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_file, parent, false)
        return FileViewHolder(view)
    }

    override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
        val file = files[position]
        holder.fileName.text = file.name

        if (file.isDirectory) {
            holder.fileIcon.setImageResource(R.drawable.ic_folder)
            holder.fileDetails.text = "Carpeta"
        } else {
            holder.fileIcon.setImageResource(R.drawable.ic_file)
            holder.fileDetails.text = formatFileDetails(file)
        }

        holder.itemView.setOnClickListener {
            onItemClick(file)
        }

        // Manejar el clic en el botón de más opciones
        holder.moreOptionsButton.setOnClickListener {
            onOptionsClick(holder.moreOptionsButton, file)
        }
    }

    override fun getItemCount(): Int = files.size

    fun updateFiles(newFiles: List<File>) {
        files = newFiles
        notifyDataSetChanged()
    }

    private fun formatFileDetails(file: File): String {
        val size = when {
            file.length() < 1024 -> "${file.length()} bytes"
            file.length() < 1024 * 1024 -> "${"%.2f".format(file.length() / 1024.0)} KB"
            file.length() < 1024 * 1024 * 1024 -> "${"%.2f".format(file.length() / (1024.0 * 1024.0))} MB"
            else -> "${"%.2f".format(file.length() / (1024.0 * 1024.0 * 1024.0))} GB"
        }
        val lastModifiedDate = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(file.lastModified()))
        return "$size | $lastModifiedDate"
    }
}