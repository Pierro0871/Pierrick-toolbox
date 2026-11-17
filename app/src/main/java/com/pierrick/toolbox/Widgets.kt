package com.pierrick.toolbox

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.RemoteViews

object Torch {
    @Volatile var on = false
    private var registered = false
    fun camId(ctx: Context): String? {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        if (!registered) {
            registered = true
            cm.registerTorchCallback(object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(id: String, enabled: Boolean) { on = enabled }
            }, null)
        }
        return cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
    }
    fun toggle(ctx: Context) {
        val id = camId(ctx) ?: return
        runCatching { (ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager).setTorchMode(id, !on); on = !on }
    }
}

fun openToolIntent(ctx: Context, tool: Tool): PendingIntent = PendingIntent.getActivity(
    ctx, tool.ordinal, Intent(ctx, MainActivity::class.java).putExtra("tool", tool.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

// ---------------- Widget écran d'accueil ----------------

class ToolboxWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { id -> mgr.updateAppWidget(id, views(ctx)) }
    }
    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        if (intent.action == ACTION_TORCH) {
            Torch.toggle(ctx)
            val mgr = AppWidgetManager.getInstance(ctx)
            mgr.updateAppWidget(ComponentName(ctx, ToolboxWidget::class.java), views(ctx))
        }
    }
    companion object {
        const val ACTION_TORCH = "com.pierrick.toolbox.TORCH"
        fun views(ctx: Context) = RemoteViews(ctx.packageName, R.layout.widget).apply {
            Torch.camId(ctx)
            setTextViewText(R.id.w_torch, if (Torch.on) "🔦\nON" else "🔦\nLampe")
            setOnClickPendingIntent(R.id.w_torch, PendingIntent.getBroadcast(ctx, 1, Intent(ctx, ToolboxWidget::class.java).setAction(ACTION_TORCH), PendingIntent.FLAG_IMMUTABLE))
            setOnClickPendingIntent(R.id.w_qr, openToolIntent(ctx, Tool.QR))
            setOnClickPendingIntent(R.id.w_calc, openToolIntent(ctx, Tool.CALC))
            setOnClickPendingIntent(R.id.w_shop, openToolIntent(ctx, Tool.SHOPPING))
            setOnClickPendingIntent(R.id.w_notes, openToolIntent(ctx, Tool.NOTES))
            setOnClickPendingIntent(R.id.w_home, PendingIntent.getActivity(ctx, 999, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        }
    }
}

// ---------------- Tuiles réglages rapides ----------------

class TorchTile : TileService() {
    override fun onStartListening() { Torch.camId(this); update() }
    override fun onClick() { Torch.toggle(this); update() }
    private fun update() { qsTile?.apply { state = if (Torch.on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE; label = "Lampe"; updateTile() } }
}

@android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
class BlockerTile : TileService() {
    override fun onStartListening() = update()
    override fun onClick() {
        val running = BlockerVpnService.running
        val needsApp = VpnService.prepare(this) != null || (running && (prefs(this).getString("bl_pin", null) != null || BlockList.focusActive(this)))
        if (needsApp) {
            val i = Intent(this, MainActivity::class.java).putExtra("tool", Tool.BLOCKER.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 5, i, PendingIntent.FLAG_IMMUTABLE))
            else @Suppress("DEPRECATION") startActivityAndCollapse(i)
            return
        }
        if (running) startService(Intent(this, BlockerVpnService::class.java).setAction(BlockerVpnService.STOP))
        else startService(Intent(this, BlockerVpnService::class.java))
        qsTile?.apply { state = if (running) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE; updateTile() }
    }
    private fun update() { qsTile?.apply { state = if (BlockerVpnService.running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE; label = "Bloqueur"; updateTile() } }
}
