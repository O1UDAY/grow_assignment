package app.dueback

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33])
class LedgerTest {
    private lateinit var store:Store
    private val app:Application get()=RuntimeEnvironment.getApplication()
    private fun open()=Store(Room.databaseBuilder(app,Ledger::class.java,"ledger-test").allowMainThreadQueries().build())
    @Before fun setup(){app.deleteDatabase("ledger-test");store=open()}
    @After fun close(){store.db.close()}
    private fun credit(n:Long,ref:String,payer:String="",dest:String="Bank",order:String="")=
        Credit(amount=n,reference=ref,eventKey=null,payer=payer,destination=dest,source="Manual",confirmed=true,orderRef=order)

    @Test fun refundsAndRestart()=runBlocking {
        val d=Due(name="Juniper",amount=120000,orderRef="J17")
        store.refund(d)
        store.ingest(credit(90000,"REF001",order="J17"))
        store.ingest(credit(20000,"REF002",dest="Wallet",order="J17"))
        assertEquals(10000L,store.state().left(d))
        assertEquals(setOf("Bank","Wallet"),store.state().credits.map {it.destination}.toSet())
        store.db.close();store=open()
        assertEquals(10000L,store.state().left(d))
        store.ingest(credit(10000,"REF003",order="J17"))
        assertEquals(0L,store.state().left(d))
    }
    @Test fun initiatedRefundAndUndo()=runBlocking {
        val parsed=Rules.parse("Refund initiated for ₹800")
        assertEquals(Rules.Kind.PROMISE,parsed.kind)
        val d=Due(name="Juniper",amount=Rules.paise(parsed.amount))
        store.refund(d)
        assertEquals(80000L,store.state().left(d))
        val c=credit(80000,"REF004")
        store.ingest(c);store.allocate(c.id,d.id,c.amount,false)
        assertEquals(0L,store.state().left(d))
        store.undo(store.state().allocations.single())
        assertEquals(80000L,store.state().left(d));assertEquals(80000L,store.state().free(c))
    }
    @Test fun equalSplitAndPartialPayment()=runBlocking {
        store.group("Lunch",listOf("Mira" to "mira@upi","Dev" to "dev@upi"))
        store.bill(store.state().circles.single(),"Lunch",90000,Rules.equal(90000,3).toList())
        assertEquals(60000L,store.state().dues.sumOf {it.amount})
        store.ingest(credit(20000,"REF005","mira@upi"))
        val s=store.state()
        assertEquals(10000L,s.left(s.dues.single {it.name=="Mira"}))
        assertEquals(40000L,s.dues.sumOf {s.left(it)})
    }
    @Test fun ambiguousBillChosenByUser()=runBlocking {
        store.group("Flat",listOf("Mira" to "mira@upi"))
        val g=store.state().circles.single()
        for(name in listOf("March","April"))store.bill(g,name,100000,listOf(50000,50000))
        val c=credit(50000,"REF006","mira@upi");store.ingest(c)
        val before=store.state()
        assertEquals(2,before.candidates(c).size);assertTrue(before.allocations.isEmpty())
        val chosen=before.dues.first()
        store.allocate(c.id,chosen.id,50000,false)
        val after=store.state()
        assertEquals(0L,after.left(chosen));assertEquals(50000L,after.left(before.dues.last()))
    }
    @Test fun crossSourceDuplicateAndDistinctTransactions()=runBlocking {
        val parsed=Rules.parse("INR 500 credited to bank. UTR ABC123456")
        store.ingest(credit(Rules.paise(parsed.amount),parsed.reference).copy(source="Notification fixture"))
        importCsv(store,"amount,type,utr\n500,credit,abc123456\n500,credit,ABC123457")
        assertEquals(2,store.state().credits.size)
        assertEquals(100000L,store.state().credits.sumOf {it.amount})
    }
    @Test fun splitWithExplicitConsentOnly()=runBlocking {
        val d1=Due(name="Shop",amount=30000);val d2=Due(name="Travel",amount=40000)
        store.refund(d1);store.refund(d2)
        val c=credit(50000,"REF007");store.ingest(c)
        assertTrue(runCatching {store.allocate(c.id,d1.id,30000,false)}.isFailure)
        store.allocate(c.id,d1.id,30000,true)
        assertTrue(runCatching {store.allocate(c.id,d2.id,30000,true)}.isFailure)
        store.allocate(c.id,d2.id,20000,true)
        assertEquals(0L,store.state().free(c))
    }
}

