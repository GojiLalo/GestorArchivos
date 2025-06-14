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
import android.os.Environment
import android.os.Handler
import android.util.Log
import kotlinx.coroutines.*
import java.io.*
import java.util.*
import android.net.Uri

val MY_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
const val APP_NAME = "MiGestorDeArchivos"

class BluetoothService(private val context: Context, private val uiHandler: Handler) {

    private val bluetoothAdapter: BluetoothAdapter? by lazy(LazyThreadSafetyMode.NONE) {
        BluetoothAdapter.getDefaultAdapter()
    }

    private var connectThread: ConnectThread? = null
    private var connectedThread: ConnectedThread? = null
    private var acceptThread: AcceptThread? = null

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
        } catch (e: IllegalArgumentException) {}
    }

    private val discoveryReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            val action: String? = intent.action
            when(action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    device?.let { listener?.onDeviceFound(it) }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    listener?.onScanFinished()
                    try { context.unregisterReceiver(this) } catch (_: IllegalArgumentException) {}
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
        connectedThread?.sendFile(file) ?: listener?.onMessage("No hay conexión Bluetooth activa.")
    }

    fun stop() {
        serviceJob.cancel()
        connectThread?.cancel()
        connectedThread?.cancel()
        acceptThread?.cancel()
        connectThread = null
        connectedThread = null
        acceptThread = null
        try { context.unregisterReceiver(discoveryReceiver) } catch (_: IllegalArgumentException) {}
    }

    private fun manageConnectedSocket(socket: BluetoothSocket, device: BluetoothDevice, isSender: Boolean) {
        connectedThread?.cancel()
        connectedThread = ConnectedThread(socket, isSender)
        connectedThread?.start()
        uiHandler.post { listener?.onConnected(device.name ?: device.address) }
    }

    private inner class ConnectThread(private val device: BluetoothDevice) : Thread() {
        private val mmSocket: BluetoothSocket? by lazy(LazyThreadSafetyMode.NONE) {
            device.createRfcommSocketToServiceRecord(MY_UUID)
        }

        override fun run() {
            bluetoothAdapter?.cancelDiscovery()
            mmSocket?.let { socket ->
                try {
                    socket.connect()
                    manageConnectedSocket(socket, device, true)
                } catch (e: IOException) {
                    uiHandler.post {
                        listener?.onConnectionFailed(device.name ?: device.address, e.message ?: "Error de conexión.")
                    }
                    try { socket.close() } catch (closeException: IOException) {}
                }
            }
        }

        fun cancel() { try { mmSocket?.close() } catch (_: IOException) {} }
    }

    private inner class AcceptThread : Thread() {
        private val mmServerSocket: BluetoothServerSocket? = bluetoothAdapter?.listenUsingRfcommWithServiceRecord(APP_NAME, MY_UUID)
        private var running = true

        override fun run() {
            var socket: BluetoothSocket?
            while (running && coroutineScope.isActive) {
                try {
                    socket = mmServerSocket?.accept()
                    socket?.also {
                        manageConnectedSocket(it, it.remoteDevice, false)
                        mmServerSocket?.close()
                        running = false
                    }
                } catch (e: IOException) {
                    uiHandler.post { listener?.onMessage("Error en socket del servidor: ${e.message}") }
                    running = false
                }
            }
        }

        fun cancel() { running = false; try { mmServerSocket?.close() } catch (_: IOException) {} }
    }

    inner class ConnectedThread(private val bluetoothSocket: BluetoothSocket, private val isSender: Boolean) : Thread() {

        private val mmInStream: InputStream = bluetoothSocket.inputStream
        private val mmOutStream: OutputStream = bluetoothSocket.outputStream

        override fun run() {
            try {
                if (isSender) sendFile() else receiveFile()
            } catch (e: IOException) {
                Log.e("BluetoothError", "Error en conexión Bluetooth", e)
            } finally {
                try { bluetoothSocket.close() } catch (_: IOException) {}
            }
        }

        fun sendFile(file: File = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "prueba.txt")) {
            try {
                if (!file.exists()) return
                val header = "FILE_NAME:${file.name}|FILE_SIZE:${file.length()}\n"
                mmOutStream.write(header.toByteArray())
                mmOutStream.flush()
                Thread.sleep(100)

                val buffer = ByteArray(1024)
                var bytesRead: Int
                val input = FileInputStream(file)
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    mmOutStream.write(buffer, 0, bytesRead)
                }
                input.close()
                mmOutStream.flush()
                Log.d("BluetoothSend", "Archivo enviado correctamente")
            } catch (e: IOException) {
                Log.e("BluetoothSend", "Error al enviar archivo", e)
            }
        }

        private fun receiveFile() {
            try {
                val (fileName, fileSize) = readHeaderMessage()
                val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName)
                val output = FileOutputStream(file)

                val buffer = ByteArray(1024)
                var bytesRead: Int
                var totalBytesRead = 0L
                while (totalBytesRead < fileSize) {
                    bytesRead = mmInStream.read(buffer)
                    if (bytesRead == -1) break
                    output.write(buffer, 0, bytesRead)
                    totalBytesRead += bytesRead
                }
                output.flush()
                output.close()
                Log.d("BluetoothReceive", "Archivo recibido en: ${file.absolutePath}")
            } catch (e: IOException) {
                Log.e("BluetoothReceive", "Error al recibir archivo", e)
            }
        }

        private fun readHeaderMessage(): Pair<String, Long> {
            val reader = BufferedReader(InputStreamReader(mmInStream))
            val header = reader.readLine()
            if (header == null || !header.contains("FILE_NAME") || !header.contains("FILE_SIZE"))
                throw IOException("Encabezado inválido: $header")
            val name = header.substringAfter("FILE_NAME:").substringBefore("|")
            val size = header.substringAfter("FILE_SIZE:").trim().toLong()
            return Pair(name, size)
        }

        fun cancel() { try { bluetoothSocket.close() } catch (_: IOException) {} }
    }
}
