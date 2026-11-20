package app.dueback

import android.accounts.Account
import android.accounts.AccountManager
import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection

private const val gmailScope="https://www.googleapis.com/auth/gmail.readonly"

private class GmailFailure(val code:Int):Exception()
private suspend fun gmailRequest(path:String,token:String):JSONObject {
    for(attempt in 0..2) {
        currentCoroutineContext().ensureActive()
        val connection=URL("https://gmail.googleapis.com/gmail/v1/users/me/messages"+path).openConnection() as HttpsURLConnection
        try {
            connection.connectTimeout=10000
            connection.readTimeout=15000
            connection.instanceFollowRedirects=false
            connection.setRequestProperty("Authorization","Bearer "+token)
            val code=connection.responseCode
            if(code==200) return connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
            if(attempt==2 || (code!=429 && code !in 500..599)) throw GmailFailure(code)
        } catch(e:java.io.IOException){if(attempt==2) throw e}
        finally {connection.disconnect()}
        delay(1000L shl attempt)
    }
    error("Retry limit reached")
}
private fun header(part:JSONObject,name:String):String {
    val headers=part.optJSONArray("headers") ?: return ""
    for(i in 0 until headers.length()){
        val h=headers.getJSONObject(i)
        if(h.optString("name").equals(name,true)) return h.optString("value")
    }
    return ""
}
internal suspend fun emailBody(part:JSONObject,load:suspend(String)->String):String {
    if(part.optString("filename").isNotEmpty() || header(part,"Content-Disposition").startsWith("attachment",true)) return ""
    val type=part.optString("mimeType").lowercase()
    val children=part.optJSONArray("parts")
    if(children!=null) {
        val parts=(0 until children.length()).map {children.getJSONObject(it)}
        if(type=="multipart/alternative") {
            for(child in parts.sortedBy {it.optString("mimeType")!="text/plain"}) {
                val text=emailBody(child,load)
                if(text.isNotBlank()) return text
            }
            return ""
        }
        val result=StringBuilder()
        for(child in parts) {
            if(result.length>=24000)break
            result.appendLine(emailBody(child,load))
        }
        return result.toString().take(24000)
    }
    if(type!="text/plain" && type!="text/html") return ""
    val body=part.optJSONObject("body") ?: return ""
    if(body.optInt("size")>1048576) return ""
    val data=body.optString("data").ifBlank {
        val id=body.optString("attachmentId")
        if(id.isBlank()) return ""
        load(id)
    }
    val bytes=android.util.Base64.decode(data,android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP)
    val charsetName=Regex("""(?i)charset=["']?([A-Za-z0-9_-]+)""").find(header(part,"Content-Type"))?.groupValues?.get(1)
    val charset=runCatching {charsetName?.let { java.nio.charset.Charset.forName(it) }}.getOrNull() ?: Charsets.UTF_8
    val decoded=bytes.toString(charset)
    return (if(type=="text/html") android.text.Html.fromHtml(decoded,android.text.Html.FROM_HTML_MODE_LEGACY).toString() else decoded).take(24000)
}
private data class Counts(val checked:Int=0,val added:Int=0,val duplicate:Int=0,val skipped:Int=0){
    override fun toString()="$checked checked · $added to review · $duplicate duplicates · $skipped skipped"
}
private suspend fun importGmail(store:Store,sender:String,account:String,token:String,older:Boolean,progress:suspend(Counts)->Unit) = withContext(Dispatchers.IO) {
    val query=URLEncoder.encode((if(older) "" else "newer_than:30d ")+"from:"+sender,"UTF-8")
    var page=""
    var counts=Counts()
    do {
        val next=if(page.isBlank()) "" else "&pageToken="+URLEncoder.encode(page,"UTF-8")
        val listing=gmailRequest("?maxResults=50&q="+query+next,token)
        val messages=listing.optJSONArray("messages")
        for(i in 0 until (messages?.length() ?: 0)) {
            ensureActive()
            val id=requireNotNull(messages).getJSONObject(i).getString("id")
            require(id.matches(Regex("[A-Za-z0-9_-]+")))
            val result=try {
                val message=gmailRequest("/$id?format=full",token)
                val payload=message.getJSONObject("payload")
                val date=runCatching {java.time.ZonedDateTime.parse(header(payload,"Date").substringBefore(" ("),
                    java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)}.getOrNull()
                val time=date?.toInstant()?.toEpochMilli() ?: message.getString("internalDate").toLong()
                val zone=date?.zone ?: java.time.ZoneOffset.UTC
                val body=emailBody(payload){partId -> gmailRequest("/$id/attachments/"+android.net.Uri.encode(partId),token).optString("data")}
                ensureActive()
                captureText(store,header(payload,"Subject")+"\n"+body,"Gmail import","gmail/"+digest(account+"|"+id),time,zone)
            } catch(e:GmailFailure){if(e.code==404) "No supported transaction found" else throw e}
            counts=when(result){
                "Already recorded" -> counts.copy(duplicate=counts.duplicate+1)
                "No supported transaction found" -> counts.copy(skipped=counts.skipped+1)
                else -> counts.copy(added=counts.added+1)
            }.copy(checked=counts.checked+1)
            withContext(NonCancellable + Dispatchers.Main.immediate){progress(counts)}
        }
        page=listing.optString("nextPageToken")
    } while(page.isNotBlank())
}
@Composable fun GmailImport(store:Store) {
    Text("Choose one sender and a date range. Only extracted transaction fields are saved.")
    if(!BuildConfig.GMAIL_IMPORT_CONFIGURED) {
        Section("Account connection unavailable")
        Text("This build has no configured Google OAuth client. Share or paste email text, or select Gmail in Capture sources for new notifications.")
        return
    }
    val ctx=LocalContext.current
    if(com.google.android.gms.common.GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(ctx)!=com.google.android.gms.common.ConnectionResult.SUCCESS){
        Text("Google Play services is unavailable. Update it or share email text.");return
    }
    val client=remember(ctx){Identity.getAuthorizationClient(ctx)}
    val scope=rememberCoroutineScope()
    var sender by rememberSaveable { mutableStateOf("") }
    var older by rememberSaveable {mutableStateOf(false)}
    var account by rememberSaveable { mutableStateOf("") }
    var status by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var importJob by remember {mutableStateOf<Job?>(null)}
    var counts by remember {mutableStateOf(Counts())}
    fun consume(result:AuthorizationResult){
        val token=result.accessToken
        if(token.isNullOrBlank() || gmailScope !in result.grantedScopes){status="Read access was not granted.";busy=false;return}
        counts=Counts()
        importJob=scope.launch {
            try {
                importGmail(store,sender.trim(),account,token,older){counts=it}
                status="Import complete. $counts"
            } catch(e:CancellationException){status="Cancelled. $counts";throw e}
              catch(e:GmailFailure){
                  status=when(e.code){
                      401 -> {
                          withContext(Dispatchers.IO){runCatching {com.google.android.gms.auth.GoogleAuthUtil.clearToken(ctx,token)}}
                          "Authorization expired. Choose the account again."
                      }
                      403 -> "Access denied. Check consent, Gmail API and test-user access."
                      else -> "Gmail unavailable after retries. Retry the import."
                  }+" $counts"
              }
              catch(e:Exception){status="Import interrupted. Retry to resume safely. $counts"}
            finally {busy=false;importJob=null}
        }
    }
    val consent=rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()){ result ->
        if(result.resultCode==Activity.RESULT_OK) runCatching {consume(client.getAuthorizationResultFromIntent(result.data))}
            .onFailure {busy=false;status="Authorization failed. Try connecting again."}
        else {busy=false;status="Import cancelled."}
    }
    val choose=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()){result->
        val name=result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
        if(result.resultCode!=Activity.RESULT_OK || name.isNullOrBlank()){busy=false;status="No account selected."}
        else {
            account=name
            val request=AuthorizationRequest.builder().setAccount(Account(name,"com.google"))
                .setRequestedScopes(listOf(Scope(gmailScope))).build()
            client.authorize(request).addOnSuccessListener { auth ->
                runCatching {
                    if(auth.hasResolution()) consent.launch(IntentSenderRequest.Builder(requireNotNull(auth.pendingIntent)).build())
                    else consume(auth)
                }.onFailure {busy=false;status="Could not open Google consent. Try again."}
            }.addOnFailureListener {busy=false;status="Connection unavailable. Check Google Play services and OAuth setup."}
        }
    }
    Text("Google will ask for read-only Gmail access. The sender filter limits this import. Google grants the permission account-wide.")
    if(!busy) Field("Sender email address",sender,{sender=it}) else LinearProgressIndicator()
    if(!busy) {
        Text(if(older) "Range · All matching emails" else "Range · Last 30 days")
        Link(if(older) "Use last 30 days" else "Include older emails"){older=!older}
    }
    Button(enabled=!busy,onClick={
        if(!sender.trim().matches(Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+[.][A-Za-z]{2,}"))) status="Enter one sender email address."
        else {
            busy=true;status=""
            runCatching {choose.launch(AccountManager.newChooseAccountIntent(null,null,arrayOf("com.google"),null,null,null,null))}
                .onFailure {busy=false;status="Google account selection unavailable."}
        }
    }){Text("Choose account and import")}
    if(importJob!=null){Text(counts.toString());Link("Cancel import"){importJob?.cancel()}}
    if(status.isNotEmpty())Text(status)
}
