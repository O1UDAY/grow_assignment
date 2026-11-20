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

private data class CaptureState(val granted:Boolean, val connected:Boolean, val selected:Set<String>, val status:String,
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
        val selected=installedSources(ctx).keys.filter {settings.getBoolean(it,false)}.toSet()
        CaptureState(granted,CaptureService.connected,selected,when {
            settings.getBoolean("paused",false) -> "Capture paused"
            granted && selected.isNotEmpty() && CaptureService.connected && settings.getBoolean("processingFailed",false) -> "Processing failed"
            else -> CapturePolicy.status(granted,selected.size,CaptureService.connected)
        },
            settings.getLong("eventTime",0),settings.getString("eventSource",null),settings.getString("outcome",null))
    }
}
@Composable fun AccessForm(resumeTick:Int,say:(String)->Unit,diagnostics:()->Unit,email:()->Unit,delete:()->Unit) {
    val ctx=LocalContext.current
    val state=captureState(resumeTick)
    Section("Notification access · "+if(state.granted)"Enabled" else "Off")
    Text("Android can share the visible content of new notifications. DueBack processes only the apps you select. This grants no access to SMS history or your email inbox.")
    Section("Apps to listen to")
    val available=remember(resumeTick){installedSources(ctx)}
    if(available.isEmpty()) Text("No supported apps installed. Share text or import a file instead.")
    available.forEach { (pkg,label) ->
        val checked=pkg in state.selected
        Row(Modifier.fillMaxWidth().toggleable(checked,role=Role.Switch,onValueChange={
            prefs(ctx).edit().putBoolean(pkg,it).putString("label/$pkg",label).apply()
        }).heightIn(min=52.dp),verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)){Text(label);Text(pkg,style=MaterialTheme.typography.bodySmall)}; Switch(checked,null)
        }
        HorizontalDivider()
    }
    Text(state.status)
    val paused=prefs(ctx).getBoolean("paused",false)
    Link(if(paused) "Resume capture" else "Pause capture"){
        prefs(ctx).edit().putBoolean("paused",!paused).apply()
    }
    if(state.granted && !CaptureService.connected) Link("Reconnect listener"){
        runCatching { android.service.notification.NotificationListenerService.requestRebind(ComponentName(ctx,CaptureService::class.java)) }
            .onSuccess { say("Reconnect requested. If still disconnected, turn DueBack access off and on in Android settings.") }
            .onFailure {say("Turn DueBack access off and on in Android settings.")}
    }
    if(!state.granted && Build.VERSION.SDK_INT>=33) {
        var help by remember {mutableStateOf(false)}
        Link("Android blocked this setting?"){help=!help}
        if(help) Text("For a trusted sideloaded APK, open Settings → Apps → DueBack → More → Allow restricted settings if available. Otherwise use manual entry or share text.")
    }
    Action(if(state.granted)"Manage Android access" else "Open Android notification access"){
        runCatching { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            .onFailure { say("Open Android Settings and search for Notification access, then select DueBack.") }
    }
    Link("View capture diagnostics",diagnostics)
    Section("SMS history")
    Text("DueBack does not read your SMS inbox. Share or paste one message, import a text file, or choose a CSV statement.")
    Section("Other inputs")
    Text("Payments accepts CSV statements. Refunds and payments can always be entered manually.")
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
    Section("Privacy")
    var confirmDelete by remember { mutableStateOf(false) }
    Link("Delete imported data"){confirmDelete=true}
    if(confirmDelete) AlertDialog(onDismissRequest={confirmDelete=false},title={Text("Delete imports?")},
        text={Text("Removes imported payments and refunds, their matches, drafts and activity history. Your groups, bills and manual entries stay. Capture will pause.")},
        confirmButton={TextButton(onClick={confirmDelete=false;delete()}){Text("Delete imports")}},
        dismissButton={TextButton(onClick={confirmDelete=false}){Text("Keep data")}})

}

@Composable fun Diagnostics(resumeTick:Int,manage:()->Unit) {
    val ctx=LocalContext.current
    val state=captureState(resumeTick)
    BoxCard {
        Section("Android access")
        Text(if (state.granted) "Access granted" else "Access denied")
        Text(if (state.connected) "Listener connected" else "Listener disconnected")
        Text(state.status)
    }
    BoxCard { Section("Selected apps");Text(state.selected.mapNotNull {installedSources(ctx)[it]}.joinToString().ifBlank {"None selected"}) }
    BoxCard {
        Section("Last selected-app notification")
        if(state.time==0L) Text("No event received yet.") else {
            Text(state.source.orEmpty()+" · "+DateFormat.getDateTimeInstance().format(Date(state.time)))
            prefs(ctx).getString("eventShape",null)?.let { Text(it) }
            Text(state.outcome.orEmpty())
            if (prefs(ctx).getString("eventShape",null)?.startsWith("Group summary") == true && state.outcome?.startsWith("Saved for review") != true) {
                prefs(ctx).getString("detailOutcome",null)?.let { Text("Previous processing result: $it") }
            }
        }
    }
    Text("Only the source, time and result appear here. If no event arrives, check that the source app posts notifications with previews enabled.")
    Section("Listener test")
    val settings=prefs(ctx)
    var probeResult by remember {mutableStateOf(settings.getString("testResult",null))}
    DisposableEffect(settings){
        val listener=SharedPreferences.OnSharedPreferenceChangeListener { _,key -> if(key=="testResult") probeResult=settings.getString(key,null) }
        settings.registerOnSharedPreferenceChangeListener(listener)
        onDispose {settings.unregisterOnSharedPreferenceChangeListener(listener)}
    }
    Text("Posts a labelled test notification from DueBack. Nothing is added to your balance.")
    Action("Run listener test"){
        val manager=ctx.getSystemService(NotificationManager::class.java)
        if(!state.granted || !CaptureService.connected) probeResult="Enable access and reconnect the listener first."
        else if(manager==null || !manager.areNotificationsEnabled() || (Build.VERSION.SDK_INT>=33 && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED))
            probeResult="Enable DueBack alerts in Sources to post the test."
        else {
            val nonce=uid()
            settings.edit().putString("probe",nonce).putString("testResult","Waiting for delivery. If this persists, check the test channel and reconnect.").apply()
            manager.createNotificationChannel(android.app.NotificationChannel("test","Capture tests",NotificationManager.IMPORTANCE_DEFAULT))
            manager.notify("capture-test",17,android.app.Notification.Builder(ctx,"test").setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle("DueBack test data").setContentText("INR 12.34 credited").setTimeoutAfter(30000)
                .addExtras(android.os.Bundle().apply {putString("dueback_probe",nonce)}).build())
        }
    }
    probeResult?.let {Text(it)}
    Link("Manage capture sources",manage)
}
