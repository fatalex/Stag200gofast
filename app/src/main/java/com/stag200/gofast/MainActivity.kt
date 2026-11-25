package com.stag200.gofast

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvTelemetry: TextView
    private lateinit var tvHexLog: TextView
    private lateinit var btnConnect: Button
    private lateinit var btnPoll: Button

    private lateinit var btManager: BluetoothSppManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val logLines = ArrayDeque<String>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private var ecuFwInfo = "не определена"
    private var ecuRtc = "—"
    private var errSummary = "не проверялись"
    private var polling = false

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (polling && btManager.isConnected) {
                sendCommand(UgicProtocol.CMD_LIVE_DUMP, 0x00, "Live (0x64)")
                mainHandler.postDelayed(this, 600L)
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) {
            showPairedDevicesDialog()
        } else {
            Toast.makeText(this, "Нужны разрешения Bluetooth для подключения", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvTelemetry = findViewById(R.id.tvTelemetry)
        tvHexLog = findViewById(R.id.tvHexLog)
        btnConnect = findViewById(R.id.btnConnect)
        btnPoll = findViewById(R.id.btnPoll)

        btManager = BluetoothSppManager(
            onConnected = { name ->
                runOnUiThread {
                    tvStatus.text = "Статус: Подключено к $name"
                    btnConnect.text = "Отключить"
                    btnPoll.isEnabled = true
                    appendLog("SYS", "Подключено к $name")
                    sendCommand(UgicProtocol.CMD_DEVICE_INFO, 0x00, "Паспорт ЭБУ (0x00)")
                    mainHandler.postDelayed({ sendCommand(UgicProtocol.CMD_ERR_COUNTERS, 0x00, "Ошибки (0x40)") }, 250L)
                    mainHandler.postDelayed({ sendCommand(UgicProtocol.CMD_LIVE_DUMP, 0x00, "Live (0x64)") }, 500L)
                }
            },
            onDisconnected = { reason ->
                runOnUiThread {
                    stopPolling()
                    tvStatus.text = "Статус: $reason"
                    btnConnect.text = "Выбрать Bluetooth"
                    btnPoll.isEnabled = false
                    appendLog("SYS", reason)
                }
            },
            onFrameReceived = { frame, crcOk ->
                runOnUiThread {
                    val cmd = frame[4].toInt() and 0xFF
                    val payload = frame.copyOfRange(6, frame.size - 1)
                    appendLog("RX", "${UgicProtocol.toHex(frame)} [${if (crcOk) "CRC OK" else "CRC ERR"}]")
                    if (!crcOk) return@runOnUiThread

                    when (cmd) {
                        0x80 -> {
                            UgicProtocol.parseFwDate00(payload)?.let { ecuFwInfo = it }
                            updateDashboard(null)
                        }
                        0xC0 -> {
                            val total = payload.sumOf { it.toInt() and 0xFF }
                            errSummary = if (total == 0) "Ошибок нет (0)" else "Найдено ошибок: $total"
                            updateDashboard(null)
                        }
                        0xC9 -> {
                            UgicProtocol.parseRtc49(payload)?.let { ecuRtc = it }
                            updateDashboard(null)
                        }
                        0xE4 -> {
                            val dump = UgicProtocol.parseLiveDump64(payload)
                            updateDashboard(dump)
                        }
                    }
                }
            },
            onRawBytesReceived = { _ -> }
        )

        btnConnect.setOnClickListener {
            if (btManager.isConnected) {
                btManager.disconnect("Отключено пользователем")
            } else {
                ensureBluetoothPermissionsAndPick()
            }
        }

        btnPoll.setOnClickListener {
            if (polling) stopPolling() else startPolling()
        }

        findViewById<Button>(R.id.btnCmd03).apply {
            text = "0x00 ЭБУ"
            setOnClickListener { sendCommand(UgicProtocol.CMD_DEVICE_INFO, 0x00, "Паспорт (0x00)") }
        }
        findViewById<Button>(R.id.btnCmd14).apply {
            text = "0x64 Live"
            setOnClickListener { sendCommand(UgicProtocol.CMD_LIVE_DUMP, 0x00, "Live (0x64)") }
        }
        findViewById<Button>(R.id.btnCmd40).apply {
            text = "0x40 Ошибки"
            setOnClickListener { sendCommand(UgicProtocol.CMD_ERR_COUNTERS, 0x00, "Ошибки (0x40)") }
        }

        findViewById<Button>(R.id.btnCopyLog).setOnClickListener {
            val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cb.setPrimaryClip(ClipData.newPlainText("STAG200_HEX_LOG", logLines.joinToString("\n")))
            Toast.makeText(this, "Лог скопирован", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnClearLog).setOnClickListener {
            logLines.clear()
            tvHexLog.text = "Лог очищен."
        }
    }

    private fun updateDashboard(dump: UgicProtocol.LiveDump64?) {
        tvTelemetry.text = buildString {
            appendLine("ЭБУ: $ecuFwInfo")
            appendLine("Ошибки: $errSummary  |  Часы: $ecuRtc")
            if (dump != null) {
                appendLine("──────────────────────────────")
                appendLine("Каналы (0x64): ${dump.ch0} | ${dump.ch1} | ${dump.ch2} | ${dump.ch3} | ${dump.ch4} | ${dump.ch5}")
                appendLine("Блок A (Ц1-4): ${dump.injA.joinToString(" / ")}")
                appendLine("Блок B (Ц1-4): ${dump.injB.joinToString(" / ")}")
                if (dump.asciiInfo.isNotEmpty()) {
                    appendLine("──────────────────────────────")
                    append(dump.asciiInfo)
                }
            }
        }
    }

    private fun sendCommand(cmd: Byte, sub: Byte, label: String) {
        if (!btManager.isConnected) {
            Toast.makeText(this, "Сначала подключитесь к адаптеру", Toast.LENGTH_SHORT).show()
            return
        }
        val frame = UgicProtocol.buildFrame(cmd, sub)
        appendLog("TX", "${UgicProtocol.toHex(frame)} // $label")
        btManager.send(frame)
    }

    private fun startPolling() {
        polling = true
        btnPoll.text = "Стоп опрос"
        mainHandler.removeCallbacks(pollRunnable)
        mainHandler.post(pollRunnable)
    }

    private fun stopPolling() {
        polling = false
        btnPoll.text = "Автоопрос"
        mainHandler.removeCallbacks(pollRunnable)
    }

    private fun appendLog(tag: String, msg: String) {
        val line = "${timeFmt.format(Date())} [$tag] $msg"
        logLines.addFirst(line)
        while (logLines.size > 150) logLines.removeLast()
        tvHexLog.text = logLines.joinToString("\n")
    }

    private fun ensureBluetoothPermissionsAndPick() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val needed = arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            )
            val missing = needed.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (missing.isNotEmpty()) {
                permissionLauncher.launch(missing.toTypedArray())
                return
            }
        }
        showPairedDevicesDialog()
    }

    @SuppressLint("MissingPermission")
    private fun showPairedDevicesDialog() {
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter: BluetoothAdapter? = manager.adapter
        if (adapter == null || !adapter.isEnabled) {
            Toast.makeText(this, "Включите Bluetooth на телефоне", Toast.LENGTH_LONG).show()
            return
        }
        val paired: List<BluetoothDevice> = adapter.bondedDevices?.toList().orEmpty()
        if (paired.isEmpty()) {
            Toast.makeText(this, "Нет сопряжённых Bluetooth-устройств.", Toast.LENGTH_LONG).show()
            return
        }
        val names = paired.map { "${it.name ?: "Без имени"}\n${it.address}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Выберите адаптер STAG")
            .setItems(names) { _, which ->
                val device = paired[which]
                tvStatus.text = "Подключение к ${device.name ?: device.address}..."
                btManager.connect(device)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPolling()
        btManager.disconnect()
    }
}
