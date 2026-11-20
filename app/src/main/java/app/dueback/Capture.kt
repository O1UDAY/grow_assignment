package app.dueback

import android.Manifest
import android.app.*
import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent
import android.os.Build
import android.service.notification.*
import androidx.room.withTransaction
import kotlinx.coroutines.*
import java.math.BigDecimal
import java.security.MessageDigest
import java.text.NumberFormat
import java.util.Locale

fun money(p: Long): String = NumberFormat.getCurrencyInstance(Locale("en","IN")).apply {
    minimumFractionDigits=if(p%100==0L) 0 else 2
}.format(BigDecimal.valueOf(p,2))
internal fun installedSources(c:Context):Map<String,String> {
    val pm=c.packageManager
    val pairs=listOf("smsto","mailto","upi").flatMap { scheme ->
        val action=if(scheme=="upi") Intent.ACTION_VIEW else Intent.ACTION_SENDTO
        val uri=android.net.Uri.parse(if(scheme=="upi") "upi://pay" else "$scheme:")
        pm.queryIntentActivities(Intent(action,uri),0).map { it.activityInfo.applicationInfo }
    }.toMutableList()
    for(pkg in listOf("com.sbi.lotusintouch","com.csam.icici.bank.imobile","com.snapwork.hdfc","com.axis.mobile",
        "com.phonepe.app","com.google.android.apps.nbu.paisa.user","com.google.android.apps.messaging","com.google.android.gm")) {
        try { pairs.add(pm.getApplicationInfo(pkg,0)) } catch(_:PackageManager.NameNotFoundException) { }
    }
    return pairs.filter {it.enabled && it.packageName!=c.packageName}.distinctBy {it.packageName}
        .sortedBy {it.loadLabel(pm).toString()}.associate {it.packageName to it.loadLabel(pm).toString()}
}
fun prefs(c: Context)=c.getSharedPreferences("capture",Context.MODE_PRIVATE)
fun digest(s:String)=MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
fun deliver(c:Context,msg:String) {
    if(Build.VERSION.SDK_INT>=33 && c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) return
    val manager=c.getSystemService(NotificationManager::class.java) ?: return
    if(!manager.areNotificationsEnabled()) return
    manager.createNotificationChannel(NotificationChannel("ledger","DueBack activity",NotificationManager.IMPORTANCE_DEFAULT))
    val open=PendingIntent.getActivity(c,1,Intent(c,MainActivity::class.java).putExtra("incoming",true),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    manager.notify(msg.hashCode(),Notification.Builder(c,"ledger").setSmallIcon(android.R.drawable.stat_notify_more)
        .setVisibility(Notification.VISIBILITY_PRIVATE).setContentTitle("DueBack").setContentText(msg).setContentIntent(open).setAutoCancel(true).build())
}
private const val NO_TRANSACTION = "No supported transaction found"
private const val DUPLICATE = "Already recorded"


internal fun textKey(raw: String) = "text/" + digest(raw.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " "))

internal suspend fun captureText(store: Store, raw: String, source: String, key: String, sourceTime: Long? = null, zone:java.time.ZoneId=java.time.ZoneId.systemDefault()): String {
    val p = Rules.parse(raw, sourceTime?.let(java.time.Instant::ofEpochMilli), zone)
    if(p.kind==Rules.Kind.REVIEW && (p.amount.isBlank() || p.amountNeedsReview)) {
        return store.db.withTransaction {
            if(store.q.remember(SeenInput(key)) == -1L) return@withTransaction DUPLICATE
            val extracted=listOf("Check payment amount","Transaction ID "+p.reference,"order "+p.order,"Source: "+source).joinToString("\n")
            store.q.draft(Draft(key,extracted,sourceTime,zone.id,true))
            "Payment amount needs review".also {store.note(it)}
        }
    }
    return when (p.kind) {
        Rules.Kind.IGNORE -> NO_TRANSACTION
        Rules.Kind.PROMISE, Rules.Kind.CANCEL -> store.db.withTransaction {
            val id = if (p.reference.isNotBlank()) "refund-reference/"+digest(p.reference.uppercase(Locale.ROOT)) else key
            if (store.q.remember(SeenInput(id)) == -1L) return@withTransaction DUPLICATE
            val fields = listOf("Merchant: " + p.merchant,
                if (p.kind == Rules.Kind.CANCEL) "Cancelled" else "Refund initiated",
                if(p.amountNeedsReview) "Check the promised amount" else "INR " + p.amount, "order " + p.order, "expected " + p.date, "destination: " + p.destination,
                if(p.dateNeedsReview) "Check expected date: source date or business calendar uncertain." else "", "Source: " + source).filter {it.isNotBlank()}.joinToString("\n")
            store.q.draft(Draft(id, fields,sourceTime,zone.id))
            "Refund ready for review".also { store.note(it) }
        }
        else -> {
            val amount = runCatching { Rules.paise(p.amount) }.getOrNull() ?: return NO_TRANSACTION
            if (amount <= 0) return NO_TRANSACTION
            store.ingest(Credit(amount=amount, reference=p.reference.ifBlank { null }, eventKey=key,
                payer=p.payer, destination=p.destination, source=source,
                sourceTime=sourceTime,sourceZone=zone.id,confirmed=false, orderRef=p.order))
        }
    }
}

