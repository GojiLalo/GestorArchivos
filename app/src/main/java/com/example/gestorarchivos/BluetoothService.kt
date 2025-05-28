package com.example.gestorarchivos

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive // Importar isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import android.os.Environment

val MY_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
const val APP_NAME = "MiGestorDeArchivos"

class BluetoothService(private val context: Context, private val uiHandler: Handler) {

    private val bluetoothAdapter: BluetoothAdapter? by lazy(LazyThreadSafetyMode.NONE) {
        BluetoothAdapter.getDefaultAdapter()
    }

    private var connectThread: ConnectThread? = null
    private var connectedThread: ConnectedThread? = null
    private var acceptThread: AcceptThread? = null

    // Usaremos un Job para controlar el ciclo de vida de las corrutinas en los hilos.
    private var serviceJob = Job()
    private val coroutineScope = CoroutineScope(Dispatchers.IO + serviceJob)

    interface BluetoothServiceListener {
        fun onDeviceFound(device: BluetoothDevice)
        fun onScanFinished()
        fun onConnectionAttempt(deviceName: String)
        fun onConnected(deviceName: String)
        fun onConnectionFailed(deviceName: String, error: String)
        fun onFileTransferStarted(fileName: String)
        fun onFileTransferProgress(fileName: String, progress: Int)
        fun onFileTransferComplete(fileName: String, success: Boolean)
        fun onMessage(message: String)
    }

    private var listener: BluetoothServiceListener? = null

    fun setListener(listener: BluetoothServiceListener) {
        this.listener = listener
    }

