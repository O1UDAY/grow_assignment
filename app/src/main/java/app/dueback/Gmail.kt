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

private fun gmailRequest(path:String,token:String):JSONObject {
    val connection=URL("https://gmail.googleapis.com/gmail/v1/users/me/messages"+path).openConnection() as HttpsURLConnection
    try {
        connection.connectTimeout=10000
        connection.readTimeout=15000
        connection.instanceFollowRedirects=false
        connection.setRequestProperty("Authorization","Bearer "+token)
        check(connection.responseCode==200) { "Gmail declined the request. Check the account, consent and Gmail API setup, then retry." }
        return connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
    } finally { connection.disconnect() }
}

private suspend fun importGmail(store:Store,sender:String,account:String,token:String):String=withContext(Dispatchers.IO) {
    val query=URLEncoder.encode("newer_than:30d from:"+sender,"UTF-8")
    val messages=gmailRequest("?maxResults=25&q="+query,token).optJSONArray("messages")
    var added=0
    var duplicates=0
    val total=messages?.length() ?: 0
    for(i in 0 until total) {
        ensureActive()
        val id=requireNotNull(messages).getJSONObject(i).getString("id")
        require(id.matches(Regex("[A-Za-z0-9_-]+")))
        val message=gmailRequest("/"+id+"?format=full&fields=snippet,payload(headers)",token)
        val headers=message.optJSONObject("payload")?.optJSONArray("headers")
        val subject=(0 until (headers?.length() ?: 0)).map {requireNotNull(headers).getJSONObject(it)}
            .firstOrNull {it.optString("name").equals("Subject",true)}?.optString("value").orEmpty()
        val preview=android.text.Html.fromHtml(message.optString("snippet"),android.text.Html.FROM_HTML_MODE_LEGACY).toString()
        when(captureText(store,(subject+"\n"+preview).take(12000),"Gmail import","gmail/"+digest(account+"|"+id),false)){
            "Already recorded" -> duplicates++
            "No supported transaction found" -> Unit
            else -> added++
        }
    }
    "Checked $total emails. $added to review, $duplicates already recorded."
}

@Composable fun GmailImport(store:Store) {
    Text("Import up to 25 recent emails from one sender. DueBack checks subjects and previews from the last 30 days. Imported payments need review.")
    if(!BuildConfig.GMAIL_IMPORT_CONFIGURED) {
        Section("Account connection unavailable")
        Text("This build has no configured Google OAuth client. Share or paste email text, or select Gmail in Capture sources for new notifications.")
        return
    }
    val ctx=LocalContext.current
    val client=remember(ctx){Identity.getAuthorizationClient(ctx)}
    val scope=rememberCoroutineScope()
    var sender by rememberSaveable { mutableStateOf("") }
    var account by rememberSaveable { mutableStateOf("") }
    var status by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    fun consume(result:AuthorizationResult){
        val token=result.accessToken
        if(token.isNullOrBlank() || gmailScope !in result.grantedScopes){status="Read access was not granted.";busy=false;return}
        scope.launch {
            try { status=importGmail(store,sender.trim(),account,token) }
            catch(e:CancellationException){throw e}
            catch(e:Exception){status="Import interrupted. Check your connection and Google authorization, then retry. Saved items are kept."}
            finally {busy=false}
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
    Button(enabled=!busy,onClick={
        if(!sender.trim().matches(Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+[.][A-Za-z]{2,}"))) status="Enter one sender email address."
        else {
            busy=true;status=""
            runCatching {choose.launch(AccountManager.newChooseAccountIntent(null,null,arrayOf("com.google"),null,null,null,null))}
                .onFailure {busy=false;status="Google account selection unavailable."}
        }
    }){Text("Choose account and import")}
    if(status.isNotEmpty())Text(status)
}