internal fun notificationText(n: Notification): String {
    val extras = n.extras ?: return ""
    val message = if (Build.VERSION.SDK_INT >= 30)
        extras.getParcelableArray(Notification.EXTRA_MESSAGES)?.let { Notification.MessagingStyle.Message.getMessagesFromBundleArray(it).lastOrNull()?.text } else null
    val body = listOf(message, extras.getCharSequence(Notification.EXTRA_BIG_TEXT),
        extras.getCharSequence(Notification.EXTRA_TEXT), extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.lastOrNull())
        .firstOrNull { !it.isNullOrBlank() }
    return listOfNotNull(extras.getCharSequence(Notification.EXTRA_TITLE), body)
        .joinToString("\n").take(12000)
}

internal fun notificationEvents(notification:Notification,pkg:String,notificationKey:String,instanceTime:Long):List<Triple<String,Long,String>> {
    val messages=notification.extras?.getParcelableArray(Notification.EXTRA_MESSAGES)
        .orEmpty().filterIsInstance<android.os.Bundle>()
    val isSummary=notification.flags and Notification.FLAG_GROUP_SUMMARY != 0
    return when {
        messages.isNotEmpty() -> messages.mapNotNull { bundle ->
            val body=bundle.getCharSequence("text")?.toString()?.take(12000) ?: return@mapNotNull null
            val stamp=bundle.getLong("time").takeIf {it>0} ?: instanceTime
            val sender=bundle.getCharSequence("sender")?.toString().orEmpty()
            Triple(body,stamp,digest("$pkg|$sender|$stamp|"+textKey(body)))
        }
        isSummary -> emptyList()
        else -> {
            val body=notificationText(notification)
            val stamp=notification.`when`.takeIf {it>0} ?: instanceTime
            listOf(Triple(body,stamp,digest(notificationKey+"|"+instanceTime+"|"+textKey(body))))
        }
    }
}

class CaptureService: NotificationListenerService() {
    companion object {
        @Volatile var connected = false
            private set
    }
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + worker)
    override fun onListenerConnected() { super.onListenerConnected(); connected = true; signal() }
    override fun onListenerDisconnected() { connected = false; signal(); super.onListenerDisconnected() }
    private fun signal() { prefs(this).edit().putLong("connection", android.os.SystemClock.elapsedRealtimeNanos()).apply() }
    override fun onDestroy() { connected = false; signal(); scope.cancel(); worker.close(); super.onDestroy() }
    // Android reuses a notification key for edits until that notification is removed.
    private val activePosts = mutableMapOf<String, Long>()
    override fun onNotificationRemoved(sbn:StatusBarNotification) { activePosts.remove(sbn.key) }
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        val settings = prefs(this)
        if(pkg==packageName && sbn.tag=="capture-test") {
            val nonce=settings.getString("probe",null) ?: return
            if(sbn.notification.extras.getString("dueback_probe")==nonce) {
                val parsed=Rules.parse(notificationText(sbn.notification))
                val ok=parsed.kind==Rules.Kind.CREDIT && parsed.amount=="12.34"
                settings.edit().remove("probe").putString("testResult",if(ok) "Passed: Android delivered the test and the parser recognised INR 12.34." else "Delivered, but parsing failed.").apply()
                cancelNotification(sbn.key)
            }
            return
        }
        if(settings.getBoolean("paused",false) || !settings.getBoolean(pkg,false)) return
        val source=installedSources(this)[pkg] ?: return
        val time=System.currentTimeMillis()
        val instanceTime=activePosts.getOrPut(sbn.key) { sbn.postTime }
        scope.launch {
            if(settings.getBoolean("paused",false) || !settings.getBoolean(pkg,false)) return@launch
            var failed=false
            val outcome=try {
                val notification=sbn.notification
                val isSummary=notification.flags and Notification.FLAG_GROUP_SUMMARY != 0
                val inputs=notificationEvents(notification,pkg,sbn.key,instanceTime)
                val store=Store(Ledger.get(this@CaptureService))
                var saved=0
                var duplicates=0
                for((body,stamp,key) in inputs) {
                    if(settings.getBoolean("paused",false) || !settings.getBoolean(pkg,false)) break
                    val result=store.db.withTransaction {
                        if(settings.getBoolean("paused",false) || !settings.getBoolean(pkg,false)) NO_TRANSACTION
                        else captureText(store,body,source,key,stamp)
                    }
                    when(result){
                        DUPLICATE -> duplicates++
                        NO_TRANSACTION -> Unit
                        else -> saved++
                    }
                }
                when {
                    saved>0 -> { deliver(this@CaptureService,"New items are ready to review."); "$saved ready for review" }
                    duplicates>0 -> DUPLICATE
                    isSummary && inputs.isEmpty() -> "Group summary. Waiting for individual messages."
                    notification.extras?.getCharSequence(Notification.EXTRA_TEXT).isNullOrBlank() &&
                        notification.extras?.getCharSequence(Notification.EXTRA_BIG_TEXT).isNullOrBlank() ->
                        "No supported transaction. Preview missing or limited to a title."
                    else -> NO_TRANSACTION
                }
            } catch(e:CancellationException){throw e}
              catch(e:Exception){failed=true; "Processing failed. Share the message or import the statement."}
            if (settings.getLong("eventTime",0)>time) return@launch
            settings.edit().putLong("eventTime",time).putString("eventSource",source)
                .putBoolean("processingFailed",failed).putString("outcome",outcome).apply()
        }
    }
}