    @SuppressLint("MissingPermission")
    fun startDiscovery() {
        if (bluetoothAdapter == null) {
            listener?.onMessage("Bluetooth no soportado en este dispositivo.")
            return
        }
        if (bluetoothAdapter?.isDiscovering == true) {
            bluetoothAdapter?.cancelDiscovery()
        }
        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        context.registerReceiver(discoveryReceiver, filter)

        val endFilter = IntentFilter(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        context.registerReceiver(discoveryReceiver, endFilter)

        bluetoothAdapter?.startDiscovery()
        listener?.onMessage("Buscando dispositivos...")
    }

    @SuppressLint("MissingPermission")
    fun cancelDiscovery() {
        bluetoothAdapter?.cancelDiscovery()
        try {
            context.unregisterReceiver(discoveryReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver no estaba registrado, ignorar
        }
    }

    private val discoveryReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            val action: String? = intent.action
            when(action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    device?.let {
                        listener?.onDeviceFound(it)
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    listener?.onScanFinished()
                    try {
                        context.unregisterReceiver(this) // Desregistrar el receiver una vez que termina
                    } catch (e: IllegalArgumentException) {
                        // Receiver ya fue desregistrado
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        listener?.onConnectionAttempt("Conectando a ${device.name ?: device.address}...")
        connectThread?.cancel()
        connectThread = ConnectThread(device)
        connectThread?.start()
    }

    @SuppressLint("MissingPermission")
    fun startAcceptingConnections() {
        acceptThread?.cancel()
        acceptThread = AcceptThread()
        acceptThread?.start()
        listener?.onMessage("Esperando conexiones entrantes...")
    }

    fun sendFile(file: File) {
        if (connectedThread != null) {
            connectedThread?.sendFile(file)
        } else {
            listener?.onMessage("No hay una conexión Bluetooth activa para enviar el archivo.")
        }
    }

    fun stop() {
        serviceJob.cancel() // Cancelar el Job principal, esto cancelará las corrutinas
        connectThread?.cancel()
        connectedThread?.cancel()
        acceptThread?.cancel()
        connectThread = null
        connectedThread = null
        acceptThread = null
        try {
            context.unregisterReceiver(discoveryReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver no estaba registrado, ignorar
        }
    }

    private inner class ConnectThread(private val device: BluetoothDevice) : Thread() {
        private val mmSocket: BluetoothSocket? by lazy(LazyThreadSafetyMode.NONE) {
            @SuppressLint("MissingPermission")
            device.createRfcommSocketToServiceRecord(MY_UUID)
        }

        override fun run() {
            @SuppressLint("MissingPermission")
            bluetoothAdapter?.cancelDiscovery()

            mmSocket?.let { socket ->
                try {
                    socket.connect()
                    manageConnectedSocket(socket, device)
                    uiHandler.post { listener?.onConnected(device.name ?: device.address) }
                } catch (e: IOException) {
                    uiHandler.post {
                        listener?.onConnectionFailed(device.name ?: device.address, e.message ?: "Error de conexión.")
                    }
                    try {
                        socket.close()
                    } catch (closeException: IOException) {
                        uiHandler.post {
                            listener?.onMessage("No se pudo cerrar el socket de cliente: ${closeException.message}")
                        }
                    }
                }
            }
        }

        fun cancel() {
            try {
                mmSocket?.close()
            } catch (e: IOException) {
                uiHandler.post {
                    listener?.onMessage("No se pudo cerrar el socket de cliente: ${e.message}")
                }
            }
        }
    }

    private inner class AcceptThread : Thread() {
        private val mmServerSocket: BluetoothServerSocket? by lazy(LazyThreadSafetyMode.NONE) {
            @SuppressLint("MissingPermission")
            bluetoothAdapter?.listenUsingRfcommWithServiceRecord(APP_NAME, MY_UUID)
        }

        private var running = true // Bandera para controlar el bucle

        override fun run() {
            var socket: BluetoothSocket? = null
            // Escuchar mientras el hilo de la coroutine esté activo Y la bandera sea true
            while (running && coroutineScope.isActive) {
                try {
                    socket = mmServerSocket?.accept()
                } catch (e: IOException) {
                    // Si el server socket se cierra (e.g., por cancelación), se lanzará una excepción
                    // Esto indica que el hilo debe terminar.
                    uiHandler.post {
                        listener?.onMessage("Socket del servidor falló o fue cerrado: ${e.message}")
                    }
                    running = false // Salir del bucle
                }

                socket?.also {
                    manageConnectedSocket(it, it.remoteDevice)
                    try {
                        mmServerSocket?.close() // Cerrar el server socket una vez que se acepta la conexión
                    } catch (e: IOException) {
                        uiHandler.post {
                            listener?.onMessage("Error al cerrar server socket después de aceptar: ${e.message}")
                        }
                    }
                    running = false // Salir del bucle después de una conexión exitosa
                }
            }
        }

        fun cancel() {
            running = false // Establecer la bandera a false
            try {
                mmServerSocket?.close() // Cerrar el socket para interrumpir el accept() bloqueante
            } catch (e: IOException) {
                uiHandler.post {
                    listener?.onMessage("No se pudo cerrar el socket del servidor: ${e.message}")
                }
            }
        }
    }

    private fun manageConnectedSocket(socket: BluetoothSocket, device: BluetoothDevice) {
        connectThread?.cancel()
        connectThread = null

        acceptThread?.cancel() // Asegurarse de que el AcceptThread se detenga
        acceptThread = null

        connectedThread = ConnectedThread(socket)
        connectedThread?.start()
    }

    private inner class ConnectedThread(private val mmSocket: BluetoothSocket) : Thread() {
        private val mmInStream: InputStream = mmSocket.inputStream
        private val mmOutStream: OutputStream = mmSocket.outputStream
        private val buffer = ByteArray(1024)

        private var running = true // Bandera para controlar el bucle de lectura

        // En ConnectedThread
        override fun run() {
            // Escuchar el InputStream para mensajes entrantes mientras la bandera sea true y la corrutina activa
            while (running && coroutineScope.isActive) {
                try {
                    // Asumimos que los mensajes normales son cortos o terminados por un newline.
                    // Para la transferencia de archivos, el primer mensaje SIEMPRE será el encabezado.
                    // Puedes usar una estrategia para diferenciar mensajes de texto normales de encabezados de archivo.
                    // Por ejemplo, un prefijo fijo para encabezados, o que todos los mensajes de texto también terminen con newline.
                    // Para simplificar aquí, asumimos que "FILE_NAME:" es el primer mensaje.

                    // Intenta leer el encabezado del archivo
                    val message = readHeaderMessage() // Lee hasta el newline

                    if (message.startsWith("FILE_NAME:")) {
                        // Si es un encabezado de archivo, llama a receiveFile con el encabezado completo
                        receiveFile(message)
                    } else {
                        // Si no es un encabezado de archivo, es un mensaje normal
                        uiHandler.post { listener?.onMessage("Mensaje recibido: $message") }
                    }
                } catch (e: IOException) {
                    // Este es el error "read failed, socket might closed" que verías aquí.
                    uiHandler.post {
                        listener?.onMessage("Error de conexión al leer: ${e.message}. Conexión cerrada.")
                    }
                    running = false // Salir del bucle si hay un error en la lectura principal
                } catch (e: InterruptedException) {
                    uiHandler.post { listener?.onMessage("Lectura de hilo interrumpida: ${e.message}") }
                    running = false // Salir del bucle si el hilo es interrumpido
                } finally {
                    // Asegurar que el socket se cierra si el bucle termina por cualquier razón (error o cancelación)
                    cancel()
                }
            }
        }

        // En ConnectedThread
        @SuppressLint("MissingPermission")
        fun sendFile(file: File) {
            coroutineScope.launch {
                withContext(Dispatchers.Main) {
                    listener?.onFileTransferStarted(file.name)
                }

                var fileInputStream: FileInputStream? = null
                try {
                    val fileName = file.name
                    val fileSize = file.length() // Obtener el tamaño del archivo

                    // **PROTOCOLO:** Enviar nombre y tamaño (por ejemplo, separados por '|')
                    val headerMessage = "FILE_NAME:$fileName|FILE_SIZE:$fileSize"
                    mmOutStream.write(headerMessage.toByteArray())
                    mmOutStream.flush()
                    // No es necesario Thread.sleep(500) aquí, ya que el receptor leerá el tamaño y sabrá qué hacer.

                    fileInputStream = FileInputStream(file)
                    var bytesRead: Int
                    var totalBytesSent: Long = 0
                    val bufferSize = 4096 // Un buffer más grande puede mejorar el rendimiento
                    val sendBuffer = ByteArray(bufferSize) // Buffer para el envío

                    while (fileInputStream.read(sendBuffer).also { bytesRead = it } != -1 && coroutineScope.isActive) {
                        mmOutStream.write(sendBuffer, 0, bytesRead)
                        totalBytesSent += bytesRead
                        val progress = ((totalBytesSent * 100) / fileSize).toInt()
                        withContext(Dispatchers.Main) {
                            listener?.onFileTransferProgress(file.name, progress)
                        }
                    }
                    mmOutStream.flush() // Asegurar que todos los bytes son enviados

                    // Opcional: Podrías enviar un mensaje de "FIN_TRANSFERENCIA" si tu protocolo lo requiere
                    // mmOutStream.write("END_TRANSFER".toByteArray())
                    // mmOutStream.flush()

                    withContext(Dispatchers.Main) {
                        listener?.onFileTransferComplete(file.name, true)
                        listener?.onMessage("Archivo '$fileName' enviado exitosamente. Total bytes: $totalBytesSent")
                    }
                } catch (e: IOException) {
                    withContext(Dispatchers.Main) {
                        listener?.onFileTransferComplete(file.name, false)
                        listener?.onMessage("Error al enviar archivo: ${e.message}")
                    }
                } catch (e: InterruptedException) {
                    withContext(Dispatchers.Main) {
                        listener?.onFileTransferComplete(file.name, false)
                        listener?.onMessage("Envío de archivo interrumpido: ${e.message}")
                    }
                } finally {
                    try {
                        fileInputStream?.close()
                    } catch (e: IOException) {
                        e.printStackTrace()
                    }
                }
            }
        }

        // En ConnectedThread

        // Nuevo método para leer un mensaje de encabezado completo (nombre + tamaño)
// Esto es crucial porque `read()` puede no devolver el mensaje completo de una vez.
        private fun readHeaderMessage(): String {
            val stringBuilder = StringBuilder()
            var byte: Int
            // Leer byte por byte hasta encontrar un delimitador conocido o un tamaño máximo de encabezado
            // Por simplicidad, asumimos que el encabezado es relativamente corto y el delimitador es '\n' o similar.
            // Un protocolo más robusto enviaría primero la longitud del encabezado.
            while (mmInStream.read().also { byte = it } != -1) {
                val char = byte.toChar()
                if (char == '\n') break // Usar un newline como terminador de encabezado
                stringBuilder.append(char)
                if (stringBuilder.length > 2048) { // Evitar leer indefinidamente si no hay '\n'
                    throw IOException("Header message too long or missing terminator")
                }
            }
            return stringBuilder.toString()
        }

        private fun receiveFile(headerMessage: String) {
            coroutineScope.launch {
                var fileName: String = "unknown_file"
                var fileSize: Long = 0L

                try {
                    // Parsear el encabezado: "FILE_NAME:nombre.txt|FILE_SIZE:12345"
                    val parts = headerMessage.split("|")
                    val namePart = parts.find { it.startsWith("FILE_NAME:") }
                    val sizePart = parts.find { it.startsWith("FILE_SIZE:") }

                    fileName = namePart?.substringAfter("FILE_NAME:") ?: "unknown_file"
                    fileSize = sizePart?.substringAfter("FILE_SIZE:")?.toLongOrNull() ?: 0L

                    if (fileSize <= 0) {
                        withContext(Dispatchers.Main) {
                            listener?.onMessage("Error al recibir archivo '$fileName': Tamaño de archivo inválido o no especificado ($fileSize).")
                            listener?.onFileTransferComplete(fileName, false)
                        }
                        return@launch // Salir de la coroutine
                    }

                    withContext(Dispatchers.Main) {
                        listener?.onFileTransferStarted(fileName)
                    }

                    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    if (!downloadsDir.exists()) downloadsDir.mkdirs()
                    val receivedFile = File(downloadsDir, fileName)

                    var fileOutputStream: FileOutputStream? = null
                    try {
                        fileOutputStream = FileOutputStream(receivedFile)
                        var bytesRead: Int
                        var totalBytesReceived: Long = 0
                        val bufferForReceive = ByteArray(4096) // Un buffer más grande

                        // **PROTOCOLO:** Leer exactamente el fileSize
                        while (totalBytesReceived < fileSize && coroutineScope.isActive) {
                            val bytesToRead = (fileSize - totalBytesReceived).toInt().coerceAtMost(bufferForReceive.size)
                            bytesRead = mmInStream.read(bufferForReceive, 0, bytesToRead)

                            if (bytesRead == -1) { // Fin del stream inesperado (socket cerrado por el otro lado)
                                throw IOException("Conexión perdida durante la recepción del archivo.")
                            }
                            if (bytesRead > 0) {
                                fileOutputStream.write(bufferForReceive, 0, bytesRead)
                                totalBytesReceived += bytesRead
                                val progress = ((totalBytesReceived * 100) / fileSize).toInt()
                                withContext(Dispatchers.Main) {
                                    listener?.onFileTransferProgress(fileName, progress)
                                }
                            }
                        }

                        fileOutputStream.flush()
                        withContext(Dispatchers.Main) {
                            listener?.onFileTransferComplete(fileName, true)
                            listener?.onMessage("Archivo '$fileName' recibido en ${receivedFile.absolutePath}. Total bytes: $totalBytesReceived")
                        }
                    } catch (e: IOException) {
                        withContext(Dispatchers.Main) {
                            listener?.onFileTransferComplete(fileName, false)
                            listener?.onMessage("Error al recibir archivo: ${e.message}")
                        }
                    } finally {
                        try {
                            fileOutputStream?.close()
                        } catch (e: IOException) {
                            e.printStackTrace()
                        }
                    }
                } catch (e: Exception) { // Capturar errores durante el parsing del encabezado o al iniciar la recepción
                    withContext(Dispatchers.Main) {
                        listener?.onFileTransferComplete(fileName, false)
                        listener?.onMessage("Error fatal al preparar la recepción del archivo '$fileName': ${e.message}")
                    }
                }
            }
        }


        fun cancel() {
            running = false // Establecer la bandera a false
            try {
                mmSocket.close()
            } catch (e: IOException) {
                uiHandler.post { listener?.onMessage("No se pudo cerrar el socket de conexión: ${e.message}") }
            }
        }
    }
}