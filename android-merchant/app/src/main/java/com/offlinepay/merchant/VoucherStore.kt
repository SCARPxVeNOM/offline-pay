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
    val status: String,         // accepted | rejected | settled | replica
    val rejectReason: String?,
    val acceptedAtMs: Long,
    val settledTx: String?,
    /// How many distinct mesh peers have ACK'd a copy of this voucher.
    /// 0 = no backup yet (single point of failure on this device).
    val replicaCount: Int = 0,
    /// JSON list of peer endpoint IDs that have ACK'd a replica.
    val replicaPeers: String = "[]",
)

@Dao
interface VoucherDao {
    @Query("SELECT COUNT(*) > 0 FROM vouchers WHERE voucherId = :id")
    fun exists(id: String): Boolean

    @Query("SELECT * FROM vouchers WHERE voucherId = :id LIMIT 1")
    suspend fun get(id: String): VoucherRow?

    @Query("SELECT * FROM vouchers ORDER BY acceptedAtMs DESC LIMIT 100")
    fun recent(): Flow<List<VoucherRow>>

    @Query("SELECT * FROM vouchers WHERE status='accepted' OR status='replica' LIMIT 50")
    suspend fun pendingForSettle(): List<VoucherRow>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: VoucherRow): Long

    @Query("UPDATE vouchers SET status='settled', settledTx=:tx WHERE voucherId=:id")
    suspend fun markSettled(id: String, tx: String)

    @Query("UPDATE vouchers SET replicaCount=:count, replicaPeers=:peers WHERE voucherId=:id")
    suspend fun updateReplication(id: String, count: Int, peers: String)
}

@Database(entities = [VoucherRow::class], version = 2, exportSchema = false)
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

    suspend fun get(id: String): VoucherRow? = dao.get(id)

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

    /// Voucher received over the mesh from a peer. Stored as a backup so
    /// if the original merchant device is lost, we can still settle.
    suspend fun saveReplica(v: Voucher) {
        dao.insert(VoucherRow(
            voucherId    = v.voucherId,
            payer        = v.payer,
            merchant     = v.merchant,
            amount       = v.amount.toString(),
            expiry       = v.expiry,
            nonce        = v.nonce,
            signature    = v.signature,
            status       = "replica",
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

    /// Record that a mesh peer ACK'd storing a copy of this voucher.
    suspend fun recordReplicaAck(voucherId: String, peerId: String) {
        val row = dao.get(voucherId) ?: return
        val peers = parsePeers(row.replicaPeers).toMutableSet()
        if (peers.add(peerId)) {
            dao.updateReplication(voucherId, peers.size, encodePeers(peers))
        }
    }

    private fun parsePeers(json: String): Set<String> =
        json.trim().removePrefix("[").removeSuffix("]")
            .split(",").map { it.trim().trim('"') }.filter { it.isNotEmpty() }.toSet()

    private fun encodePeers(peers: Set<String>): String =
        peers.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }
}

/// UI helper — confidence level shown to the merchant alongside an accepted voucher.
fun confidenceLevel(replicaCount: Int): String = when {
    replicaCount >= 3 -> "HIGH ($replicaCount backups)"
    replicaCount >= 1 -> "MEDIUM ($replicaCount backup${if (replicaCount==1) "" else "s"})"
    else              -> "LOW (no backup yet)"
}