suspend fun importCsv(store:Store,text:String):String = store.db.withTransaction {
    val rows=Rules.csv(text.removePrefix("\uFEFF"))
    require(rows.size>1) { "CSV needs a header and at least one transaction." }
    val header=rows.first().map { it.lowercase().trim() }
    require("credit" in header || ("amount" in header && "type" in header)) { "Use a credit column, or amount and type columns." }
    var added=0; var duplicates=0; var skipped=0
    val seenRows = mutableMapOf<String, Int>()
    fun rowSignature(row: List<String>): String {
        val fields=header.zip(row).sortedBy { it.first }.flatMap { (key,value) ->
            listOf(key,value.trim().lowercase(Locale.ROOT))
        }
        return digest(fields.joinToString("") { "${it.length}:$it" })
    }
    val rowKeys=rows.drop(1).map(::rowSignature)
    val statement=digest(rowKeys.sorted().joinToString(""))
    val batch=digest(text)
    val existingKeys = store.q.credits().mapNotNull { it.eventKey }.toSet()
    rows.drop(1).forEachIndexed { index,row ->
        fun col(vararg keys:String)=keys.firstNotNullOfOrNull { key -> row.getOrNull(header.indexOf(key))?.takeIf { it.isNotBlank() } }.orEmpty()
        val kind=col("type").lowercase()
        if(kind in listOf("debit","dr","request","collect","refund initiated","pending") || (col("credit").isBlank() && "credit" in header)) { skipped++;return@forEachIndexed }
        val amount=col("credit","amount")
        val p=try {Rules.paise(amount)} catch(e:Exception) {throw IllegalArgumentException("Row ${index+2}: check the amount.")}
        if(p==0L) { skipped++;return@forEachIndexed }
        val stamp=col("timestamp","date_time")
        val sourceDate=if(stamp.isBlank()) null else try {
            java.time.OffsetDateTime.parse(stamp)
        } catch(e:java.time.DateTimeException){throw IllegalArgumentException("Row ${index+2}: use an ISO timestamp with timezone, or leave it empty.")}
        val signature = rowKeys[index]
        val occurrence = seenRows.getOrDefault(signature, 0)
        seenRows[signature] = occurrence + 1
        val legacyKey = "csv/$batch/$index"
        if (legacyKey in existingKeys) { duplicates++; return@forEachIndexed }
        val msg=store.ingest(Credit(amount=p,reference=col("utr","rrn","transaction_id","reference").ifBlank { null },
            eventKey="csv/$statement/$signature/$occurrence",payer=col("payer_id","upi_id"),destination=col("destination","account"),
            sourceTime=sourceDate?.toInstant()?.toEpochMilli(),sourceZone=sourceDate?.offset?.id,source="CSV",confirmed=false,orderRef=col("order_ref")))
        if(msg=="Already recorded") duplicates++ else added++
    }
    val summary="Imported "+added+" · "+duplicates+" duplicates · "+skipped+" skipped"
    store.note(summary)
    summary
}

