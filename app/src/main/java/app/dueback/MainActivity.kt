package app.dueback

import androidx.room.withTransaction
import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import java.time.LocalDate

private val pine=Color(0xff145948)
private val paper=Color(0xfffcfbf7)
private val ink=Color(0xff192923)
private val divider=Color(0xffd3d9d2)
private val tabs=listOf("Refunds","Groups","Payments")
class MainActivity:ComponentActivity() {
    private var resumeTick by mutableIntStateOf(0)
    override fun onResume() { super.onResume(); resumeTick++ }
    private var received by mutableStateOf<Pair<String,String>?>(null)
    private var openIncoming by mutableStateOf(0)
    private fun handle(i:Intent?) {
        if(i?.action==Intent.ACTION_SEND) i.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.take(12000)?.let { received=uid() to it }
        if(i?.getBooleanExtra("incoming",false)==true) openIncoming++
    }
    override fun onNewIntent(intent:Intent){super.onNewIntent(intent);setIntent(intent);handle(intent)}
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState)
        if(savedInstanceState==null) handle(intent)
        window.statusBarColor=android.graphics.Color.rgb(19,88,77)
        setContent { MaterialTheme(colorScheme=lightColorScheme(primary=pine,secondary=pine,background=paper,surface=paper,onSurface=ink)) {
            Home(remember { Store(Ledger.get(this)) },received,openIncoming,resumeTick) { received=null }
        } }
    }
}

