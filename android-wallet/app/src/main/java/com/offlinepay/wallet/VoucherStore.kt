package com.offlinepay.wallet

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

interface VoucherStoreLike {
    fun exists(id: String): Boolean
}

/// Vouchers received over NFC by this device, awaiting settle via backend.
@Entity(tableName = "vouchers")
data class VoucherRow(
    @PrimaryKey val voucherId: String,
    val payer: String,
    val merchant: String,
    val amount: String,
    val expiry: Long,
    val nonce: Long,
    val signature: String,
    val status: String,        // accepted | settled | rejected
    val rejectReason: String?,
    val acceptedAtMs: Long,
    val settledTx: String?,
)

/// Pre-signed bearer vouchers loaded onto this phone at topup time. Each
/// `cardPayload` is the exact JSON the backend returned and that we'll emit
/// over NFC during a tap — already signed, no extra work at tap time.
@Entity(tableName = "unspent")
data class UnspentRow(
    @PrimaryKey val voucherId: String,
    val amountBaseUnits: Long,
    val cardPayload: String,
    val addedAtMs: Long,
    val status: String,         // unspent | spent
    val spentAtMs: Long?,
)

@Dao
interface VoucherDao {
    @Query("SELECT COUNT(*) > 0 FROM vouchers WHERE voucherId = :id")
    fun exists(id: String): Boolean

    @Query("SELECT * FROM vouchers WHERE voucherId = :id LIMIT 1")
    suspend fun get(id: String): VoucherRow?

    @Query("SELECT * FROM vouchers ORDER BY acceptedAtMs DESC LIMIT 100")
    fun recent(): Flow<List<VoucherRow>>

    @Query("SELECT * FROM vouchers WHERE status='accepted' LIMIT 50")
    suspend fun pendingForSettle(): List<VoucherRow>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: VoucherRow): Long

    @Query("UPDATE vouchers SET status='settled', settledTx=:tx WHERE voucherId=:id")
    suspend fun markSettled(id: String, tx: String)
}

@Dao
interface UnspentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<UnspentRow>)

    @Query("SELECT * FROM unspent WHERE status='unspent' ORDER BY amountBaseUnits ASC")
    suspend fun unspentList(): List<UnspentRow>

    @Query("SELECT * FROM unspent WHERE status='unspent' ORDER BY amountBaseUnits ASC")
    fun unspentFlow(): Flow<List<UnspentRow>>

    @Query("SELECT IFNULL(SUM(amountBaseUnits), 0) FROM unspent WHERE status='unspent'")
    fun unspentTotalFlow(): Flow<Long>

    @Query("UPDATE unspent SET status='spent', spentAtMs=:ts WHERE voucherId IN (:ids)")
    suspend fun markSpent(ids: List<String>, ts: Long)
}

@Database(entities = [VoucherRow::class, UnspentRow::class], version = 2, exportSchema = false)
abstract class VoucherDb : RoomDatabase() {
    abstract fun voucherDao(): VoucherDao
    abstract fun unspentDao(): UnspentDao
    companion object {
        @Volatile private var INSTANCE: VoucherDb? = null
        fun get(ctx: Context): VoucherDb =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    ctx.applicationContext, VoucherDb::class.java, "offlinepay-wallet.db"
                ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
    }
}

open class VoucherStore(ctx: Context) : VoucherStoreLike {
    private val dao = VoucherDb.get(ctx).voucherDao()

    override fun exists(id: String): Boolean = dao.exists(id)
    suspend fun get(id: String): VoucherRow? = dao.get(id)

    suspend fun saveAccepted(v: Voucher) {
        dao.insert(VoucherRow(
            voucherId = v.voucherId, payer = v.payer, merchant = v.merchant,
            amount = v.amount.toString(), expiry = v.expiry, nonce = v.nonce,
            signature = v.signature,
            status = "accepted", rejectReason = null,
            acceptedAtMs = System.currentTimeMillis(), settledTx = null,
        ))
    }

    suspend fun saveRejected(v: Voucher, reason: String) {
        dao.insert(VoucherRow(
            voucherId = v.voucherId, payer = v.payer, merchant = v.merchant,
            amount = v.amount.toString(), expiry = v.expiry, nonce = v.nonce,
            signature = v.signature,
            status = "rejected", rejectReason = reason,
            acceptedAtMs = System.currentTimeMillis(), settledTx = null,
        ))
    }

    suspend fun pendingForSettle() = dao.pendingForSettle()
    suspend fun markSettled(id: String, tx: String) = dao.markSettled(id, tx)
    fun recent(): Flow<List<VoucherRow>> = dao.recent()
}

/// Sender-side store for pre-signed bearer vouchers issued by the backend
/// at topup time. NFC tap drains entries from here.
class UnspentStore(ctx: Context) {
    private val dao = VoucherDb.get(ctx).unspentDao()

    suspend fun add(rows: List<UnspentRow>) = dao.insertAll(rows)
    suspend fun unspent() = dao.unspentList()
    fun unspentFlow(): Flow<List<UnspentRow>> = dao.unspentFlow()
    fun unspentTotalFlow(): Flow<Long> = dao.unspentTotalFlow()
    suspend fun markSpent(ids: List<String>) = dao.markSpent(ids, System.currentTimeMillis())

    /// Greedy-descending: prefer fewer, larger vouchers. Returns null if the
    /// caller's amount cannot be made exactly from available denominations.
    suspend fun pickForExactAmount(amount: Long): List<UnspentRow>? {
        val sorted = dao.unspentList().sortedByDescending { it.amountBaseUnits }
        val picked = mutableListOf<UnspentRow>()
        var remaining = amount
        for (row in sorted) {
            if (row.amountBaseUnits <= remaining) {
                picked += row
                remaining -= row.amountBaseUnits
                if (remaining == 0L) return picked
            }
        }
        return null
    }
}
