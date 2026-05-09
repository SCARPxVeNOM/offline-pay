package com.offlinepay.wallet

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

interface VoucherStoreLike {
    fun exists(id: String): Boolean
}

/// Vouchers received over NFC by this device, awaiting settle via backend.
/// `recipient` is part of the on-chain Voucher struct (v3 schema) — bound
/// at sign time so relay broadcasters can't redirect funds.
@Entity(tableName = "vouchers")
data class VoucherRow(
    @PrimaryKey val voucherId: String,
    val payer: String,
    val merchant: String,
    val recipient: String,
    val amount: String,
    val expiry: Long,
    val nonce: Long,
    val signature: String,
    val status: String,        // accepted | settled | rejected | replica
    val rejectReason: String?,
    val acceptedAtMs: Long,
    val settledTx: String?,
    val replicaCount: Int = 0,
    val replicaPeers: String = "[]",
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

/// Unified activity feed shared between sender and receiver views. One
/// entry per user-meaningful event (topup, sent voucher, received voucher,
/// on-chain settle). Drives the dashboard "recent activity" list.
@Entity(tableName = "activity")
data class ActivityRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,                // topup | sent | received | settled | failed
    val amountBaseUnits: Long,
    val counterparty: String?,        // peer address (sender or receiver) — nullable for topup
    val voucherId: String?,
    val txHash: String?,              // when on-chain
    val ts: Long,
    val note: String? = null,
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

    @Query("UPDATE vouchers SET status='rejected', rejectReason=:reason WHERE voucherId=:id")
    suspend fun markRejected(id: String, reason: String)

    @Query("UPDATE vouchers SET replicaCount=:count, replicaPeers=:peers WHERE voucherId=:id")
    suspend fun updateReplication(id: String, count: Int, peers: String)
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

@Dao
interface ActivityDao {
    @Insert
    suspend fun insert(row: ActivityRow): Long

    @Query("SELECT * FROM activity ORDER BY ts DESC LIMIT 200")
    fun recent(): Flow<List<ActivityRow>>

    @Query("SELECT * FROM activity ORDER BY ts DESC LIMIT 200")
    suspend fun recentList(): List<ActivityRow>

    @Query("UPDATE activity SET txHash=:tx WHERE id=:id")
    suspend fun setTx(id: Long, tx: String)
}

@Database(
    entities = [VoucherRow::class, UnspentRow::class, ActivityRow::class],
    version = 5, exportSchema = false,
)
abstract class VoucherDb : RoomDatabase() {
    abstract fun voucherDao(): VoucherDao
    abstract fun unspentDao(): UnspentDao
    abstract fun activityDao(): ActivityDao
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
            voucherId = v.voucherId, payer = v.payer, merchant = v.merchant, recipient = v.recipient,
            amount = v.amount.toString(), expiry = v.expiry, nonce = v.nonce,
            signature = v.signature,
            status = "accepted", rejectReason = null,
            acceptedAtMs = System.currentTimeMillis(), settledTx = null,
        ))
    }

    suspend fun saveRejected(v: Voucher, reason: String) {
        dao.insert(VoucherRow(
            voucherId = v.voucherId, payer = v.payer, merchant = v.merchant, recipient = v.recipient,
            amount = v.amount.toString(), expiry = v.expiry, nonce = v.nonce,
            signature = v.signature,
            status = "rejected", rejectReason = reason,
            acceptedAtMs = System.currentTimeMillis(), settledTx = null,
        ))
    }

    suspend fun pendingForSettle() = dao.pendingForSettle()
    suspend fun markSettled(id: String, tx: String) = dao.markSettled(id, tx)
    suspend fun markRejected(id: String, reason: String) = dao.markRejected(id, reason)
    fun recent(): Flow<List<VoucherRow>> = dao.recent()

    suspend fun saveReplica(v: Voucher) {
        dao.insert(VoucherRow(
            voucherId = v.voucherId, payer = v.payer, merchant = v.merchant, recipient = v.recipient,
            amount = v.amount.toString(), expiry = v.expiry, nonce = v.nonce,
            signature = v.signature,
            status = "replica", rejectReason = null,
            acceptedAtMs = System.currentTimeMillis(), settledTx = null,
        ))
    }

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

/// Unified activity feed store. Consumers call `record*` from any side
/// (sender, receiver, topup) and the dashboard streams them.
class ActivityStore(ctx: Context) {
    private val dao = VoucherDb.get(ctx).activityDao()

    fun recent(): Flow<List<ActivityRow>> = dao.recent()

    suspend fun recordTopup(amountBaseUnits: Long, txHash: String?) {
        dao.insert(ActivityRow(
            kind = "topup", amountBaseUnits = amountBaseUnits,
            counterparty = null, voucherId = null, txHash = txHash,
            ts = System.currentTimeMillis(),
            note = "Locked into vault",
        ))
    }

    suspend fun recordReceived(voucherId: String, payer: String, amountBaseUnits: Long) {
        dao.insert(ActivityRow(
            kind = "received", amountBaseUnits = amountBaseUnits,
            counterparty = payer, voucherId = voucherId, txHash = null,
            ts = System.currentTimeMillis(),
            note = "Tapped offline",
        ))
    }

    suspend fun recordSent(voucherId: String, recipient: String, amountBaseUnits: Long) {
        dao.insert(ActivityRow(
            kind = "sent", amountBaseUnits = amountBaseUnits,
            counterparty = recipient, voucherId = voucherId, txHash = null,
            ts = System.currentTimeMillis(),
            note = "Tapped offline",
        ))
    }

    suspend fun recordSettled(voucherIds: List<String>, txHash: String) {
        // One audit row per settle batch. The voucherIds are joined into note.
        dao.insert(ActivityRow(
            kind = "settled", amountBaseUnits = 0L,
            counterparty = null,
            voucherId = voucherIds.firstOrNull(),
            txHash = txHash, ts = System.currentTimeMillis(),
            note = "${voucherIds.size} voucher${if (voucherIds.size == 1) "" else "s"} settled on chain",
        ))
    }

    suspend fun recordFailed(reason: String) {
        dao.insert(ActivityRow(
            kind = "failed", amountBaseUnits = 0L,
            counterparty = null, voucherId = null, txHash = null,
            ts = System.currentTimeMillis(),
            note = reason,
        ))
    }
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

fun confidenceLevel(replicaCount: Int): String = when {
    replicaCount >= 3 -> "HIGH ($replicaCount backups)"
    replicaCount >= 1 -> "MEDIUM ($replicaCount backup${if (replicaCount == 1) "" else "s"})"
    else              -> "LOW (no backup yet)"
}
