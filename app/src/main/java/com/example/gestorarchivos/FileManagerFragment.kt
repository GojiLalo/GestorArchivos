package com.example.gestorarchivos

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FileManagerFragment : Fragment(), BluetoothService.BluetoothServiceListener {

    private lateinit var recyclerView: RecyclerView
    private lateinit var fileAdapter: FileAdapter
    private lateinit var currentPathTextView: TextView
    private lateinit var upButton: ImageView
    private lateinit var bluetoothService: BluetoothService
    private val bluetoothHandler = Handler(Looper.getMainLooper()) // Handler para callbacks de BluetoothService en UI Thread

    private var currentDirectory: File = Environment.getExternalStorageDirectory()
    private var fileToCopy: File? = null

    // Vistas para el progreso de transferencia
    private lateinit var transferProgressBar: ProgressBar
    private lateinit var transferProgressText: TextView
    private lateinit var transferFileNameText: TextView


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bluetoothService = BluetoothService(requireContext(), bluetoothHandler)
        bluetoothService.setListener(this)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_file_manager, container, false)

        recyclerView = view.findViewById(R.id.file_list_recyclerview)
        currentPathTextView = view.findViewById(R.id.current_path_text_view)
        upButton = view.findViewById(R.id.up_button)

        transferProgressBar = view.findViewById(R.id.transfer_progress_bar) // Asegúrate de añadir esto a tu layout
        transferProgressText = view.findViewById(R.id.transfer_progress_text) // Asegúrate de añadir esto a tu layout
        transferFileNameText = view.findViewById(R.id.transfer_file_name_text) // Asegúrate de añadir esto a tu layout

        // Ocultar la barra de progreso inicialmente
        transferProgressBar.visibility = View.GONE
        transferProgressText.visibility = View.GONE
        transferFileNameText.visibility = View.GONE


        recyclerView.layoutManager = LinearLayoutManager(context)

        fileAdapter = FileAdapter(
            emptyList(),
            onItemClick = { file ->
                if (file.isDirectory) {
                    currentDirectory = file
                    listFiles(currentDirectory)
                } else {
                    openFile(file)
                }
            },
            onOptionsClick = { view, file ->
                showFileOptions(view, file)
            }
        )
        recyclerView.adapter = fileAdapter

        upButton.setOnClickListener {
            navigateUp()
        }

        listFiles(currentDirectory)

        return view
    }

    override fun onDestroy() {
        super.onDestroy()
        bluetoothService.stop() // Detener el servicio Bluetooth cuando el fragmento se destruye
    }

    private fun listFiles(directory: File) {
        currentPathTextView.text = directory.absolutePath

        upButton.visibility = if (directory.absolutePath != Environment.getExternalStorageDirectory().absolutePath) {
            View.VISIBLE
        } else {
            View.GONE
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val files = directory.listFiles()?.toList() ?: emptyList()
                val filteredFiles = files.filter { it.canRead() }
                val sortedFiles = filteredFiles.sortedWith(compareBy<File> { !it.isDirectory }
                    .thenBy { it.name.toLowerCase(Locale.ROOT) })

                withContext(Dispatchers.Main) {
                    fileAdapter.updateFiles(sortedFiles)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Error al listar archivos: ${e.message}", Toast.LENGTH_LONG).show()
                }
                e.printStackTrace()
            }
        }
    }

    private fun navigateUp() {
        if (currentDirectory.absolutePath != Environment.getExternalStorageDirectory().absolutePath) {
            val parent = currentDirectory.parentFile
            if (parent != null && parent.canRead()) {
                currentDirectory = parent
                listFiles(currentDirectory)
            } else {
                Toast.makeText(context, "No se puede acceder al directorio padre.", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, "Ya estás en la raíz del almacenamiento accesible.", Toast.LENGTH_SHORT).show()
        }
    }

    // --- Implementación de Operaciones de Archivos (existente) ---

    private fun openFile(file: File) {
        if (!file.exists()) {
            Toast.makeText(context, "El archivo no existe.", Toast.LENGTH_SHORT).show()
            return
        }

        val uri: Uri = FileProvider.getUriForFile(
            requireContext(),
            "${requireContext().packageName}.provider",
            file
        )

        val mimeType: String = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension)
            ?: "*/*"

        val intent = Intent(Intent.ACTION_VIEW)
        intent.setDataAndType(uri, mimeType)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "No hay aplicación para abrir este tipo de archivo.", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Error al abrir archivo: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showFileOptions(view: View, file: File) {
        val popup = PopupMenu(requireContext(), view)
        popup.menuInflater.inflate(R.menu.file_options_menu, popup.menu)

        val pasteMenuItem = popup.menu.findItem(R.id.action_paste)
        // Solo mostrar "Pegar" si hay algo en el portapapeles y el destino es un directorio
        pasteMenuItem.isVisible = fileToCopy != null && file.isDirectory

        // Deshabilitar "Enviar por Bluetooth" si no es un archivo (o si es un directorio y no queremos enviar carpetas recursivamente)
        val sendBluetoothItem = popup.menu.findItem(R.id.action_send_bluetooth)
        sendBluetoothItem.isVisible = file.isFile

        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_open -> {
                    openFile(file)
                    true
                }
                R.id.action_copy -> {
                    fileToCopy = file
                    Toast.makeText(context, "Archivo '${file.name}' copiado al portapapeles.", Toast.LENGTH_SHORT).show()
                    true
                }
                R.id.action_paste -> {
                    fileToCopy?.let { sourceFile ->
                        if (file.isDirectory) {
                            pasteFile(sourceFile, file)
                        } else {
                            Toast.makeText(context, "No se puede pegar en un archivo. Selecciona una carpeta.", Toast.LENGTH_SHORT).show()
                        }
                    }
                    true
                }
                R.id.action_move -> {
                    fileToCopy = file // Usamos la misma variable para "mover"
                    Toast.makeText(context, "Archivo '${file.name}' seleccionado para mover. Navega a la carpeta destino y presiona 'Pegar'.", Toast.LENGTH_LONG).show()
                    true
                }
                R.id.action_rename -> {
                    showRenameDialog(file)
                    true
                }
                R.id.action_delete -> {
                    showDeleteConfirmationDialog(file)
                    true
                }
                R.id.action_send_bluetooth -> {
                    showBluetoothSendDialog(file)
                    true
                }
                R.id.action_receive_bluetooth -> {
                    showBluetoothReceiveDialog()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    // --- Diálogos de Confirmación/Entrada (existente) ---

    private fun showDeleteConfirmationDialog(file: File) {
        AlertDialog.Builder(requireContext())
            .setTitle("Confirmar Eliminación")
            .setMessage("¿Estás seguro de que quieres eliminar '${file.name}'? Esta acción no se puede deshacer.")
            .setPositiveButton("Eliminar") { dialog, _ ->
                deleteFile(file)
                dialog.dismiss()
            }
            .setNegativeButton("Cancelar") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun showRenameDialog(file: File) {
        val builder = AlertDialog.Builder(requireContext())
        builder.setTitle("Renombrar")

        val input = EditText(context)
        input.setText(file.name)
        builder.setView(input)

        builder.setPositiveButton("Renombrar") { dialog, _ ->
            val newName = input.text.toString().trim()
            if (newName.isNotEmpty() && newName != file.name) {
                renameFile(file, newName)
            } else {
                Toast.makeText(context, "Nombre inválido o igual al actual.", Toast.LENGTH_SHORT).show()
            }
            dialog.dismiss()
        }
        builder.setNegativeButton("Cancelar") { dialog, _ -> dialog.cancel() }
        builder.show()
    }

    // --- Lógica de Operaciones de Archivos (existente) ---

    private fun deleteFile(file: File) {
        lifecycleScope.launch(Dispatchers.IO) {
            val success = try {
                if (file.isDirectory) {
                    file.deleteRecursively()
                } else {
                    file.delete()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }

            withContext(Dispatchers.Main) {
                if (success) {
                    Toast.makeText(context, "Eliminado: ${file.name}", Toast.LENGTH_SHORT).show()
                    listFiles(currentDirectory)
                } else {
                    Toast.makeText(context, "Error al eliminar: ${file.name}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun renameFile(file: File, newName: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val newFile = File(file.parent, newName)
            val success = try {
                file.renameTo(newFile)
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }

            withContext(Dispatchers.Main) {
                if (success) {
                    Toast.makeText(context, "Renombrado a: $newName", Toast.LENGTH_SHORT).show()
                    listFiles(currentDirectory)
                } else {
                    Toast.makeText(context, "Error al renombrar: ${file.name}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun pasteFile(sourceFile: File, destinationDirectory: File) {
        lifecycleScope.launch(Dispatchers.IO) {
            val destinationFile = File(destinationDirectory, sourceFile.name)
            val isCopy = fileToCopy?.absolutePath == sourceFile.absolutePath

            val success = try {
                if (sourceFile.isDirectory) {
                    sourceFile.copyRecursively(destinationFile, overwrite = true)
                } else {
                    copyFileContent(sourceFile, destinationFile)
                }
                if (!isCopy) {
                    sourceFile.deleteRecursively()
                }
                true
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }

            withContext(Dispatchers.Main) {
                if (success) {
                    Toast.makeText(context, "${if (isCopy) "Copiado" else "Movido"}: ${sourceFile.name} a ${destinationDirectory.name}", Toast.LENGTH_SHORT).show()
                    fileToCopy = null
                    listFiles(currentDirectory)
                } else {
                    Toast.makeText(context, "Error al ${if (isCopy) "copiar" else "mover"}: ${sourceFile.name}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun copyFileContent(source: File, destination: File) {
        var input: FileInputStream? = null
        var output: FileOutputStream? = null
        try {
            input = FileInputStream(source)
            output = FileOutputStream(destination)
            val buffer = ByteArray(1024)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                output.write(buffer, 0, read)
            }
            output.flush()
        } finally {
            input?.close()
            output?.close()
        }
    }

    // --- Lógica de Bluetooth ---

    @SuppressLint("MissingPermission")
    private fun showBluetoothSendDialog(file: File) {
        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Toast.makeText(context, "Bluetooth no disponible o no activado.", Toast.LENGTH_SHORT).show()
            return
        }

        val pairedDevices: Set<BluetoothDevice>? = bluetoothAdapter.bondedDevices
        val deviceList = mutableListOf<BluetoothDevice>()
        pairedDevices?.let { deviceList.addAll(it) }

        val devicesAdapter = BluetoothDeviceListAdapter(deviceList) { device ->
            // El usuario seleccionó un dispositivo emparejado, intentar conectar y enviar
            bluetoothService.connect(device)
            Toast.makeText(context, "Intentando conectar con ${device.name ?: device.address}", Toast.LENGTH_SHORT).show()
        }

        // Crear un diálogo para listar dispositivos emparejados
        val builder = AlertDialog.Builder(requireContext())
        builder.setTitle("Enviar archivo por Bluetooth a...")
        val recyclerView = RecyclerView(requireContext())
        recyclerView.layoutManager = LinearLayoutManager(context)
        recyclerView.adapter = devicesAdapter
        builder.setView(recyclerView)

        // Opción para buscar nuevos dispositivos
        builder.setPositiveButton("Buscar nuevos dispositivos") { dialog, _ ->
            showBluetoothDiscoveryDialog(file) // Mostrar un nuevo diálogo para la búsqueda
            dialog.dismiss()
        }
        builder.setNegativeButton("Cancelar") { dialog, _ -> dialog.dismiss() }

        val dialog = builder.create()
        dialog.show()
    }

    @SuppressLint("MissingPermission")
    private fun showBluetoothDiscoveryDialog(fileToSend: File?) {
        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Toast.makeText(context, "Bluetooth no disponible o no activado.", Toast.LENGTH_SHORT).show()
            return
        }

        val discoveredDevices = mutableListOf<BluetoothDevice>()
        val discoveryAdapter = BluetoothDeviceListAdapter(discoveredDevices) { device ->
            // El usuario seleccionó un dispositivo descubierto, intentar conectar y enviar
            bluetoothService.connect(device)
            Toast.makeText(context, "Intentando conectar con ${device.name ?: device.address}", Toast.LENGTH_SHORT).show()
            bluetoothService.cancelDiscovery() // Detener el descubrimiento
        }

        val builder = AlertDialog.Builder(requireContext())
        builder.setTitle("Buscando dispositivos...")
        val recyclerView = RecyclerView(requireContext())
        recyclerView.layoutManager = LinearLayoutManager(context)
        recyclerView.adapter = discoveryAdapter
        builder.setView(recyclerView)

        val dialog = builder.create()
        dialog.setOnDismissListener {
            bluetoothService.cancelDiscovery() // Asegurarse de cancelar el descubrimiento al cerrar el diálogo
        }
        dialog.show()

        // Iniciar el descubrimiento de dispositivos
        bluetoothService.startDiscovery()

        // Implementar el listener para actualizar la lista de dispositivos
        bluetoothService.setListener(object : BluetoothService.BluetoothServiceListener {
            override fun onDeviceFound(device: BluetoothDevice) {
                // Previene duplicados si la misma app ya está en la lista o un dispositivo emparejado aparece en la búsqueda
                if (!discoveredDevices.contains(device)) {
                    discoveredDevices.add(device)
                    discoveryAdapter.updateDevices(discoveredDevices)
                }
            }
            override fun onScanFinished() {
                Toast.makeText(context, "Búsqueda de dispositivos Bluetooth finalizada.", Toast.LENGTH_SHORT).show()
            }
            override fun onConnectionAttempt(deviceName: String) {
                Toast.makeText(context, "Conectando a: $deviceName", Toast.LENGTH_SHORT).show()
                dialog.dismiss() // Cerrar el diálogo de búsqueda al intentar conectar
            }
            override fun onConnected(deviceName: String) {
                Toast.makeText(context, "Conectado a: $deviceName", Toast.LENGTH_SHORT).show()
                fileToSend?.let { bluetoothService.sendFile(it) } // Enviar el archivo si hay uno
            }
            override fun onConnectionFailed(deviceName: String, error: String) {
                Toast.makeText(context, "Falló la conexión con $deviceName: $error", Toast.LENGTH_LONG).show()
            }
            override fun onFileTransferStarted(fileName: String) {
                Toast.makeText(context, "Iniciando transferencia de: $fileName", Toast.LENGTH_SHORT).show()
                transferProgressBar.visibility = View.VISIBLE
                transferProgressText.visibility = View.VISIBLE
                transferFileNameText.visibility = View.VISIBLE
                transferFileNameText.text = "Enviando: $fileName"
                transferProgressBar.progress = 0
            }
            override fun onFileTransferProgress(fileName: String, progress: Int) {
                transferProgressBar.progress = progress
                transferProgressText.text = "$progress%"
            }
            override fun onFileTransferComplete(fileName: String, success: Boolean) {
                transferProgressBar.visibility = View.GONE
                transferProgressText.visibility = View.GONE
                transferFileNameText.visibility = View.GONE
                if (success) {
                    Toast.makeText(context, "Transferencia de '$fileName' completada.", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Transferencia de '$fileName' fallida.", Toast.LENGTH_LONG).show()
                }
            }
            override fun onMessage(message: String) {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun showBluetoothReceiveDialog() {
        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Toast.makeText(context, "Bluetooth no disponible o no activado.", Toast.LENGTH_SHORT).show()
            return
        }

        // Asegurarse de que el dispositivo sea detectable para otros por un tiempo.
        @SuppressLint("MissingPermission")
        val discoverableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
            putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300) // 300 segundos = 5 minutos
        }
        startActivity(discoverableIntent)

        AlertDialog.Builder(requireContext())
            .setTitle("Esperando recibir archivos...")
            .setMessage("Asegúrate de que tu Bluetooth esté activado y visible. Esperando conexión entrante...")
            .setPositiveButton("Ok") { dialog, _ ->
                dialog.dismiss()
            }
            .setNegativeButton("Cancelar Recepción") { dialog, _ ->
                bluetoothService.stop() // Detener el hilo de aceptación
                dialog.dismiss()
                Toast.makeText(context, "Recepción cancelada.", Toast.LENGTH_SHORT).show()
            }
            .show()

        // Iniciar el hilo del servidor para escuchar conexiones
        bluetoothService.startAcceptingConnections()

        // Configurar listener para actualizar la UI con el progreso de recepción
        bluetoothService.setListener(this) // Reutilizamos el listener principal del fragmento
    }


    // --- Implementación de BluetoothService.BluetoothServiceListener ---

    override fun onDeviceFound(device: BluetoothDevice) {
        // Este callback solo se usa si el diálogo de búsqueda está activo con un listener específico
        // Para el diálogo de enviar a emparejados, no se usa aquí directamente.
    }

    override fun onScanFinished() {
        // Se puede usar para cerrar el diálogo de búsqueda si está abierto.
    }

    override fun onConnectionAttempt(deviceName: String) {
        Toast.makeText(context, "Conectando a: $deviceName", Toast.LENGTH_SHORT).show()
    }

    @SuppressLint("MissingPermission")
    override fun onConnected(deviceName: String) {
        Toast.makeText(context, "Conectado a: $deviceName", Toast.LENGTH_SHORT).show()
        // Después de conectar, podrías enviar el archivo si la acción fue "Enviar"
        // O simplemente esperar la recepción si la acción fue "Recibir"
    }

    override fun onConnectionFailed(deviceName: String, error: String) {
        Toast.makeText(context, "Falló la conexión con $deviceName: $error", Toast.LENGTH_LONG).show()
    }

    override fun onFileTransferStarted(fileName: String) {
        transferProgressBar.visibility = View.VISIBLE
        transferProgressText.visibility = View.VISIBLE
        transferFileNameText.visibility = View.VISIBLE
        transferFileNameText.text = "Transfiriendo: $fileName"
        transferProgressBar.progress = 0
    }

    override fun onFileTransferProgress(fileName: String, progress: Int) {
        transferProgressBar.progress = progress
        transferProgressText.text = "$progress%"
    }

    override fun onFileTransferComplete(fileName: String, success: Boolean) {
        transferProgressBar.visibility = View.GONE
        transferProgressText.visibility = View.GONE
        transferFileNameText.visibility = View.GONE
        if (success) {
            Toast.makeText(context, "Transferencia de '$fileName' completada.", Toast.LENGTH_SHORT).show()
            listFiles(currentDirectory) // Refrescar la lista si se recibió un archivo
        } else {
            Toast.makeText(context, "Transferencia de '$fileName' fallida.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onMessage(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}