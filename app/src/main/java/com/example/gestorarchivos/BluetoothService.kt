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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

// UUID para el perfil SPP (Serial Port Profile), comúnmente usado para transferencias genéricas.
// Si deseas usar OPP (Object Push Profile) nativo de Android, la implementación es más compleja
// y a menudo implica el uso de la API de ContentResolver para interactuar con el sistema de intercambio de archivos.
// Para este ejemplo, usaremos SPP para una implementación más directa de socket.
val MY_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB") // UUID para SPP
const val APP_NAME = "MiGestorDeArchivos"

class BluetoothService(private val context: Context, private val uiHandler: Handler) {

    private val bluetoothAdapter: BluetoothAdapter? by lazy(LazyThreadSafetyMode.NONE) {
        BluetoothAdapter.getDefaultAdapter()
    }

    private var connectThread: ConnectThread? = null
    private var connectedThread: ConnectedThread? = null
    private var acceptThread: AcceptThread? = null // Hilo para el servidor

    private var job: Job = Job()
    private val coroutineScope = CoroutineScope(Dispatchers.IO + job)

    // Listener para eventos de Bluetooth
    interface BluetoothServiceListener {
        fun onDeviceFound(device: BluetoothDevice)
        fun onScanFinished()
        fun onConnectionAttempt(deviceName: String)
        fun onConnected(deviceName: String)
        fun onConnectionFailed(deviceName: String, error: String)
        fun onFileTransferStarted(fileName: String)
        fun onFileTransferProgress(fileName: String, progress: Int)
        fun onFileTransferComplete(fileName: String, success: Boolean)
        fun onMessage(message: String) // Para mensajes de estado general
    }

    private var listener: BluetoothServiceListener? = null

    fun setListener(listener: BluetoothServiceListener) {
        this.listener = listener
    }

