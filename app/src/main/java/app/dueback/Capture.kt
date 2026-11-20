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
val sources=mapOf("com.google.android.apps.nbu.paisa.user" to "Google Pay","com.phonepe.app" to "PhonePe",
    "com.google.android.apps.messaging" to "Google Messages","com.google.android.gm" to "Gmail",
    "com.sbi.lotusintouch" to "YONO SBI","com.csam.icici.bank.imobile" to "iMobile")
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
internal fun sourceAllowed(pkg: String, selected: Boolean, summary: Boolean) =
    CapturePolicy.accept(pkg in sources, selected, summary)

internal fun textKey(raw: String) = "text/" + digest(raw.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " "))

internal fun refundKey(p: Rules.Parsed, raw: String): String {
    if (p.merchant.isBlank() || p.order.isBlank() || p.amount.isBlank()) return textKey(raw)
    return "refund/" + digest(listOf(p.merchant.trim().lowercase(Locale.ROOT), p.order.uppercase(Locale.ROOT),
        runCatching { Rules.paise(p.amount).toString() }.getOrDefault(p.amount)).joinToString("|"))
}

internal suspend fun captureText(store: Store, raw: String, source: String, key: String, trustedCredit: Boolean): String {
    val p = Rules.parse(raw)
    return when (p.kind) {
        Rules.Kind.IGNORE -> NO_TRANSACTION
        Rules.Kind.PROMISE, Rules.Kind.CANCEL -> store.db.withTransaction {
            val id = refundKey(p, raw)
            if (store.q.remember(SeenInput(id)) == -1L) return@withTransaction DUPLICATE
            val fields = listOf("Merchant: " + p.merchant,
                if (p.kind == Rules.Kind.CANCEL) "Cancelled" else "Refund initiated",
                "INR " + p.amount, "order " + p.order, "expected " + p.date, "destination: " + p.destination,
                "Source: " + source).joinToString("\n")
            store.q.draft(Draft(id, fields))
            "Refund ready for review".also { store.note(it) }
        }
        else -> {
            val amount = runCatching { Rules.paise(p.amount) }.getOrNull() ?: return NO_TRANSACTION
            if (amount <= 0) return NO_TRANSACTION
            store.ingest(Credit(amount=amount, reference=p.reference.ifBlank { null }, eventKey=key,
                payer=p.payer, destination=p.destination, source=source,
                confirmed=trustedCredit && p.kind == Rules.Kind.CREDIT, orderRef=p.order))
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
        if (!sourceAllowed(pkg, settings.getBoolean(pkg, false), sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0)) return
        val time = System.currentTimeMillis()
        val instanceTime = activePosts.getOrPut(sbn.key) { sbn.postTime }
        scope.launch {
            if (!settings.getBoolean(pkg, false)) return@launch
            val source = sources.getValue(pkg)
            val outcome = try {
                val raw = notificationText(sbn.notification)
                val trusted = pkg in setOf("com.google.android.apps.nbu.paisa.user", "com.phonepe.app",
                    "com.sbi.lotusintouch", "com.csam.icici.bank.imobile")
                val result = captureText(Store(Ledger.get(this@CaptureService)), raw, source,
                    digest(sbn.key + "|" + instanceTime + "|" + textKey(raw)), trusted)
                if (result != DUPLICATE && result != NO_TRANSACTION) deliver(this@CaptureService, "Open DueBack to review ledger activity.")
                when {
                    result == DUPLICATE || result == NO_TRANSACTION -> result
                    result.startsWith("Matched ") -> "Credit matched"
                    else -> "Transaction saved for review"
                }
            } catch(e: CancellationException) { throw e }
              catch(e: Exception) { "Could not process event. Share the text or import a CSV." }
            if (settings.getLong("eventTime", 0L) > time) return@launch
            settings.edit().putLong("eventTime", time).putString("eventSource", source)
                .putString("outcome", outcome).apply()
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
        val confirmed=kind in listOf("credit","cr","received") || ("credit" in header && kind.isBlank())
        val signature = rowKeys[index]
        val occurrence = seenRows.getOrDefault(signature, 0)
        seenRows[signature] = occurrence + 1
        val legacyKey = "csv/$batch/$index"
        if (legacyKey in existingKeys) { duplicates++; return@forEachIndexed }
        val msg=store.ingest(Credit(amount=p,reference=col("utr","rrn","transaction_id","reference").ifBlank { null },
            eventKey="csv/$statement/$signature/$occurrence",payer=col("payer_id","upi_id"),destination=col("destination","account"),
            source="CSV",confirmed=confirmed,orderRef=col("order_ref")))
        if(msg=="Already recorded") duplicates++ else added++
    }
    val summary="Imported "+added+" · "+duplicates+" duplicates · "+skipped+" skipped"
    store.note(summary)
    summary
}

