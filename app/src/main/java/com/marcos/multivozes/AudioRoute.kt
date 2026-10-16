package com.marcos.multivozes

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

enum class Route(val label: String) {
    WIRED("fone com fio"),
    BLUETOOTH("fone Bluetooth"),
    SPEAKER("alto-falante")
}

object AudioRoute {

    private val wiredTypes = setOf(
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_LINE_ANALOG,
        AudioDeviceInfo.TYPE_LINE_DIGITAL
    )
    private val btTypes = setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        26, // TYPE_BLE_HEADSET (Android 12+)
        27  // TYPE_BLE_SPEAKER (Android 12+)
    )

    fun current(context: Context): Route {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        if (outs.any { it.type in btTypes }) return Route.BLUETOOTH
        if (outs.any { it.type in wiredTypes }) return Route.WIRED
        return Route.SPEAKER
    }

    /**
     * Microfone do próprio celular. Importante: fones com fio que têm microfone
     * (P2 de 4 polos) fariam o Android gravar pelo microfone do fone.
     */
    fun builtInMic(context: Context): AudioDeviceInfo? {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
    }

    fun nativeSampleRate(context: Context): Int {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
            ?.takeIf { it in 22050..96000 } ?: 48000
    }

    fun unprocessedSupported(context: Context): Boolean {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
    }
}
