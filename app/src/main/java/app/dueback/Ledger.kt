package app.dueback

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import java.util.UUID

fun uid() = UUID.randomUUID().toString()
@Entity data class Circle(@PrimaryKey val id: String = uid(), val name: String)
@Entity data class Person(@PrimaryKey val id: String = uid(), val circle: String, val name: String, val identity: String)
@Entity data class Bill(@PrimaryKey val id: String = uid(), val circle: String, val name: String, val total: Long, val mine: Long)
@Entity data class Due(@PrimaryKey val id: String = uid(), val name: String, val amount: Long,
    val bill: String = "", val person: String = "", val orderRef: String = "", val date: String = "", val destination: String = "",
    @ColumnInfo(defaultValue = "'Previously recorded'") val origin:String="Manual", val sourceTime:Long?=null, val sourceZone:String?=null)
@Entity(indices=[Index(value=["reference"], unique=true), Index(value=["eventKey"], unique=true)])
data class Credit(@PrimaryKey val id: String = uid(), val amount: Long, val reference: String?, val eventKey: String?,
    val payer: String, val destination: String, val source: String, val confirmed: Boolean, val orderRef: String = "", val sourceTime:Long?=null, val sourceZone:String?=null)
@Entity data class Allocation(@PrimaryKey val id: String = uid(), val credit: String, val due: String, val amount: Long, val reason: String)
@Entity data class ActivityLine(@PrimaryKey(autoGenerate=true) val id: Long = 0, val text: String)
@Entity data class Draft(@PrimaryKey val id: String, val message: String, val sourceTime:Long?=null, val sourceZone:String?=null, @ColumnInfo(defaultValue="0") val payment:Boolean=false)

@Entity data class SeenInput(@PrimaryKey val token: String)
@Dao interface Books {
    @Query("SELECT * FROM Circle ORDER BY rowid DESC") suspend fun circles(): List<Circle>
    @Query("SELECT * FROM Person ORDER BY rowid") suspend fun people(): List<Person>
    @Query("SELECT * FROM Bill ORDER BY rowid DESC") suspend fun bills(): List<Bill>
    @Query("SELECT * FROM Due ORDER BY rowid DESC") suspend fun dues(): List<Due>
    @Query("SELECT * FROM Credit ORDER BY rowid DESC") suspend fun credits(): List<Credit>
    @Query("SELECT * FROM Allocation") suspend fun allocations(): List<Allocation>
    @Query("SELECT * FROM ActivityLine ORDER BY id DESC LIMIT 100") suspend fun feed(): List<ActivityLine>
    @Query("SELECT * FROM Draft") suspend fun drafts(): List<Draft>
    @Query("SELECT MAX(id) FROM ActivityLine") fun changes(): Flow<Long?>
    @Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun remember(input: SeenInput): Long
    @Insert suspend fun add(x: Circle)
    @Insert suspend fun add(x: Person)
    @Insert suspend fun add(x: Bill)
    @Insert suspend fun add(x: Due)
    @Insert suspend fun add(x: Credit)
    @Insert suspend fun add(x: Allocation)
    @Insert suspend fun add(x: ActivityLine)
    @Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun draft(x: Draft): Long
    @Delete suspend fun remove(x: Allocation)
    @Query("DELETE FROM Draft WHERE id=:id") suspend fun dropDraft(id: String)
    @Query("UPDATE Credit SET confirmed=1 WHERE id=:id") suspend fun confirm(id: String)
    @Query("DELETE FROM Credit WHERE id=:id AND confirmed=0") suspend fun dismiss(id: String)
}
internal val migration23 = object : androidx.room.migration.Migration(2, 3) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE Due ADD COLUMN sourceTime INTEGER")
        db.execSQL("ALTER TABLE Due ADD COLUMN sourceZone TEXT")
        db.execSQL("ALTER TABLE Credit ADD COLUMN sourceTime INTEGER")
        db.execSQL("ALTER TABLE Credit ADD COLUMN sourceZone TEXT")
        db.execSQL("ALTER TABLE Draft ADD COLUMN sourceTime INTEGER")
        db.execSQL("ALTER TABLE Draft ADD COLUMN sourceZone TEXT")
        db.execSQL("ALTER TABLE Draft ADD COLUMN payment INTEGER NOT NULL DEFAULT 0")
    }
}

