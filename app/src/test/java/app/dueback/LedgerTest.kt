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

    @Test fun selectedEmailNeedsReviewAndConfirmedCreditIsCountedOnce()=runBlocking {
        val text="INR 125 credited. UTR: REF112233"
        captureText(store,text,"Gmail","event1")
        val credit=store.state().credits.single()
        assertFalse(credit.confirmed)
        importCsv(store,"amount,type,utr\n125,credit,ref112233")
        assertEquals(1,store.state().credits.size)
        assertTrue(store.state().allocations.isEmpty())
    }

    @Test fun missedParseAndEmptyNotificationsNeverCreateLedgerItems()=runBlocking {
        assertEquals("No supported transaction found",captureText(store,"","Google Messages","empty"))
        assertEquals("No supported transaction found",captureText(store,"See you at lunch","Gmail","ordinary"))
        assertTrue(store.state().credits.isEmpty())
        assertTrue(store.state().drafts.isEmpty())
    }

    @Test fun sameRefundCannotReturnAfterReview()=runBlocking {
        val text="Shop: refund initiated for INR 800, order #ABC22. UTR ABC12345"
        captureText(store,text,"Gmail","mail1")
        val draft=store.state().drafts.single()
        store.refund(Due(name="Shop",amount=80000),draft.id)
        assertEquals("Already recorded",captureText(store,text,"Google Messages","msg1"))
        assertTrue(store.state().drafts.isEmpty())
        assertEquals(1,store.state().dues.size)
    }

    @Test fun reorderedCsvDoesNotReviveDismissedCredit()=runBlocking {
        importCsv(store,"credit,payer_id\n25,alice@upi\n50,bob@upi")
        val alice=store.state().credits.first { it.amount==2500L }
        store.review(alice,false)
        importCsv(store,"payer_id,credit\nbob@upi,50\nalice@upi,25")
        assertEquals(listOf(5000L),store.state().credits.map {it.amount})
    }

    @Test fun missingReferenceRequiresReviewEvenWhenOrderMatches()=runBlocking {
        store.refund(Due(name="Shop",amount=80000,orderRef="ABC22"))
        captureText(store,"INR 800 credited for order ABC22","Google Pay","posted1")
        assertFalse(store.state().credits.single().confirmed)
        assertTrue(store.state().allocations.isEmpty())
    }

    @Test fun expandedNotificationAndTitleOnlyAreReadable() {
        val n=android.app.Notification.Builder(app,"test").setContentTitle("Bank")
            .setContentText("Open app").setStyle(android.app.Notification.BigTextStyle().bigText("INR 20 credited")).build()
        assertEquals("Bank\nINR 20 credited",notificationText(n))
        val titleOnly=android.app.Notification.Builder(app,"test").setContentTitle("Refund initiated").build()
        assertEquals("Refund initiated",notificationText(titleOnly))
    }

    @Test fun replayAndOverlappingImportsKeepDistinctUnidentifiedEventsInReview()=runBlocking {
        val msg="INR 300 credited"
        captureText(store,msg,"Payment app","post1",1000L)
        captureText(store,msg,"Payment app","post1",1000L)
        captureText(store,msg,"Payment app","post2",2000L)
        importCsv(store,"credit,utr\n300,UTR12345")
        importCsv(store,"credit,utr\n300,UTR12345\n300,UTR67890")
        assertEquals(4,store.state().credits.size)
        assertEquals(4,store.state().credits.count { !it.confirmed })
        assertTrue(store.state().allocations.isEmpty())
    }

    @Test fun bankNotificationWithReferenceNeverSettlesOnItsOwn()=runBlocking {
        store.refund(Due(name="Shop",amount=80000,orderRef="ABC22"))
        captureText(store,"INR 800 credited for order ABC22. UTR TEST12345","Bank","post-with-ref",1000L)
        assertFalse(store.state().credits.single().confirmed)
        assertEquals(80000L,store.state().left(store.state().dues.single()))
    }

    @Test fun upgradeV2RetainsExistingBalanceAndReviewItems()=runBlocking {
        val name="migration-test"
        app.deleteDatabase(name)
        app.openOrCreateDatabase(name,0,null).use { db ->
            val fixture=requireNotNull(javaClass.classLoader?.getResourceAsStream("ledger-v2.sql"))
            fixture.bufferedReader().useLines { lines -> lines.filter {it.isNotBlank()}.forEach {db.execSQL(it)} }
        }
        val upgraded=Room.databaseBuilder(app,Ledger::class.java,name).addMigrations(migration23).build()
        try {
            val state=Store(upgraded).state()
            assertEquals(6000L,state.left(state.dues.single()))
            assertEquals("House",state.circles.single().name)
            assertEquals("t",state.drafts.single().id)
            assertNull(state.credits.single().sourceTime)
            assertEquals("Selected by you",state.allocations.single().reason)
        } finally {upgraded.close();app.deleteDatabase(name)}
    }

    @Test fun multipartAlternativeUsesPlainBodyAndSkipsAttachment()=runBlocking {
        val part=org.json.JSONObject("""{"mimeType":"multipart/alternative","parts":[
            {"mimeType":"text/html","body":{"data":"PGI-SFRNTDwvYj4"}},
            {"mimeType":"text/plain","filename":"ignored.txt","body":{"data":"ZmFrZQ"}},
            {"mimeType":"text/plain","body":{"data":"SU5SIDEwMCBjcmVkaXRlZA"}}
        ]}""")
        assertEquals("INR 100 credited",emailBody(part){error("Unexpected attachment request")})
    }

    @Test fun groupedUpdateReplaysOldMessageWithoutLosingNewOne()=runBlocking {
        val notification=android.app.Notification.Builder(app,"test").build()
        fun entry(text:String,t:Long)=android.os.Bundle().apply {
            putString("text",text);putLong("time",t);putString("sender","Bank")
        }
        val first=entry("INR 100 credited",1000L)
        val second=entry("INR 100 credited",2000L)
        suspend fun receive(vararg entries:android.os.Bundle) {
            notification.extras.putParcelableArray(android.app.Notification.EXTRA_MESSAGES,entries)
            for((body,time,key) in notificationEvents(notification,"com.example.bank","chat",900L))
                captureText(store,body,"Bank",key,time)
        }
        receive(first);receive(first,second)
        assertEquals(listOf(1000L,2000L),store.state().credits.mapNotNull {it.sourceTime}.sorted())
        assertTrue(store.state().credits.none {it.confirmed})
    }
}
