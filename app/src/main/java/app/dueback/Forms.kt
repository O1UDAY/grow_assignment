package app.dueback

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.math.BigDecimal
import java.time.LocalDate

@Composable private fun Check(label:String,selected:Boolean,change:(Boolean)->Unit){
    Row(Modifier.fillMaxWidth().toggleable(value=selected,role=androidx.compose.ui.semantics.Role.Checkbox,onValueChange=change).heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically){Checkbox(selected,null);Spacer(Modifier.width(12.dp));Text(label)}
}
@Composable private fun Save(label:String,primary:Boolean=true,action:()->Unit){
    var error by rememberSaveable {mutableStateOf("")}
    if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error)
    val submit = { try { error="";action() } catch(e:Exception){error=e.message?:"Check your entries."} }
    if(primary) Action(label,submit) else Link(label,submit)
}
private fun positive(s:String)=Rules.paise(s).also {require(it>0){"Enter an amount greater than zero."}}
@Composable fun RefundForm(raw:String,save:(Due)->Unit) {
    val p=remember(raw){Rules.parse(raw)}
    var merchant by rememberSaveable {mutableStateOf(p.merchant)}
    var amount by rememberSaveable {mutableStateOf(if(p.kind==Rules.Kind.CANCEL) "" else p.amount)}
    var ref by rememberSaveable {mutableStateOf(p.order)}
    var date by rememberSaveable {mutableStateOf(p.date)}
    var dest by rememberSaveable {mutableStateOf(p.destination)}
    var promised by rememberSaveable {mutableStateOf(p.kind!=Rules.Kind.CANCEL)}
    if(raw.isNotBlank()) SourcePreview(raw)
    if(p.kind==Rules.Kind.CANCEL) Check("Was a refund promised?",promised){promised=it}
    Field("Merchant",merchant,{merchant=it});Field("Promised amount (₹)",amount,{amount=it},keyboard=KeyboardType.Decimal)
    Field("Order reference (optional)",ref,{ref=it});Field("Expected date (optional, YYYY-MM-DD)",date,{date=it})
    Field("Expected destination (optional)",dest,{dest=it})
    Save("Save refund"){require(promised){"Confirm that a refund was promised first."};require(merchant.isNotBlank()){"Enter a merchant."}
        require(date.isBlank() || runCatching { LocalDate.parse(date.trim()) }.isSuccess){"Use YYYY-MM-DD for the expected date."}
        save(Due(name=merchant.trim(),amount=positive(amount),orderRef=ref.trim(),date=date.trim(),destination=dest.trim()))}
}
@Composable fun CreditForm(raw:String,save:(Credit)->Unit) {
    val p=remember(raw){Rules.parse(raw)}
    var amount by rememberSaveable {mutableStateOf(p.amount)}
    var ref by rememberSaveable {mutableStateOf(p.reference)}
    var payer by rememberSaveable {mutableStateOf(p.payer)}
    var dest by rememberSaveable {mutableStateOf(p.destination)}
    var order by rememberSaveable {mutableStateOf(p.order)}
    var confirmed by rememberSaveable {mutableStateOf(raw.isEmpty())}
    if(raw.isNotBlank()) SourcePreview(raw)
    Field("Amount received (₹)",amount,{amount=it},keyboard=KeyboardType.Decimal);Field("UTR or transaction ID (optional)",ref,{ref=it})
    Field("Sender UPI ID or phone (optional)",payer,{payer=it});Field("Received in (bank, wallet or card)",dest,{dest=it})
    Field("Refund order reference (optional)",order,{order=it});Check("Money arrived in my account",confirmed){confirmed=it}
    Save("Save incoming payment"){
        require(payer.isBlank() || Rules.identity(payer).isNotEmpty()){"Enter a UPI ID or an Indian mobile number."}
        save(Credit(amount=positive(amount),reference=ref.trim().ifBlank {null},payer=payer,destination=dest.trim(),orderRef=order.trim(),
            eventKey=raw.takeIf { it.isNotBlank() }?.let(::textKey),source=if(raw.isEmpty())"Manual" else "Shared text",confirmed=confirmed))
    }
}
@Composable fun GroupForm(save:(String,List<Pair<String,String>>)->Unit){
    val ctx=LocalContext.current
    var title by rememberSaveable {mutableStateOf("")}
    var name by rememberSaveable {mutableStateOf("")}
    var id by rememberSaveable {mutableStateOf("")}
    var names by rememberSaveable {mutableStateOf(arrayListOf<String>())}
    var ids by rememberSaveable {mutableStateOf(arrayListOf<String>())}
    var pickerMessage by rememberSaveable {mutableStateOf("")}
    val pick=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()){result->
        if(result.resultCode==Activity.RESULT_OK) result.data?.data?.let {uri->
            runCatching {ctx.contentResolver.query(uri,arrayOf(Phone.DISPLAY_NAME,Phone.NUMBER),null,null,null)?.use {if(it.moveToFirst()){
                name=it.getString(0).orEmpty();id=Rules.identity(it.getString(1).orEmpty())
            }} }.onFailure { pickerMessage="Enter the member manually to continue." }
        }
    }
    Field("Group name",title,{title=it})
    BoxCard {
        Section("Members");Text("You · Bill payer")
        names.forEachIndexed {i,n->Row(verticalAlignment=Alignment.CenterVertically){Text(n,Modifier.weight(1f));Link("Remove"){names=ArrayList(names).apply{removeAt(i)};ids=ArrayList(ids).apply{removeAt(i)}}}}
    }
    Link("Pick a contact"){runCatching {pick.launch(Intent(Intent.ACTION_PICK,Phone.CONTENT_URI))}.onFailure {pickerMessage="Contact picker unavailable. Enter a member below."}}
    if(pickerMessage.isNotEmpty())Text(pickerMessage)
    Field("Member name",name,{name=it});Field("UPI ID or phone (optional)",id,{id=it})
    Text("A matching payer ID allows DueBack to recognise repayments.")
    fun checkedMember():Pair<String,String>{
        require(name.isNotBlank()){"Enter a member name."}
        require(id.isBlank() || Rules.identity(id).isNotBlank()){"Enter a valid UPI ID or Indian mobile number."}
        return name.trim() to Rules.identity(id)
    }
    Save("Add member",primary=false){val m=checkedMember();names=ArrayList(names).apply{add(m.first)};ids=ArrayList(ids).apply{add(m.second)};name="";id=""}
    Save("Create group"){require(title.isNotBlank()){"Give the group a name."}
        val members=names.zip(ids).toMutableList()
        if(name.isNotBlank() || id.isNotBlank())members.add(checkedMember())
        require(members.isNotEmpty()){"Add at least one other member."}
        save(title,members)}
}
@Composable fun BillForm(group:Circle,people:List<Person>,save:(String,Long,List<Long>)->Unit){
    var name by rememberSaveable {mutableStateOf("")}
    var amount by rememberSaveable {mutableStateOf("")}
    var custom by rememberSaveable {mutableStateOf(false)}
    var values by rememberSaveable {mutableStateOf(ArrayList(List(people.size+1){""}))}
    val labels=listOf("You")+people.map {it.name}
    Text(group.name);Field("Bill name",name,{name=it});Field("Total you paid (₹)",amount,{amount=it},keyboard=KeyboardType.Decimal)
    Check("Use custom shares",custom){custom=it}
    val equal=runCatching{Rules.equal(positive(amount),labels.size).toList()}.getOrNull()
    labels.forEachIndexed {i,label->if(custom)Field(label+" (₹)",values[i],{v->values=ArrayList(values).apply{set(i,v)}},keyboard=KeyboardType.Decimal)
        else Text(label+" · "+(equal?.get(i)?.let(::money)?:"—"))}
    Save("Save bill"){require(name.isNotBlank()){"Enter a bill name."}
        save(name.trim(),positive(amount),if(custom)values.map {Rules.paise(it)} else requireNotNull(equal))}
}
@Composable fun MatchForm(s:ViewData,c:Credit,save:(Due,Long,Boolean)->Unit){
    val candidates=s.candidates(c)
    val all=s.dues.filter {s.left(it)>0}.sortedBy {if(it in candidates)0 else 1}
    var chosen by rememberSaveable {mutableStateOf(if(candidates.size==1)candidates.single().id else "")}
    var split by rememberSaveable {mutableStateOf(s.free(c)<c.amount)}
    var amount by rememberSaveable {mutableStateOf(BigDecimal.valueOf(s.free(c),2).toPlainString())}
    Text(money(s.free(c))+" available")
    if(candidates.size>1)Text("Several balances match this payer or order reference. Choose one.")
    if(all.isEmpty()) Text("Add a refund or group bill with an outstanding balance first.")
    all.forEach {d->BoxCard{Row(verticalAlignment=Alignment.CenterVertically){
        RadioButton(chosen==d.id,{chosen=d.id});Column{Text(s.label(d));Text(money(s.left(d))+" outstanding")}}
        if(d in candidates)Text(if(d.bill.isEmpty())"Same order reference" else "Same payer ID")
    }}
    Check("Explicitly split this credit",split){split=it}
    if(split) Field("Apply amount (₹)",amount,{amount=it},keyboard=KeyboardType.Decimal)
    Save("Confirm allocation"){val d=all.find {it.id==chosen}?:error("Choose a refund or bill share.")
        save(d,if(split)positive(amount) else c.amount,split)}
}

@Composable private fun SourcePreview(raw:String) {
    var show by remember { mutableStateOf(false) }
    Link(if(show) "Hide source text" else "Show source text") { show=!show }
    if(show) Text(raw)
}
