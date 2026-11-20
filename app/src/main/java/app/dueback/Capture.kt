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
    val manager=c.getSystemService(NotificationManager::class.java)
    if(!manager.areNotificationsEnabled()) return
    manager.createNotificationChannel(NotificationChannel("ledger","DueBack activity",NotificationManager.IMPORTANCE_DEFAULT))
    val open=PendingIntent.getActivity(c,1,Intent(c,MainActivity::class.java).putExtra("incoming",true),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    manager.notify(msg.hashCode(),Notification.Builder(c,"ledger").setSmallIcon(android.R.drawable.stat_notify_more)
        .setContentTitle("DueBack").setContentText(msg).setContentIntent(open).setAutoCancel(true).build())
}
class CaptureService: NotificationListenerService() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    override fun onDestroy(){ scope.cancel();super.onDestroy() }
    override fun onNotificationPosted(sbn:StatusBarNotification) {
        val pkg=sbn.packageName
        if(pkg !in sources || !prefs(this).getBoolean(pkg,false) || sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras=sbn.notification.extras
        val body=extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT) ?: return
        val raw=(extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()+"\n"+body).take(12000)
        val parsed=Rules.parse(raw)
        if(parsed.kind==Rules.Kind.IGNORE) return
        val key=digest(sbn.key+"|"+sbn.postTime+"|"+raw)
        scope.launch {
            val store=Store(Ledger.get(this@CaptureService))
            val msg=when(parsed.kind) {
                Rules.Kind.PROMISE, Rules.Kind.CANCEL -> store.db.withTransaction {
                    // Persist only extracted fields, never the notification body.
                    val extracted=listOf("Merchant: "+parsed.merchant,
                        if(parsed.kind==Rules.Kind.CANCEL) "Cancelled" else "Refund initiated",
                        "₹"+parsed.amount,"order "+parsed.order,"expected "+parsed.date,"destination: "+parsed.destination).joinToString("\n")
                    if(store.q.draft(Draft(key,extracted))==-1L) null else "Review a refund promise".also { store.note(it) }
                }
                else -> {
                    val amount=runCatching { Rules.paise(parsed.amount) }.getOrNull() ?: return@launch
                    if(amount<=0) return@launch
                    store.ingest(Credit(amount=amount,reference=parsed.reference.ifBlank { null },eventKey=key,payer=parsed.payer,
                        destination=parsed.destination,source=sources.getValue(pkg),confirmed=parsed.kind==Rules.Kind.CREDIT,orderRef=parsed.order))
                }
            }
            if(msg!=null && msg!="Already recorded") deliver(this@CaptureService,msg)
        }
    }
}

suspend fun importCsv(store:Store,text:String):String = store.db.withTransaction {
    val rows=Rules.csv(text.removePrefix("\uFEFF"))
    require(rows.size>1) { "CSV needs a header and at least one transaction." }
    val header=rows.first().map { it.lowercase().trim() }
    require("credit" in header || ("amount" in header && "type" in header)) { "Use a credit column, or amount and type columns." }
    var added=0; var duplicates=0; var skipped=0
    val batch=digest(text)
    rows.drop(1).forEachIndexed { index,row ->
        fun col(vararg keys:String)=keys.firstNotNullOfOrNull { key -> row.getOrNull(header.indexOf(key))?.takeIf { it.isNotBlank() } }.orEmpty()
        val kind=col("type").lowercase()
        if(kind in listOf("debit","dr","request","collect","refund initiated","pending") || (col("credit").isBlank() && "credit" in header)) { skipped++;return@forEachIndexed }
        val amount=col("credit","amount")
        val p=Rules.paise(amount)
        if(p==0L) { skipped++;return@forEachIndexed }
        val confirmed=kind in listOf("credit","cr","received") || ("credit" in header && kind.isBlank())
        val msg=store.ingest(Credit(amount=p,reference=col("utr","rrn","transaction_id","reference").ifBlank { null },
            eventKey="csv/"+batch+"/"+index,payer=col("payer_id","upi_id"),destination=col("destination","account"),
            source="CSV",confirmed=confirmed,orderRef=col("order_ref")))
        if(msg=="Already recorded") duplicates++ else added++
    }
    val summary="Imported "+added+" · "+duplicates+" duplicates · "+skipped+" skipped"
    store.note(summary)
    summary
}