@Database(entities=[Circle::class, Person::class, Bill::class, Due::class, Credit::class, Allocation::class, ActivityLine::class, Draft::class, SeenInput::class], version=3, exportSchema=false)
abstract class Ledger : RoomDatabase() {
    abstract fun books(): Books
    companion object {
        @Volatile private var instance: Ledger? = null
        fun get(c: Context): Ledger = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(c.applicationContext, Ledger::class.java,"dueback.db")
                .addMigrations(object : androidx.room.migration.Migration(1, 2) {
                    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("ALTER TABLE Due ADD COLUMN origin TEXT NOT NULL DEFAULT 'Previously recorded'")
                        db.execSQL("CREATE TABLE IF NOT EXISTS SeenInput (token TEXT NOT NULL PRIMARY KEY)")
                        db.execSQL("INSERT OR IGNORE INTO SeenInput SELECT id FROM Draft")
                    }
                }, migration23).build().also { instance=it }
        }
    }
}

data class ViewData(val circles: List<Circle> = emptyList(), val people: List<Person> = emptyList(),
    val bills: List<Bill> = emptyList(), val dues: List<Due> = emptyList(), val credits: List<Credit> = emptyList(),
    val allocations: List<Allocation> = emptyList(), val feed: List<ActivityLine> = emptyList(), val drafts: List<Draft> = emptyList()) {
    fun left(d: Due) = Accounting.remaining(d.amount,allocations.filter { it.due==d.id }.sumOf { it.amount })
    fun free(c: Credit) = Accounting.remaining(c.amount,allocations.filter { it.credit==c.id }.sumOf { it.amount })
    fun label(d: Due) = if (d.bill.isEmpty()) d.name else d.name+" · "+bills.first { it.id==d.bill }.name+" · "+circles.first { it.id==bills.first { b->b.id==d.bill }.circle }.name
    fun candidates(c: Credit) = dues.filter { d ->
        if(d.bill.isEmpty()) Accounting.compatible(c.amount,left(d),c.orderRef,d.orderRef)
        else people.any { it.id==d.person && Accounting.compatible(c.amount,left(d),c.payer,it.identity) }
    }
}
class Store(val db: Ledger) {
    val q=db.books()
    suspend fun state() = db.withTransaction { ViewData(q.circles(),q.people(),q.bills(),q.dues(),q.credits(),q.allocations(),q.feed(),q.drafts()) }
    suspend fun note(s: String) = q.add(ActivityLine(text=s))
    suspend fun refund(d: Due, draftId: String="") = db.withTransaction {
        require(d.amount>0 && d.name.isNotBlank())
        if(draftId.isNotBlank() && q.drafts().none { it.id == draftId }) {
            require(q.remember(SeenInput(draftId)) != -1L) { "This refund was already reviewed." }
        }
        val draft=q.drafts().firstOrNull { it.id==draftId }
        q.add(d.copy(sourceTime=draft?.sourceTime ?: d.sourceTime, sourceZone=draft?.sourceZone ?: d.sourceZone))
        q.dropDraft(draftId)
        note("Refund promised by "+d.name)
    }
    suspend fun deleteImports() = db.withTransaction {
        val sql=db.openHelper.writableDatabase
        val importedCredits="SELECT id FROM Credit WHERE source != 'Manual'"
        val importedRefunds="SELECT id FROM Due WHERE bill='' AND origin NOT IN ('Manual','Previously recorded')"
        sql.execSQL("DELETE FROM Allocation WHERE credit IN ($importedCredits) OR due IN ($importedRefunds)")
        sql.execSQL("DELETE FROM Credit WHERE id IN ($importedCredits)")
        sql.execSQL("DELETE FROM Due WHERE id IN ($importedRefunds)")
        sql.execSQL("DELETE FROM Draft")
        sql.execSQL("DELETE FROM SeenInput")
        sql.execSQL("DELETE FROM ActivityLine")
        note("Imported data deleted")
    }
    suspend fun group(name: String, members: List<Pair<String,String>>) = db.withTransaction {
        require(name.isNotBlank() && members.isNotEmpty() && members.all { it.first.isNotBlank() })
        val g=Circle(name=name.trim())
        q.add(g)
        members.forEach { q.add(Person(circle=g.id,name=it.first.trim(),identity=Rules.identity(it.second))) }
        note("Created "+g.name)
    }
    suspend fun bill(g: Circle, name: String, total: Long, shares: List<Long>) = db.withTransaction {
        val members=q.people().filter { it.circle==g.id }
        require(name.isNotBlank() && total>0 && shares.size==members.size+1 && shares.all { it>=0 && it<=total } && shares.fold(0L,Math::addExact)==total) { "Shares must add up to the bill." }
        val b=Bill(circle=g.id,name=name,total=total,mine=shares.first())
        q.add(b)
        members.forEachIndexed { i,p -> q.add(Due(name=p.name,amount=shares[i+1],person=p.id,bill=b.id)) }
        note("Added bill "+name)
    }
    suspend fun ingest(input: Credit, auto: Boolean=true): String = db.withTransaction {
        require(input.amount>0)
        val c=input.copy(reference=input.reference?.trim()?.uppercase()?.ifBlank { null }, payer=Rules.identity(input.payer))
        if (c.eventKey != null && q.remember(SeenInput(c.eventKey)) == -1L) return@withTransaction "Already recorded"
        val s=state()
        if(s.credits.any { Accounting.duplicate(c.reference,c.eventKey,it.reference,it.eventKey) }) return@withTransaction "Already recorded"
        // Unreferenced imports require confirmation before they affect a balance.
        val possibleDuplicate = s.credits.any { it.amount == c.amount && (it.reference == null || c.reference == null) }
        val saved=if((c.reference==null && c.source!="Manual") || possibleDuplicate) c.copy(confirmed=false) else c
        q.add(saved)
        val targets=s.candidates(saved)
        val msg=when {
            auto && Accounting.autoMatch(saved.confirmed,targets.size) -> {
                val d=targets.single()
                val why=if(d.bill.isEmpty()) "Same order reference "+saved.orderRef else "Same payer ID "+saved.payer
                q.add(Allocation(credit=saved.id,due=d.id,amount=saved.amount,reason=why+" · one compatible balance"))
                "Matched "+money(saved.amount)+" to "+s.label(d)
            }
            targets.size>1 -> "Choose a bill or refund for "+money(c.amount)
            else -> "Incoming payment needs review · "+money(c.amount)
        }
        note(msg)
        msg
    }
    suspend fun allocate(cId: String,dId: String,amount: Long,split: Boolean) = db.withTransaction {
        val s=state()
        val c=s.credits.first { it.id==cId }
        val d=s.dues.first { it.id==dId }
        require(c.confirmed) { "Confirm this incoming credit first." }
        Accounting.checkAllocation(c.amount,s.free(c),s.left(d),amount,split)
        q.add(Allocation(credit=cId,due=dId,amount=amount,reason=if(split) "Split confirmed by you" else "Selected by you"))
        note("Applied "+money(amount)+" to "+s.label(d))
    }
    suspend fun undo(a: Allocation) = db.withTransaction { q.remove(a); note("Undid match of "+money(a.amount)) }
    suspend fun review(c: Credit, accept: Boolean) = db.withTransaction {
        if(accept) q.confirm(c.id) else q.dismiss(c.id)
        note(if(accept) "Incoming credit confirmed" else "Review item dismissed")
    }
}
