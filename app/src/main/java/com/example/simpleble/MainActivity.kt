package com.example.simpleble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.*
import android.content.pm.PackageManager
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.View
import android.widget.*
import splitties.toast.toast
import java.nio.charset.StandardCharsets
import java.util.*

class MainActivity : AppCompatActivity() {

    private val TAG = "MainActivity"

    private val btnDiscoverDevices : Button by lazy{ findViewById(R.id.buttonDiscoverDevices) }
    private val listview: ListView by lazy { findViewById(R.id.listview) }
    private val tvSelectedDeviceTitle: TextView by lazy{ findViewById(R.id.tvSelectedDevice1) }
    private val tvSelectedDevice: TextView by lazy{ findViewById(R.id.tvSelectedDevice2) }
    private val btnConnect: Button by lazy{ findViewById(R.id.buttonConnect) }
    private val tvConnected: TextView by lazy{ findViewById(R.id.tvConnect) }
    private val btnData: Button by lazy{ findViewById(R.id.buttonData) }
    private val tvData: TextView by lazy{ findViewById(R.id.tvData) }
    private val btnLED: Button by lazy{ findViewById(R.id.buttonLED) }
    private val tvLED: TextView by lazy{ findViewById(R.id.tvLED) }

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var scanner: BluetoothLeScanner
    private var bluetoothLeService: BluetoothLeService? = null
    private lateinit var gattCharacteristic: BluetoothGattCharacteristic

    private lateinit var selectedDevice: String
    private lateinit var deviceAddress: String
    private var isScanning = false
    private var deviceIsSelected = false
    private var isConnected = false
    private var isOnLED = false
    private var isReceivingData = false

    private var discoveredDevices = arrayListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnConnect.isEnabled = false
        btnData.isEnabled = false
        btnLED.isEnabled = false

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if(bluetoothAdapter == null)
        {
            toast(getString(R.string.bt_not_available))
            finish()
        }

        if (!packageManager.hasSystemFeature(
                PackageManager.FEATURE_BLUETOOTH_LE
            ))
        {
            toast(getString(R.string.ble_not_supported))
            finish()
        }

        scanner = bluetoothAdapter.bluetoothLeScanner

        // BluetoothLe Service starten
        val gattServiceIntent = Intent(this, BluetoothLeService::class.java)
        // Service anbinden
        bindService(gattServiceIntent, serviceConnection, BIND_AUTO_CREATE)

        btnDiscoverDevices.setOnClickListener {

            checkBTPermission()

            if (!isScanning) { // Suche ist nicht gestartet
                scanner.startScan(scanCallback)
                //scanner?.startScan(scanCallback)
                Log.i(TAG, "Starte Scan")
                isScanning = true
                btnDiscoverDevices.text = getString(R.string.stop_search_device)
            } else {                        // Suche ist gestartet
                scanner.stopScan(scanCallback)
                Log.i(TAG, "Stoppe Scan")
                isScanning = false
                btnDiscoverDevices.text = getString(R.string.start_search_device)
            }
        }

        btnConnect.setOnClickListener {
            // Button Logik und connect bzw disconnect
            if (isConnected) {
                bluetoothLeService!!.disconnect()
                isConnected = false
                tvConnected.text = getString(R.string.bt_connect_off)
            } else {
                bluetoothLeService!!.connect(deviceAddress)
            }
        }

        btnLED.setOnClickListener {
            // Button Logik und 'H' bzw 'L' senden
            val value = ByteArray(1)
            // Werte setzen
            if (isOnLED) {
                isOnLED = false
                btnLED.text = getString(R.string.bt_led_on)
                tvLED.text = getString(R.string.led_off)
                value[0] = 'L'.toByte()
            } else {
                isOnLED = true
                btnLED.text = getString(R.string.bt_led_off)
                tvLED.text = getString(R.string.led_on)
                value[0] = 'H'.toByte()
            }

            // Senden
            if (gattCharacteristic != null) {
                gattCharacteristic.value = value
                bluetoothLeService!!.writeCharacteristic(gattCharacteristic)
            } else {
                toast(getString(R.string.no_gatt))
            }
        }

        btnData.setOnClickListener {
            if (isReceivingData) {
                bluetoothLeService!!.setCharacteristicNotification(gattCharacteristic, false);
                isReceivingData = false;
                btnData.text = getString(R.string.bt_data_on);
                tvData.setText(R.string.no_data);
            } else {
                bluetoothLeService!!.setCharacteristicNotification(gattCharacteristic, true);
                isReceivingData = true;
                btnData.text = getString(R.string.bt_data_off);
            }
        }

