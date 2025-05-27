package com.example.gestorarchivos

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val PERMISSION_REQUEST_CODE_STORAGE_LEGACY = 100
    private val REQUEST_CODE_MANAGE_STORAGE_PERMISSION = 200
    private val PERMISSION_REQUEST_CODE_BLUETOOTH = 300
    private val REQUEST_ENABLE_BT = 301

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        checkAllPermissions()
    }


    private fun checkAllPermissions() {
        // Primero, los permisos de almacenamiento
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                val uri = Uri.fromParts("package", packageName, null)
                intent.data = uri
                startActivityForResult(intent, REQUEST_CODE_MANAGE_STORAGE_PERMISSION)
                Toast.makeText(this, "Por favor, concede el permiso 'Acceso a todos los archivos'.", Toast.LENGTH_LONG).show()
                return // Salimos y esperamos el resultado
            }
        } else {
            val readPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
            val writePermission = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)

            if (readPermission != PackageManager.PERMISSION_GRANTED || writePermission != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    PERMISSION_REQUEST_CODE_STORAGE_LEGACY
                )
                return // Salimos y esperamos el resultado
            }
        }

        // Si los permisos de almacenamiento están OK, verificamos los de Bluetooth
        checkBluetoothPermissions()
    }

    private fun checkBluetoothPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { // Android 12 (API 31) o superior
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            }
        } else { // Android 11 (API 30) o inferior
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_ADMIN)
            }
            // Los permisos de ubicación también son necesarios para el escaneo en versiones antiguas
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), PERMISSION_REQUEST_CODE_BLUETOOTH)
        } else {
            // Todos los permisos están concedidos, podemos iniciar el gestor de archivos
            enableBluetoothIfNecessary()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            PERMISSION_REQUEST_CODE_STORAGE_LEGACY -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Permisos de almacenamiento antiguos concedidos.", Toast.LENGTH_SHORT).show()
                    checkBluetoothPermissions() // Ahora, verificar Bluetooth
                } else {
                    Toast.makeText(this, "Permisos de almacenamiento denegados. La aplicación no funcionará correctamente.", Toast.LENGTH_LONG).show()
                }
            }
            PERMISSION_REQUEST_CODE_BLUETOOTH -> {
                val allBluetoothPermissionsGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
                if (allBluetoothPermissionsGranted) {
                    Toast.makeText(this, "Permisos Bluetooth concedidos.", Toast.LENGTH_SHORT).show()
                    enableBluetoothIfNecessary()
                } else {
                    Toast.makeText(this, "Permisos Bluetooth denegados. La funcionalidad de Bluetooth no estará disponible.", Toast.LENGTH_LONG).show()
                    startFileManager() // Iniciar de todas formas, pero con Bluetooth deshabilitado
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_CODE_MANAGE_STORAGE_PERMISSION -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    if (Environment.isExternalStorageManager()) {
                        Toast.makeText(this, "Acceso a todos los archivos concedido.", Toast.LENGTH_SHORT).show()
                        checkBluetoothPermissions() // Ahora, verificar Bluetooth
                    } else {
                        Toast.makeText(this, "Permiso 'Acceso a todos los archivos' denegado. La aplicación no funcionará correctamente.", Toast.LENGTH_LONG).show()
                        // No iniciar el gestor de archivos sin este permiso crucial
                    }
                }
            }
            REQUEST_ENABLE_BT -> {
                if (resultCode == RESULT_OK) {
                    Toast.makeText(this, "Bluetooth activado.", Toast.LENGTH_SHORT).show()
                    startFileManager() // Bluetooth activado, iniciar gestor
                } else {
                    Toast.makeText(this, "Bluetooth no activado. La funcionalidad de Bluetooth no estará disponible.", Toast.LENGTH_LONG).show()
                    startFileManager() // Continuar sin Bluetooth
                }
            }
        }
    }

    private fun enableBluetoothIfNecessary() {
        val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Este dispositivo no soporta Bluetooth.", Toast.LENGTH_LONG).show()
            startFileManager()
        } else if (!bluetoothAdapter.isEnabled) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
        } else {
            // Bluetooth ya está activado y todos los permisos están concedidos
            startFileManager()
        }
    }

    private fun startFileManager() {
        // Cargar el FileManagerFragment en el FrameLayout
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, FileManagerFragment())
            .commit()
    }

    override fun onBackPressed() {
        val fragment = supportFragmentManager.findFragmentById(R.id.fragment_container)
        if (fragment is FileManagerFragment) {
            fragment.navigateUp() // Llama al método de navegación del fragment
        } else {
            super.onBackPressed() // Comportamiento por defecto si no es nuestro fragment
        }
    }
}