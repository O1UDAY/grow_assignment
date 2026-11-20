package app.dueback

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

internal fun listenerGranted(ctx:Context):Boolean {
    val component = ComponentName(ctx, CaptureService::class.java)
    if (Build.VERSION.SDK_INT >= 27)
        return ctx.getSystemService(NotificationManager::class.java)?.isNotificationListenerAccessGranted(component) == true
    return Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners").orEmpty()
        .split(':').any { ComponentName.unflattenFromString(it) == component }
}

private data class CaptureState(val granted:Boolean, val selected:Set<String>, val status:String,
    val time:Long, val source:String?, val outcome:String?)
@Composable private fun captureState(resumeTick:Int):CaptureState {
    val ctx=LocalContext.current
    val settings=remember(ctx){prefs(ctx)}
    var change by remember { mutableIntStateOf(0) }
    DisposableEffect(settings){
        val listener=SharedPreferences.OnSharedPreferenceChangeListener { _,_ -> change++ }
        settings.registerOnSharedPreferenceChangeListener(listener)
        onDispose {settings.unregisterOnSharedPreferenceChangeListener(listener)}
    }
    return remember(resumeTick,change) {
        val granted=listenerGranted(ctx)
        val selected=sources.keys.filter {settings.getBoolean(it,false)}.toSet()
        CaptureState(granted,selected,CapturePolicy.status(granted,selected.size,CaptureService.connected),
            settings.getLong("eventTime",0),settings.getString("eventSource",null),settings.getString("outcome",null))
    }
}
@Composable fun AccessForm(resumeTick:Int,say:(String)->Unit,diagnostics:()->Unit,email:()->Unit) {
    val ctx=LocalContext.current
    val state=captureState(resumeTick)
    Section("Notification access · "+if(state.granted)"Enabled" else "Off")
    Text("Android can share the visible content of new notifications. DueBack processes only the apps you select. This grants no access to SMS history or your email inbox.")
    Section("Apps to listen to")
    sources.forEach { (pkg,label) ->
        val checked=pkg in state.selected
        Row(Modifier.fillMaxWidth().toggleable(checked,role=Role.Switch,onValueChange={
            prefs(ctx).edit().putBoolean(pkg,it).apply()
        }).heightIn(min=52.dp),verticalAlignment=Alignment.CenterVertically) {
            Text(label,Modifier.weight(1f)); Switch(checked,null)
        }
        HorizontalDivider()
    }
    Text(state.status)
    Action(if(state.granted)"Manage Android access" else "Open Android notification access"){
        runCatching { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            .onFailure { say("Open Android Settings and search for Notification access, then select DueBack.") }
    }
    Link("View capture diagnostics",diagnostics)
    Section("Other inputs")
    Text("Share selected SMS or email text to DueBack, or use Paste text. Under Payments, import a CSV or add a payment. Add expected refunds under Refunds.")
    Link("Gmail inbox import",email)
    Section("DueBack alerts")
    Text("Optional alerts let you know when your ledger changes.")
    val request=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){
        say(if(it)"DueBack alerts enabled." else "You can see updates in Activity.")
    }
    Link("Manage DueBack alerts"){
        if(Build.VERSION.SDK_INT>=33 && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED &&
            !prefs(ctx).getBoolean("askedAlerts",false)) {
            prefs(ctx).edit().putBoolean("askedAlerts", true).apply()
            request.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else runCatching {ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,ctx.packageName))}
            .onFailure {say("Open DueBack in Android app settings to manage alerts.")}
    }
}

@Composable fun Diagnostics(resumeTick:Int,manage:()->Unit) {
    val state=captureState(resumeTick)
    BoxCard { Section("Android access");Text(if(state.granted)"Enabled" else "Off");Text(state.status) }
    BoxCard { Section("Selected apps");Text(state.selected.mapNotNull {sources[it]}.joinToString().ifBlank {"None selected"}) }
    BoxCard {
        Section("Last selected-app notification")
        if(state.time==0L) Text("No event received yet.") else {
            Text(state.source.orEmpty()+" · "+DateFormat.getDateTimeInstance().format(Date(state.time)))
            Text(state.outcome.orEmpty())
        }
    }
    Text("Only the source, time and result appear here. If no event arrives, check that the source app posts notifications with previews enabled.")
    Action("Manage capture sources",manage)
}
