package com.itsmart.datecs.client.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.itsmart.datecs.client.device.InternalFiscalBridge

/**
 * Dupa repornirea aparatului si dupa un update al aplicatiei, fara deschiderea ei: pe BlueCash monitorizarea casei,
 * pe telefon / tableta preluarea comenzilor CRM pentru ultima casa conectata.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!InternalFiscalBridge.isAvailable() &&
            ctx.getSharedPreferences("tda_server", Context.MODE_PRIVATE).getString("last_serial", null).isNullOrBlank()) return
        try {
            ctx.startForegroundService(Intent(ctx, AgentPollingService::class.java).setAction(AgentPollingService.ACTION_START)
                .putExtra(AgentPollingService.EXTRA_FROM_BOOT, true))
        } catch (_: Throwable) {}
    }
}