@Composable fun Field(label:String,value:String,onChange:(String)->Unit,lines:Int=1,keyboard:KeyboardType=KeyboardType.Text) {
    val focus=LocalFocusManager.current
    OutlinedTextField(value,onChange,keyboardOptions=KeyboardOptions(keyboardType=keyboard,imeAction=if(lines==1) ImeAction.Done else ImeAction.Default),
        keyboardActions=KeyboardActions(onDone={focus.clearFocus()}),label={Text(label)},modifier=Modifier.fillMaxWidth(),singleLine=lines==1,minLines=lines,shape=RoundedCornerShape(6.dp))
}
@Composable fun Action(label:String,onClick:()->Unit) { Button(onClick=onClick,shape=RoundedCornerShape(6.dp)){ Text(label) } }
@Composable fun Link(label:String,onClick:()->Unit) { TextButton(onClick=onClick,shape=RoundedCornerShape(6.dp)){ Text(label) } }
@Composable fun Section(title:String){Text(title,modifier=Modifier.semantics { heading() },style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold,color=ink)}
@Composable fun BoxCard(content:@Composable ColumnScope.()->Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical=4.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        HorizontalDivider(color=divider)
        Spacer(Modifier.height(4.dp))
        content()
    }
}
@Composable private fun ActionRow(content:@Composable ()->Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if(maxWidth<340.dp || androidx.compose.ui.platform.LocalDensity.current.fontScale>=1.3f)
            Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(2.dp)){content()}
        else Row(horizontalArrangement=Arrangement.spacedBy(4.dp)){content()}
    }
}
@Composable fun Summary(label:String,value:String,detail:String){
    Column(Modifier.fillMaxWidth().padding(vertical=8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
        Text(label,style=MaterialTheme.typography.labelLarge)
        Text(value,style=MaterialTheme.typography.displaySmall,fontWeight=FontWeight.SemiBold,color=pine)
        Text(detail,style=MaterialTheme.typography.bodyMedium)
    }
}
typealias Commit = (suspend () -> Unit) -> Unit
@Composable fun Home(store:Store,shared:Pair<String,String>?,incoming:Int,resumeTick:Int,consumed:()->Unit) {
    val ctx=LocalContext.current
    val scope=rememberCoroutineScope()
    val snacks=remember { SnackbarHostState() }
    fun say(s:String){ scope.launch { snacks.showSnackbar(s) } }
    var data by remember { mutableStateOf(ViewData()) }
    var loaded by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var loadError by remember { mutableStateOf(false) }
    LaunchedEffect(store,retry){
        loadError=false
        try { store.q.changes().collect {data=store.state();loaded=true} }
        catch(e:CancellationException){throw e}
        catch(e:Exception){loadError=true}
    }
    var tab by rememberSaveable {mutableIntStateOf(0)}
    var form by rememberSaveable { mutableStateOf(if(prefs(ctx).getBoolean("introduced", false)) "" else "welcome") }
    var raw by rememberSaveable { mutableStateOf("") }
    var selected by rememberSaveable { mutableStateOf("") }
    var draft by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    fun go(f:String,id:String="",message:String=""){if(form=="welcome")prefs(ctx).edit().putBoolean("introduced",true).apply();form=f;selected=id;raw=message;draft=""}
    fun route(s:String){
        val p=Rules.parse(s)
        when(p.kind){
            Rules.Kind.CANCEL,Rules.Kind.PROMISE->{tab=0;go("refund",message=s);draft=if(p.reference.isNotBlank()) "refund-reference/"+digest(p.reference.uppercase(java.util.Locale.ROOT)) else uid()}
            Rules.Kind.CREDIT,Rules.Kind.REVIEW->{tab=2;go("credit",message=s)}
            else->{go("");say("No refund promise or incoming payment identified. You can add one manually.")}
        }
    }
    LaunchedEffect(shared?.first){if(shared!=null){route(shared.second);consumed()}}
    LaunchedEffect(incoming){if(incoming>0){tab=2;go("")}}
    val save:Commit={ job -> if(!busy){busy=true;scope.launch {
        try { job();go("") } catch(e:Exception){if(e is CancellationException) throw e;say(e.message?:"Could not save.")}
        finally {busy=false}
    }} }
    val file=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){ uri ->
        if(uri!=null) save {
            val content=withContext(Dispatchers.IO) {
                ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                    val buf=CharArray(8192);val out=StringBuilder()
                    while(true){ val n=reader.read(buf);if(n<0) break;require(out.length+n<=4_000_000){"Choose a CSV smaller than 4 MB."};out.append(buf,0,n) }
                    out.toString()
                } ?: error("File could not be opened.")
            }
            val msg=importCsv(store,content);say(msg);deliver(ctx,msg)
        }
    }
    val textFile=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){ uri ->
        if(uri!=null) scope.launch {
            try {
                val message=withContext(Dispatchers.IO){
                    requireNotNull(ctx.contentResolver.openInputStream(uri)).bufferedReader().use { reader ->
                        val buffer=CharArray(12001)
                        var used=0
                        while(used<buffer.size){
                            val n=reader.read(buffer,used,buffer.size-used)
                            if(n<0)break
                            used+=n
                        }
                        require(used in 1..12000){"Choose a text file containing one message, up to 12,000 characters."}
                        String(buffer,0,used)
                    }
                }
                route(message)
            } catch(e:CancellationException){throw e}
              catch(e:Exception){say(e.message ?: "Could not read the text file. Paste its contents instead.")}
        }
    }
    BackHandler(form.isNotEmpty()){if(!busy)go("")}
    Scaffold(containerColor=paper,snackbarHost={SnackbarHost(snacks)},bottomBar={
        if(form.isEmpty()) Surface(color=paper,tonalElevation=2.dp) {
            Row(Modifier.fillMaxWidth().navigationBarsPadding()) {
                tabs.forEachIndexed { i,name ->
                    Column(Modifier.weight(1f).selectable(selected=tab==i,role=Role.Tab,onClick={tab=i})
                        .heightIn(min=54.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                        HorizontalDivider(thickness=if(tab==i) 3.dp else 1.dp,color=if(tab==i) pine else Color.LightGray)
                        Spacer(Modifier.height(12.dp))
                        Text(name,color=if(tab==i) pine else ink,fontWeight=if(tab==i) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
        }
    }){padding ->
        key(tab,form,selected,raw){
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding().verticalScroll(rememberScrollState()).padding(horizontal=20.dp,vertical=16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                Row(verticalAlignment=Alignment.CenterVertically){Text("DueBack",color=pine,fontSize=18.sp,fontWeight=FontWeight.Bold)
                    Spacer(Modifier.weight(1f)); if(form.isNotEmpty()) Link(if(form=="welcome") "Skip" else "Close"){if(!busy)go("")} else Link("Sources"){go("access")}
                }
                Text(when(form){"refund"->if(raw.isEmpty()) "Add refund" else "Review refund";"credit"->if(raw.isEmpty()) "Add payment" else "Review payment";"group"->"New group";"bill"->"Add a bill";"paste"->"Paste a message";"match"->"Allocate payment";"welcome"->"Set up DueBack";"paymentDetail"->"Payment details";"refundDetail"->"Refund details";"access"->"Sources & connections";"diagnostics"->"Capture diagnostics";"email"->"Import from Gmail";"feed"->"Activity";else->tabs[tab]},modifier=Modifier.semantics { heading() },style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.SemiBold,color=ink)
                when(form){
                    "paste"->{var message by rememberSaveable {mutableStateOf("")};Field("Cancellation, refund or payment text",message,{message=it},4);Action("Review message"){route(message)};Link("Open text file"){textFile.launch(arrayOf("text/plain"))}}
                    "refund"->RefundForm(raw){d->save { store.refund(d.copy(origin=if(raw.isBlank()) "Manual" else if(data.drafts.any { it.id==draft }) raw.lineSequence().lastOrNull { it.startsWith("Source: ") }?.removePrefix("Source: ") ?: "Notification" else "Shared text"),draft) }}
                    "credit"->CreditForm(raw){c->save { val item=data.drafts.firstOrNull {it.id==draft && it.payment}
                        val msg=store.db.withTransaction {
                            val credit=if(item==null)c else c.copy(source=item.message.substringAfterLast("Source: "),sourceTime=item.sourceTime,sourceZone=item.sourceZone,confirmed=false,eventKey="review/"+item.id)
                            store.ingest(credit).also {if(item!=null)store.q.dropDraft(item.id)}
                        };say(msg);deliver(ctx,msg) }}
                    "group"->GroupForm {name,people->save {store.group(name,people)}}
                    "bill"->data.circles.find {it.id==selected}?.let {g->BillForm(g,data.people.filter {it.circle==g.id}){name,total,shares->save {store.bill(g,name,total,shares)}}}
                    "match"->data.credits.find {it.id==selected}?.let {c->MatchForm(data,c){d,n,split->save {store.allocate(c.id,d.id,n,split);deliver(ctx,"Payment allocated to "+data.label(d))}}}
                    "paymentDetail"->data.credits.find {it.id==selected}?.let { c ->
                        Summary(if(c.confirmed)"Confirmed credit" else "Awaiting confirmation",money(c.amount),c.source)
                        Text(originalDate(c.sourceTime,c.sourceZone))
                        if(data.allocations.none {it.credit==c.id}) Text("No allocation. This payment has not settled a balance.")
                        if(c.payer.isNotBlank()) Text("From "+c.payer)
                        if(c.destination.isNotBlank()) Text("Received in "+c.destination)
                        c.reference?.let {Text("Transaction ID "+it)}
                        if(c.orderRef.isNotBlank()) Text("Order "+c.orderRef)
                        if(!c.confirmed){ Text(if(data.credits.any {it.id!=c.id && it.amount==c.amount}) "Another payment has this amount. Confirm only if your statement shows a separate credit." else "Check your statement before confirming this credit.");ActionRow{Link("Confirm credit"){save {store.review(c,true)}};Link("Dismiss"){save {store.review(c,false)}}} }
                        else if(data.free(c)>0){Text(money(data.free(c))+" available");Link(if(data.candidates(c).size>1)"Choose bill or refund" else "Allocate"){go("match",c.id)}}
                        data.allocations.filter {it.credit==c.id}.forEach {a->
                            Text(money(a.amount)+" → "+data.label(data.dues.first {it.id==a.due}));Text(a.reason,style=MaterialTheme.typography.bodySmall);Link("Undo match"){save {store.undo(a)}}
                        }
                    }
                    "refundDetail"->data.dues.find {it.id==selected}?.let { d ->
                        Summary(d.name,money(data.left(d))+" outstanding","Promised "+money(d.amount))
                        Text("Source · "+d.origin);Text(originalDate(d.sourceTime,d.sourceZone))
                        Text("Confirmed receipts · "+money(d.amount-data.left(d)))
                        if(d.date.isNotBlank()) Text("Expected "+d.date)
                        if(d.orderRef.isNotBlank()) Text("Order "+d.orderRef)
                        data.allocations.filter {it.due==d.id}.forEach {a ->
                            val credit=data.credits.first {it.id==a.credit}
                            BoxCard {Section(money(a.amount)+" via "+credit.source);Text(a.reason);Link("View payment"){go("paymentDetail",credit.id)}}
                        }
                    }
                    "welcome"->{
                        Text("Keep track of refunds you expect and repayments from people who owe you.")
                        Section("How money gets recorded")
                        Text("Add a refund or payment yourself, share or paste message text, or import a statement CSV. For new alerts, choose apps and enable notification access.")
                        Action("Choose capture sources"){go("access")}
                        Link("Continue with manual entry"){go("")}
                    }
                    "access"->AccessForm(resumeTick,::say,{go("diagnostics")},{go("email")}) {
                        save {
                            prefs(ctx).edit().putBoolean("paused",true).remove("eventShape").remove("detailOutcome").remove("eventTime").remove("eventSource").remove("outcome")
                                .remove("processingFailed").remove("testResult").remove("probe").apply()
                            store.deleteImports()
                        }
                    }
                    "diagnostics"->Diagnostics(resumeTick){go("access")}
                    "email"->GmailImport(store)
                    "feed"->{if(data.feed.isEmpty())Text("Your saved refunds, bills and matches will appear here.");data.feed.forEach {BoxCard{Text(it.text)}}}
                    else-> if(loadError){Text("Couldn’t load your ledger.");Action("Retry"){retry++}} else if(!loaded){LinearProgressIndicator(Modifier.fillMaxWidth())} else {
                        when(tab){
                            0->{
                                val refunds=data.dues.filter {it.bill.isEmpty()}
                                Summary("Outstanding refunds",money(refunds.sumOf {data.left(it)}),money(refunds.sumOf {it.amount-data.left(it)})+" received")
                                ActionRow {Action("Add refund"){go("refund")};Link("Paste text"){go("paste")}}
                                data.drafts.filterNot { it.payment }.forEach { d->BoxCard {Text("Refund message to review");Text("Check the merchant and amount before saving.");ActionRow{Link("Review"){go("refund",message=d.message);draft=d.id};Link("Dismiss"){save {store.db.withTransaction {store.q.dropDraft(d.id);store.note("Dismissed refund message")} }}}} }
                                if(refunds.isEmpty()) Text("Expecting money back? Add the promise here and track each credit as it arrives.")
                                refunds.forEach {d->BoxCard{
                                    Section(d.name);val left=data.left(d)
                                    Text(if(left==0L)"Received in full" else if(d.date.isNotBlank() && d.date<LocalDate.now().toString()) "Overdue · "+money(left) else money(left)+" outstanding",color=pine)
                                    Text("Promised "+money(d.amount)+"   ·   Received "+money(d.amount-left));Text("Recorded via "+d.origin,style=MaterialTheme.typography.bodySmall)
                                    Link("View refund"){go("refundDetail",d.id)}
                                }}
                            }
                            1->{
                                val shares=data.dues.filter {it.bill.isNotEmpty()}
                                Summary("Group repayments outstanding",money(shares.sumOf {data.left(it)}),money(shares.sumOf {it.amount-data.left(it)})+" repaid")
                                Action("Create group"){go("group")}
                                if(data.circles.isEmpty())Text("Create a group, add the bill you paid and track each person's share.")
                                data.circles.forEach {g->BoxCard{
                                    Section(g.name);Text(data.people.filter {it.circle==g.id}.joinToString(", ",prefix="You, "){it.name})
                                    Link("Add bill"){go("bill",g.id)}
                                    data.bills.filter {it.circle==g.id}.forEach {b-> HorizontalDivider();Section(b.name)
                                        Text("You paid "+money(b.total)+" · Your share "+money(b.mine))
                                        shares.filter {it.bill==b.id}.forEach {d->BoxCard {
                                            Section(d.name);Text(money(d.amount)+" share · "+money(data.left(d))+" left")
                                            data.allocations.filter {it.due==d.id}.forEach {a->
                                                val credit=data.credits.first {it.id==a.credit}
                                                Text(money(a.amount)+" received via "+credit.source+" · "+a.reason)
                                            }
                                        }}
                                    }
                                }}
                            }
                            2->{
                                Summary("Money received",money(data.credits.filter {it.confirmed}.sumOf {it.amount}),money(data.credits.filter {it.confirmed}.sumOf {data.free(it)})+" available to allocate · "+(data.credits.count {!it.confirmed}+data.drafts.count {it.payment})+" to review")
                                ActionRow{Action("Add credit"){go("credit")};Link("Paste text"){go("paste")}}
                                ActionRow{Link("Import CSV"){file.launch(arrayOf("text/*","application/csv","application/vnd.ms-excel","application/octet-stream"))};Link("Capture diagnostics"){go("diagnostics")}}
                                var showCsv by rememberSaveable { mutableStateOf(false) }
                                Link(if(showCsv)"Hide CSV format" else "CSV format"){showCsv=!showCsv}
                                if(showCsv) Text("Use credit, or amount and type. Optional columns include utr, payer_id, destination and order_ref. Enter amounts in rupees.",style=MaterialTheme.typography.bodySmall)
                                data.drafts.filter {it.payment}.forEach { d->BoxCard {
                                    Section("Payment needs an amount");Text("Check the statement before saving.")
                                    Link("Review payment"){go("credit",message=d.message);draft=d.id}
                                    Link("Dismiss"){save { store.db.withTransaction {store.q.dropDraft(d.id);store.note("Dismissed payment draft")} }}
                                }}
                                if(data.credits.isEmpty() && data.drafts.none {it.payment})Text("Incoming credits from any source appear here.")
                                data.credits.forEach {c->BoxCard{
                                    Section(money(c.amount)); Text("Recorded via "+c.source)
                                    Text(if(c.confirmed) "Confirmed · "+money(data.free(c))+" available" else "Awaiting review")
                                    Link("View payment"){go("paymentDetail",c.id)}
                                }}
                            }
                        }
                        Link("Activity feed"){go("feed")}
                    }
                }
                if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}



private fun originalDate(time:Long?,zone:String?):String {
    if(time==null) return "Original date unavailable"
    val location=runCatching {java.time.ZoneId.of(zone)}.getOrDefault(java.time.ZoneOffset.UTC)
    return java.time.Instant.ofEpochMilli(time).atZone(location)
        .format(java.time.format.DateTimeFormatter.ofPattern("d MMM uuuu, HH:mm z"))
}
