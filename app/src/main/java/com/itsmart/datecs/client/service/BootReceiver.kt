package com.itsmart.datecs.client.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.itsmart.datecs.client.device.InternalFiscalBridge

/** BlueCash: monitorizarea casei porneste si dupa repornirea aparatului, fara deschiderea aplicatiei. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!InternalFiscalBridge.isAvailable()) return
        try {
            ctx.startForegroundService(Intent(ctx, AgentPollingService::class.java).setAction(AgentPollingService.ACTION_START)
                .putExtra(AgentPollingService.EXTRA_FROM_BOOT, true))
        } catch (_: Throwable) {}
    }
}