    @SuppressLint("MissingPermission") // Los permisos se manejan en MainActivity
    fun startDiscovery() {
        if (bluetoothAdapter == null) {
            listener?.onMessage("Bluetooth no soportado en este dispositivo.")
            return
        }
        if (bluetoothAdapter?.isDiscovering == true) {
            bluetoothAdapter?.cancelDiscovery()
        }
        // Registra un BroadcastReceiver para escuchar dispositivos encontrados
        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        context.registerReceiver(discoveryReceiver, filter)

        // Registra un BroadcastReceiver para escuchar cuando la búsqueda termina
        val endFilter = IntentFilter(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        context.registerReceiver(discoveryReceiver, endFilter)

        bluetoothAdapter?.startDiscovery()
        listener?.onMessage("Buscando dispositivos...")
    }

    @SuppressLint("MissingPermission")
    fun cancelDiscovery() {
        bluetoothAdapter?.cancelDiscovery()
        context.unregisterReceiver(discoveryReceiver) // Asegúrate de desregistrar
    }

    // BroadcastReceiver para manejar dispositivos encontrados
    private val discoveryReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            val action: String? = intent.action
            when(action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    device?.let {
                        // Filtra dispositivos ya emparejados para no listarlos dos veces (opcional)
                        // O simplemente lista todos
                        listener?.onDeviceFound(it)
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    listener?.onScanFinished()
                    context.unregisterReceiver(this) // Desregistrar el receiver una vez que termina
                }
            }
        }
    }

    /**
     * Inicia el hilo de conexión para conectar a un dispositivo Bluetooth.
     */
    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        listener?.onConnectionAttempt("Conectando a ${device.name ?: device.address}...")
        // Cancelar cualquier intento de conexión existente
        connectThread?.cancel()
        connectThread = ConnectThread(device)
        connectThread?.start()
    }

    /**
     * Inicia el hilo del servidor para escuchar conexiones entrantes.
     */
    @SuppressLint("MissingPermission")
    fun startAcceptingConnections() {
        // Si ya hay un hilo de aceptación, cancelarlo
        acceptThread?.cancel()
        acceptThread = AcceptThread()
        acceptThread?.start()
        listener?.onMessage("Esperando conexiones entrantes...")
    }

    /**
     * Envía un archivo a través de la conexión Bluetooth activa.
     */
    fun sendFile(file: File) {
        if (connectedThread != null) {
            connectedThread?.sendFile(file)
        } else {
            listener?.onMessage("No hay una conexión Bluetooth activa para enviar el archivo.")
        }
    }

    /**
     * Detiene todos los hilos y libera los recursos.
     */
    fun stop() {
        connectThread?.cancel()
        connectThread = null
        connectedThread?.cancel()
        connectedThread = null
        acceptThread?.cancel()
        acceptThread = null
        coroutineScope.cancel() // Cancelar el ámbito de las corrutinas
        try {
            context.unregisterReceiver(discoveryReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver no estaba registrado, ignorar
        }
    }

    /**
     * Hilo para conectar a un dispositivo Bluetooth como cliente.
     */
    private inner class ConnectThread(private val device: BluetoothDevice) : Thread() {
        private val mmSocket: BluetoothSocket? by lazy(LazyThreadSafetyMode.NONE) {
            // Intentar conectar a través de SPP (Serial Port Profile)
            @SuppressLint("MissingPermission")
            device.createRfcommSocketToServiceRecord(MY_UUID)
        }

        override fun run() {
            // Siempre cancelar el descubrimiento porque ralentizará la conexión
            @SuppressLint("MissingPermission")
            bluetoothAdapter?.cancelDiscovery()

            mmSocket?.let { socket ->
                try {
                    // Conectarse al dispositivo a través del socket. Bloquea hasta que tiene éxito o lanza una excepción.
                    socket.connect()
                    manageConnectedSocket(socket, device)
                    listener?.onConnected(device.name ?: device.address)
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

    /**
     * Hilo para aceptar conexiones entrantes como servidor.
     */
    private inner class AcceptThread : Thread() {
        private val mmServerSocket: BluetoothServerSocket? by lazy(LazyThreadSafetyMode.NONE) {
            @SuppressLint("MissingPermission")
            bluetoothAdapter?.listenUsingRfcommWithServiceRecord(APP_NAME, MY_UUID)
        }

        override fun run() {
            var socket: BluetoothSocket? = null
            // Mantenerse escuchando hasta que se obtenga una conexión o se cancele.
            while (true) {
                try {
                    socket = mmServerSocket?.accept() // Bloquea hasta que se obtiene una conexión
                } catch (e: IOException) {
                    uiHandler.post {
                        listener?.onMessage("Socket del servidor falló: ${e.message}")
                    }
                    break
                }

                socket?.also {
                    manageConnectedSocket(it, it.remoteDevice)
                    mmServerSocket?.close() // Cerrar el server socket una vez que se acepta la conexión
                    break
                }
            }
        }

        fun cancel() {
            try {
                mmServerSocket?.close()
            } catch (e: IOException) {
                uiHandler.post {
                    listener?.onMessage("No se pudo cerrar el socket del servidor: ${e.message}")
                }
            }
        }
    }

    /**
     * Inicia el ConnectedThread para manejar la transmisión de datos.
     */
    private fun manageConnectedSocket(socket: BluetoothSocket, device: BluetoothDevice) {
        // Cancelar el hilo de conexión porque ya hemos conectado
        connectThread?.cancel()
        connectThread = null

        // Cancelar el hilo de aceptación porque solo queremos una conexión a la vez
        acceptThread?.cancel()
        acceptThread = null

        connectedThread = ConnectedThread(socket)
        connectedThread?.start()
    }

    /**
     * Hilo para manejar la conexión y la transferencia de datos.
     */
    private inner class ConnectedThread(private val mmSocket: BluetoothSocket) : Thread() {
        private val mmInStream: InputStream = mmSocket.inputStream
        private val mmOutStream: OutputStream = mmSocket.outputStream
        private val buffer = ByteArray(1024)

        override fun run() {
            // Escuchar el InputStream para mensajes entrantes
            while (true) {
                try {
                    val bytes = mmInStream.read(buffer)
                    val message = String(buffer, 0, bytes)

                    // Un simple protocolo: si el mensaje comienza con "FILE_NAME:", entonces es el nombre del archivo
                    // Esto es muy básico, un protocolo real debería ser más robusto (tamaño, CRC, etc.)
                    if (message.startsWith("FILE_NAME:")) {
                        val fileName = message.substringAfter("FILE_NAME:")
                        receiveFile(fileName)
                    } else {
                        uiHandler.post { listener?.onMessage("Mensaje recibido: $message") }
                    }
                } catch (e: IOException) {
                    uiHandler.post { listener?.onMessage("Dispositivo desconectado: ${e.message}") }
                    break
                }
            }
        }

        @SuppressLint("MissingPermission")
        fun sendFile(file: File) {
            coroutineScope.launch {
                withContext(Dispatchers.Main) {
                    listener?.onFileTransferStarted(file.name)
                }

                var fileInputStream: FileInputStream? = null
                try {
                    // Enviar el nombre del archivo primero (protocolo simple)
                    val fileNameMessage = "FILE_NAME:${file.name}"
                    mmOutStream.write(fileNameMessage.toByteArray())
                    mmOutStream.flush()
                    sleep(500) // Pequeña pausa para asegurar que el receptor procesa el nombre

                    fileInputStream = FileInputStream(file)
                    var bytesRead: Int
                    var totalBytesSent: Long = 0
                    val totalSize = file.length()

                    while (fileInputStream.read(buffer).also { bytesRead = it } != -1) {
                        mmOutStream.write(buffer, 0, bytesRead)
                        totalBytesSent += bytesRead
                        val progress = ((totalBytesSent * 100) / totalSize).toInt()
                        withContext(Dispatchers.Main) {
                            listener?.onFileTransferProgress(file.name, progress)
                        }
                    }
                    mmOutStream.flush()
                    withContext(Dispatchers.Main) {
                        listener?.onFileTransferComplete(file.name, true)
                    }
                } catch (e: IOException) {
                    withContext(Dispatchers.Main) {
                        listener?.onFileTransferComplete(file.name, false)
                        listener?.onMessage("Error al enviar archivo: ${e.message}")
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

        private fun receiveFile(fileName: String) {
            coroutineScope.launch {
                withContext(Dispatchers.Main) {
                    listener?.onFileTransferStarted(fileName)
                }

                var fileOutputStream: FileOutputStream? = null
                try {
                    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    if (!downloadsDir.exists()) downloadsDir.mkdirs() // Asegurarse de que el directorio existe
                    val receivedFile = File(downloadsDir, fileName)

                    fileOutputStream = FileOutputStream(receivedFile)
                    var bytesRead: Int
                    var totalBytesReceived: Long = 0
                    val startTime = System.currentTimeMillis()

                    // Un mecanismo para saber cuándo parar de leer. Esto es muy simplificado.
                    // En un protocolo real, el emisor enviaría el tamaño total del archivo primero.
                    // Aquí, simplemente esperamos a que no haya más datos o un timeout.
                    while (mmInStream.read(buffer).also { bytesRead = it } != -1) {
                        fileOutputStream.write(buffer, 0, bytesRead)
                        totalBytesReceived += bytesRead
                        // No podemos calcular un progreso fiable sin el tamaño total del archivo del emisor
                        // Para un progreso real, el emisor DEBE enviar el tamaño del archivo primero
                        withContext(Dispatchers.Main) {
                            listener?.onFileTransferProgress(fileName, 0) // No hay progreso real sin tamaño total
                        }
                        // Simple timeout para evitar que siga leyendo indefinidamente
                        if (System.currentTimeMillis() - startTime > 10000 && bytesRead == 0) { // Si no se lee nada en 10s
                            break
                        }
                    }
                    fileOutputStream.flush()
                    withContext(Dispatchers.Main) {
                        listener?.onFileTransferComplete(fileName, true)
                        listener?.onMessage("Archivo '$fileName' recibido en ${receivedFile.absolutePath}")
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
            }
        }

        fun cancel() {
            try {
                mmSocket.close()
            } catch (e: IOException) {
                uiHandler.post { listener?.onMessage("No se pudo cerrar el socket de conexión: ${e.message}") }
            }
        }
    }
}