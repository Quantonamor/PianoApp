package de.pianotiles.app.audio

import android.content.Context
import android.content.pm.PackageManager
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Notes from a connected MIDI keyboard (digital piano via USB, or a paired Bluetooth-MIDI device that Android
 * lists). Exact and without delay – much better than the microphone for instruments that have a MIDI output.
 * Every device that sends notes is opened automatically, also when it is plugged in later.
 * Callbacks arrive on the main thread.
 */
class MidiInput(
    context: Context,
    private val onKey: (pitch: Int, velocity: Int, down: Boolean) -> Unit,
    private val onDevices: (names: List<String>) -> Unit,
) {
    private val manager: MidiManager? =
        if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_MIDI)) context.getSystemService(Context.MIDI_SERVICE) as? MidiManager else null
    private val main = Handler(Looper.getMainLooper())
    private val open = HashMap<Int, Pair<MidiDevice, List<MidiOutputPort>>>()
    private var started = false

    val available: Boolean get() = manager != null

    private val callback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(device: MidiDeviceInfo) = openDevice(device)
        override fun onDeviceRemoved(device: MidiDeviceInfo) {
            open.remove(device.id)?.let { (d, ports) -> ports.forEach { runCatching { it.close() } }; runCatching { d.close() } }
            report()
        }
    }

    fun start() {
        val m = manager ?: return
        if (started) return
        started = true
        @Suppress("DEPRECATION")
        val devices = if (Build.VERSION.SDK_INT >= 33) m.getDevicesForTransport(MidiManager.TRANSPORT_MIDI_BYTE_STREAM).toList() else m.devices.toList()
        devices.forEach { openDevice(it) }
        @Suppress("DEPRECATION")
        m.registerDeviceCallback(callback, main)
        report()
    }

    fun stop() {
        val m = manager ?: return
        if (!started) return
        started = false
        runCatching { m.unregisterDeviceCallback(callback) }
        for ((d, ports) in open.values) { ports.forEach { runCatching { it.close() } }; runCatching { d.close() } }
        open.clear()
        report()
    }

    private fun openDevice(info: MidiDeviceInfo) {
        val m = manager ?: return
        if (info.outputPortCount == 0 || open.containsKey(info.id)) return
        m.openDevice(info, { device ->
            if (device == null) return@openDevice
            val ports = (0 until info.outputPortCount).mapNotNull { i -> device.openOutputPort(i)?.also { it.connect(Parser()) } }
            open[info.id] = device to ports
            report()
        }, main)
    }

    private fun report() {
        val names = open.keys.mapNotNull { id ->
            open[id]?.first?.info?.properties?.let { it.getString(MidiDeviceInfo.PROPERTY_NAME) ?: it.getString(MidiDeviceInfo.PROPERTY_PRODUCT) ?: "MIDI" }
        }
        onDevices(names)
    }

    /** Note on / note off from the raw MIDI byte stream (running status supported). */
    private inner class Parser : MidiReceiver() {
        private var status = 0
        private val data = IntArray(2)
        private var count = 0

        override fun onSend(msg: ByteArray, offset: Int, length: Int, timestamp: Long) {
            for (i in offset until offset + length) {
                val b = msg[i].toInt() and 0xFF
                if (b >= 0xF8) continue // real-time messages
                if (b >= 0x80) { status = b; count = 0; continue }
                if (status == 0) continue
                data[count++] = b
                val type = status and 0xF0
                val needed = if (type == 0xC0 || type == 0xD0) 1 else 2
                if (count < needed) continue
                count = 0
                val pitch = data[0]; val vel = data[1]
                when (type) {
                    0x90 -> main.post { onKey(pitch, vel, vel > 0) }
                    0x80 -> main.post { onKey(pitch, vel, false) }
                }
            }
        }
    }
}
