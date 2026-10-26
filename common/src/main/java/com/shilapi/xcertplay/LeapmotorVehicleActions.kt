package com.shilapi.xcertplay

import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * Leapmotor entry points that the head unit does not expose anywhere else.
 *
 * The car's Bluetooth pairing screen is a system activity that only its own settings launcher
 * links to, and on several firmware builds it is not reachable from the user-facing Bluetooth
 * menu. Wireless CarPlay needs that pairing to happen once, so a shortcut saves hunting through
 * the head unit's hidden pages.
 *
 * This object only decides and launches; the settings screen owns the layout, so the shared
 * spacing and button-shape rules in `AGENTS.md` stay in one place.
 */
internal object LeapmotorVehicleActions {
    private const val PHONE_PACKAGE = "com.leapmotor.phone"
    private const val BACK_END_ACTIVITY = "com.leapmotor.phone.activity.BackEndActivity"

    /** Whether this runtime is a Leapmotor head unit, by fingerprint/brand or disguised package. */
    fun isLeapmotor(context: Context?): Boolean = LeapmotorMediaPublisher.isLeapmotor(context)

    private fun backEndIntent(): Intent = Intent("com.leapmotor.phone.activity.BackEndActivity").apply {
        component = ComponentName(PHONE_PACKAGE, BACK_END_ACTIVITY)
    }

    /** The pairing screen is unavailable when the car app is missing or has no such activity. */
    fun bluetoothPairingAvailable(context: Context): Boolean =
        runCatching { context.packageManager.resolveActivity(backEndIntent(), 0) != null }
            .getOrDefault(false)

    fun openBluetoothPairing(context: Context): Boolean = runCatching {
        context.startActivity(backEndIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)
}