        listview.onItemClickListener = lvClickListener
    }

    private val lvClickListener =
        AdapterView.OnItemClickListener { parent, view, position, id -> // Ger??t aus dem Listview ausw??hlen
            if (isScanning) {
                scanner.stopScan(scanCallback)
                isScanning = false
                btnDiscoverDevices.text = getText(R.string.start_search_device)
            }
            selectedDevice = (view as TextView).text.toString()
            deviceAddress = selectedDevice.substring(selectedDevice.length - 17)
            tvSelectedDeviceTitle.visibility = View.VISIBLE
            tvSelectedDevice.visibility = View.VISIBLE
            tvSelectedDevice.text = selectedDevice
            deviceIsSelected = true
            btnConnect.isEnabled = true
        }

    override fun onResume() {
        super.onResume()
        if (!bluetoothAdapter.isEnabled) {
            val turnBTOn = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            startActivityForResult(turnBTOn, 1)
        }
        registerReceiver(gattUpdateReceiver, makeGattUpdateIntentFilter());
        if (bluetoothLeService != null && isConnected) {
            var result = bluetoothLeService!!.connect(deviceAddress);
            Log.d(TAG, "Connect request result=" + result);
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(gattUpdateReceiver)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val deviceInfo = """${result.device.name}${result.device.address}""".trimIndent()
            Log.i(TAG, "DeviceFound: $deviceInfo")

            // gefundenes Ger??t der Liste hinzuf??gen, wenn es noch nicht aufgef??hrt ist
            if (!discoveredDevices.contains(deviceInfo)) {
                discoveredDevices.add(deviceInfo)
            }

            // aktualisierte Liste im Listview anzeigen
            val adapt = ArrayAdapter(applicationContext, android.R.layout.simple_list_item_1,
                discoveredDevices)
            listview.adapter = adapt
        }
    }

    private fun checkBTPermission() {
        var permissionCheck = checkSelfPermission("Manifest.permission.ACCESS_FINE_LOCATION")
        permissionCheck += checkSelfPermission("Manifest.permission.ACCESS_COARSE_LOCATION")
        if (permissionCheck != 0) {
            requestPermissions(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ), 1001
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Aufr??umen
        scanner.stopScan(scanCallback)
        bluetoothLeService!!.disconnect()
        unbindService(serviceConnection)
        bluetoothLeService = null
    }

    // BluetoothLE Service Anbindung
    private val serviceConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(componentName: ComponentName, service: IBinder) {
            // Variable zum Zugriff auf die Service-Methoden
            bluetoothLeService = (service as BluetoothLeService.LocalBinder).getService()
            if (!bluetoothLeService!!.initialize()) {
                Log.e(TAG, "Unable to initialize Bluetooth")
                finish()
            }
        }

        override fun onServiceDisconnected(componentName: ComponentName) {
            bluetoothLeService = null
        }
    }

    private val gattUpdateReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            val action = intent.action
            if (BluetoothLeService.ACTION_GATT_CONNECTED == action) {
                isConnected = true
                tvConnected.setText(R.string.connected)
                btnData.isEnabled = true
                btnLED.isEnabled = true
                btnDiscoverDevices.isEnabled = false
                Log.i(TAG, "connected")
            } else if (BluetoothLeService.ACTION_GATT_DISCONNECTED == action) {
                isConnected = false
                tvConnected.setText(R.string.disconnected)
                btnData.isEnabled = false
                btnLED.isEnabled = false
                btnDiscoverDevices.isEnabled = true
                Log.i(TAG, "disconnected")
            } else if (BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED == action) {
                Log.i(TAG, "services discovered")


                // alle Services und Characteristics im Log aussgeben
                for (gattService in bluetoothLeService!!.getSupportedGattServices()!!) {

                    // Wir merken uns die Characteristic, ??ber die wir kommunizieren
                    if (gattService!!.uuid.toString() == BluetoothLeService.GATT_SERVICE_UUID) {
                        gattCharacteristic = gattService.getCharacteristic(
                                UUID.fromString(BluetoothLeService.GATT_CHARACTERISTIC_UUID))
                        Log.i(TAG, "GATT_SERVICE_UUID gefunden")
                    }
                    Log.i(TAG, "Gatt Service: " + gattService!!.uuid.toString())
                    for (gattCharacteristic in gattService.characteristics) {
                        Log.i(TAG, "Gatt Characteristic: " + gattCharacteristic.uuid.toString())
                    }
                }
            } else if (BluetoothLeService.ACTION_DATA_AVAILABLE == action) {
                // neue Daten verf??gbar
                Log.i(TAG, "Data available")
                val bytes: ByteArray = gattCharacteristic.value
                // byte[] to string
                val s = String(bytes, StandardCharsets.UTF_8)
                tvData.text = s
            }
        }
    }


    private fun makeGattUpdateIntentFilter(): IntentFilter? {
        val intentFilter = IntentFilter()
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_CONNECTED)
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_DISCONNECTED)
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED)
        intentFilter.addAction(BluetoothLeService.ACTION_DATA_AVAILABLE)
        return intentFilter
    }
}


