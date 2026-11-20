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

private val pine=Color(0xff13584d)
private val paper=Color(0xfff8f8f2)
private val ink=Color(0xff19342e)
private val mint=Color(0xffd4eddf)
private val tabs=listOf("Refunds","Groups","Incoming payments")
class MainActivity:ComponentActivity() {
    private var received by mutableStateOf<Pair<String,String>?>(null)
    private var openIncoming by mutableStateOf(0)
    private fun handle(i:Intent?) {
        if(i?.action==Intent.ACTION_SEND) i.getStringExtra(Intent.EXTRA_TEXT)?.take(12000)?.let { received=uid() to it }
        if(i?.getBooleanExtra("incoming",false)==true) openIncoming++
    }
    override fun onNewIntent(intent:Intent){super.onNewIntent(intent);setIntent(intent);handle(intent)}
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState)
        if(savedInstanceState==null) handle(intent)
        window.statusBarColor=android.graphics.Color.rgb(19,88,77)
        setContent { MaterialTheme(colorScheme=lightColorScheme(primary=pine,secondary=pine,background=paper,surface=paper,onSurface=ink)) {
            Home(remember { Store(Ledger.get(this)) },received,openIncoming) { received=null }
        } }
    }
}

@Composable fun Field(label:String,value:String,onChange:(String)->Unit,lines:Int=1) {
    OutlinedTextField(value,onChange,label={Text(label)},modifier=Modifier.fillMaxWidth(),singleLine=lines==1,minLines=lines,shape=RoundedCornerShape(12.dp))
}
@Composable fun Action(label:String,onClick:()->Unit) { Button(onClick=onClick){ Text(label) } }
@Composable fun Link(label:String,onClick:()->Unit) { TextButton(onClick=onClick){ Text(label) } }
@Composable fun Section(title:String){Text(title,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold,color=ink)}
@Composable fun BoxCard(content:@Composable ColumnScope.()->Unit) {
    Surface(color=Color.White,shape=RoundedCornerShape(18.dp),border=BorderStroke(1.dp,Color(0xffdce3db))) {
        Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(5.dp),content=content)
    }
}
@Composable fun Summary(label:String,value:String,detail:String){
    Surface(color=pine,contentColor=Color.White,shape=RoundedCornerShape(22.dp)){
        Column(Modifier.fillMaxWidth().padding(22.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){
            Text(label); Text(value,fontSize=36.sp,fontWeight=FontWeight.SemiBold);Text(detail,color=mint)
        }
    }
}
typealias Commit = (suspend () -> Unit) -> Unit
@Composable fun Home(store:Store,shared:Pair<String,String>?,incoming:Int,consumed:()->Unit) {
    val ctx=LocalContext.current
    val scope=rememberCoroutineScope()
    val snacks=remember { SnackbarHostState() }
    fun say(s:String){ scope.launch { snacks.showSnackbar(s) } }
    var data by remember { mutableStateOf(ViewData()) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(store){ store.q.changes().collect {data=store.state();loaded=true} }
    var tab by rememberSaveable {mutableIntStateOf(0)}
    var form by rememberSaveable { mutableStateOf("") }
    var raw by rememberSaveable { mutableStateOf("") }
    var selected by rememberSaveable { mutableStateOf("") }
    var draft by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    fun go(f:String,id:String="",message:String=""){form=f;selected=id;raw=message;draft=""}
    fun route(s:String){
        val p=Rules.parse(s)
        when(p.kind){
            Rules.Kind.CANCEL,Rules.Kind.PROMISE->{tab=0;go("refund",message=s)}
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
    BackHandler(form.isNotEmpty()){if(!busy)go("")}
    Scaffold(containerColor=paper,snackbarHost={SnackbarHost(snacks)},bottomBar={
        if(form.isEmpty()) NavigationBar(containerColor=Color.White){
            tabs.forEachIndexed { i,name->NavigationBarItem(selected=tab==i,onClick={tab=i},icon={Text(listOf("↩","◎","↓")[i],fontSize=23.sp)},label={Text(name,maxLines=1,fontSize=11.sp)}) }
        }
    }){padding ->
        key(tab,form,selected,raw){
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal=20.dp,vertical=16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                Row(verticalAlignment=Alignment.CenterVertically){Text("DueBack",color=pine,fontSize=18.sp,fontWeight=FontWeight.Bold)
                    Spacer(Modifier.weight(1f)); if(form.isNotEmpty()) Link("Close"){if(!busy)go("")}
                }
                Text(when(form){"refund"->"Review refund";"credit"->"Review incoming";"group"->"New group";"bill"->"Add a bill";"paste"->"Paste a message";"match"->"Allocate payment";"access"->"Alert settings";"feed"->"Activity";else->tabs[tab]},fontWeight=FontWeight.Bold,fontSize=32.sp,color=ink)
                when(form){
                    "paste"->{var message by rememberSaveable {mutableStateOf("")};Field("Cancellation, refund or payment text",message,{message=it},4);Action("Review message"){route(message)}}
                    "refund"->RefundForm(raw){d->save { store.refund(d,draft) }}
                    "credit"->CreditForm(raw){c->save { val msg=store.ingest(c);say(msg);deliver(ctx,msg) }}
                    "group"->GroupForm {name,people->save {store.group(name,people)}}
                    "bill"->data.circles.find {it.id==selected}?.let {g->BillForm(g,data.people.filter {it.circle==g.id}){name,total,shares->save {store.bill(g,name,total,shares)}}}
                    "match"->data.credits.find {it.id==selected}?.let {c->MatchForm(data,c){d,n,split->save {store.allocate(c.id,d.id,n,split);deliver(ctx,"Payment allocated to "+data.label(d))}}}
                    "access"->AccessForm(::say)
                    "feed"->{if(data.feed.isEmpty())Text("Your saved refunds, bills and matches will appear here.");data.feed.forEach {BoxCard{Text(it.text)}}}
                    else-> if(loaded){
                        when(tab){
                            0->{
                                val refunds=data.dues.filter {it.bill.isEmpty()}
                                Summary("Outstanding refunds",money(refunds.sumOf {data.left(it)}),money(refunds.sumOf {it.amount-data.left(it)})+" received")
                                Row {Action("Add refund"){go("refund")};Link("Paste text"){go("paste")}}
                                data.drafts.forEach { d->BoxCard {Text("Refund message to review");Text(d.message);Row{Link("Review"){go("refund",message=d.message);draft=d.id};Link("Dismiss"){save {store.db.withTransaction {store.q.dropDraft(d.id);store.note("Dismissed refund message")} }}}} }
                                if(refunds.isEmpty()) Text("Expecting money back? Add the promise here and track each credit as it arrives.")
                                refunds.forEach {d->BoxCard{
                                    Section(d.name);val left=data.left(d)
                                    Text(if(left==0L)"Received in full" else if(d.date.isNotBlank() && d.date<LocalDate.now().toString()) "Overdue · "+money(left) else money(left)+" outstanding",color=pine)
                                    Text("Promised "+money(d.amount)+"   ·   Received "+money(d.amount-left))
                                    listOf(d.orderRef,d.date,d.destination).filter {it.isNotBlank()}.takeIf {it.isNotEmpty()}?.let {Text(it.joinToString(" · "))}
                                    data.allocations.filter {it.due==d.id}.forEach {a->val c=data.credits.first {it.id==a.credit};Text(money(a.amount)+" → "+c.destination.ifBlank {"Unspecified destination"})}
                                }}
                            }
                            1->{
                                val shares=data.dues.filter {it.bill.isNotEmpty()}
                                Summary("Others owe you",money(shares.sumOf {data.left(it)}),data.circles.size.toString()+" groups")
                                Action("Create group"){go("group")}
                                if(data.circles.isEmpty())Text("Create a group, add the bill you paid and track each person's share.")
                                data.circles.forEach {g->BoxCard{
                                    Section(g.name);Text(data.people.filter {it.circle==g.id}.joinToString(", ",prefix="You, "){it.name})
                                    Link("Add bill"){go("bill",g.id)}
                                    data.bills.filter {it.circle==g.id}.forEach {b-> HorizontalDivider();Section(b.name)
                                        Text("You paid "+money(b.total)+" · Your share "+money(b.mine))
                                        shares.filter {it.bill==b.id}.forEach {d->Text(d.name+" · "+money(d.amount-data.left(d))+" paid · "+money(data.left(d))+" left")}
                                    }
                                }}
                            }
                            2->{
                                Summary("Available to allocate",money(data.credits.filter {it.confirmed}.sumOf {data.free(it)}),data.credits.count {!it.confirmed}.toString()+" awaiting confirmation")
                                Row{Action("Add credit"){go("credit")};Link("Paste text"){go("paste")}}
                                Row{Link("Import CSV"){file.launch(arrayOf("text/*","application/csv","application/vnd.ms-excel","application/octet-stream"))};Link("Alert settings"){go("access")}}
                                Text("CSV columns: amount, type, utr, payer_id, destination, order_ref. Use rupees. A credit column can replace amount and type.",style=MaterialTheme.typography.bodySmall)
                                if(data.credits.isEmpty())Text("Incoming credits from any source appear here.")
                                data.credits.forEach {c->BoxCard{
                                    Section(money(c.amount)+" · "+c.source);Text(listOf(c.payer,c.reference.orEmpty(),c.destination).filter {it.isNotBlank()}.joinToString(" · "))
                                    if(!c.confirmed){ Text("Verify this is a separate incoming credit against your statement.");Row{Link("Confirm credit"){save {store.review(c,true)}};Link("Dismiss"){save {store.review(c,false)}}} }
                                    else if(data.free(c)>0){Text(money(data.free(c))+" available");Link(if(data.candidates(c).size>1)"Choose bill or refund" else "Allocate"){go("match",c.id)}}
                                    data.allocations.filter {it.credit==c.id}.forEach {a->
                                        Text(money(a.amount)+" → "+data.label(data.dues.first {it.id==a.due}));Text(a.reason,style=MaterialTheme.typography.bodySmall);Link("Undo match"){save {store.undo(a)}}
                                    }
                                }}
                            }
                        }
                        if(data.circles.isEmpty() && data.dues.isEmpty() && data.credits.isEmpty() && data.drafts.isEmpty()) Link("Try demo"){save {store.demo()}}
                        Link("Activity feed"){go("feed")}
                    }
                }
                if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}


