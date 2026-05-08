package com.offlinepay.merchant

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "vouchers")
data class VoucherRow(
    @PrimaryKey val voucherId: String,
    val payer: String,
    val merchant: String,
    val amount: String,
    val expiry: Long,
    val nonce: Long,
    val signature: String,
    val status: String,         // accepted | rejected | settled
    val rejectReason: String?,  // why rejected
    val acceptedAtMs: Long,
    val settledTx: String?,
)

@Dao
interface VoucherDao {
    @Query("SELECT COUNT(*) > 0 FROM vouchers WHERE voucherId = :id")
    fun exists(id: String): Boolean

    @Query("SELECT * FROM vouchers ORDER BY acceptedAtMs DESC LIMIT 100")
    fun recent(): Flow<List<VoucherRow>>

    @Query("SELECT * FROM vouchers WHERE status='accepted' LIMIT 50")
    suspend fun pendingForSettle(): List<VoucherRow>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: VoucherRow): Long

    @Query("UPDATE vouchers SET status='settled', settledTx=:tx WHERE voucherId=:id")
    suspend fun markSettled(id: String, tx: String)
}

@Database(entities = [VoucherRow::class], version = 1, exportSchema = false)
abstract class VoucherDb : RoomDatabase() {
    abstract fun dao(): VoucherDao
    companion object {
        @Volatile private var INSTANCE: VoucherDb? = null
        fun get(ctx: Context): VoucherDb =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(ctx.applicationContext, VoucherDb::class.java, "offlinepay-merchant.db")
                    .fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
    }
}

class VoucherStore(ctx: Context) {
    private val dao = VoucherDb.get(ctx).dao()

    fun exists(id: String): Boolean = dao.exists(id)

    suspend fun saveAccepted(v: Voucher) {
        dao.insert(VoucherRow(
            voucherId    = v.voucherId,
            payer        = v.payer,
            merchant     = v.merchant,
            amount       = v.amount.toString(),
            expiry       = v.expiry,
            nonce        = v.nonce,
            signature    = v.signature,
            status       = "accepted",
            rejectReason = null,
            acceptedAtMs = System.currentTimeMillis(),
            settledTx    = null,
        ))
    }

    suspend fun saveRejected(v: Voucher, reason: String) {
        dao.insert(VoucherRow(
            voucherId    = v.voucherId,
            payer        = v.payer,
            merchant     = v.merchant,
            amount       = v.amount.toString(),
            expiry       = v.expiry,
            nonce        = v.nonce,
            signature    = v.signature,
            status       = "rejected",
            rejectReason = reason,
            acceptedAtMs = System.currentTimeMillis(),
            settledTx    = null,
        ))
    }

    suspend fun pendingForSettle() = dao.pendingForSettle()
    suspend fun markSettled(id: String, tx: String) = dao.markSettled(id, tx)
    fun recent(): Flow<List<VoucherRow>> = dao.recent()
}
