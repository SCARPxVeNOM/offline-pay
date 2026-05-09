package com.offlinepay.wallet

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.TypeReference
import org.web3j.abi.datatypes.*
import org.web3j.abi.datatypes.generated.Bytes32
import org.web3j.abi.datatypes.generated.Uint256
import org.web3j.crypto.ECKeyPair
import org.web3j.crypto.RawTransaction
import org.web3j.crypto.TransactionEncoder
import org.web3j.protocol.Web3j
import org.web3j.protocol.http.HttpService
import org.web3j.utils.Numeric
import java.math.BigInteger

// Disambiguate from kotlin.Function (which expects a type argument).
private typealias Web3Function = org.web3j.abi.datatypes.Function

/// Calls OfflineVault.settle* directly. Caller MUST have MATIC for gas.
/// Returns the tx hash on success. settleBearerBatch additionally waits
/// for the receipt and asserts on-chain status==1, so the caller can be
/// sure the funds actually moved.
class SettlementClient(
    private val rpcUrl: String,
    private val vaultAddress: String,
    private val chainId: Long,
    private val keyPair: ECKeyPair,
    private val fromAddress: String,
) {
    private val web3 = Web3j.build(HttpService(rpcUrl))

    /// Build the on-chain `Voucher` struct tuple. v3 layout:
    ///   (payer, merchant, recipient, amount, expiry, nonce, voucherId)
    /// Uses StaticStruct (not Dynamic) — every field is a static type, and
    /// DynamicStruct adds an offset header that doesn't match the contract's
    /// inline calldata layout.
    private fun voucherTuple(r: VoucherRow) = StaticStruct(
        Address(r.payer),
        Address(r.merchant),
        Address(r.recipient),
        Uint256(BigInteger(r.amount)),
        Uint256(BigInteger.valueOf(r.expiry)),
        Uint256(BigInteger.valueOf(r.nonce)),
        Bytes32(Numeric.hexStringToByteArray(r.voucherId)),
    )

    /// Bearer settle (relay-friendly). Recipient comes from each voucher
    /// itself — it was signed by the payer at tap time. msg.sender can be
    /// anyone; this is the mesh-relay path.
    suspend fun settleBearerBatch(rows: List<VoucherRow>): String =
        withContext(Dispatchers.IO) {
            require(rows.isNotEmpty()) { "no vouchers to settle" }

            val tuples = rows.map { voucherTuple(it) }
            val sigs   = rows.map { DynamicBytes(Numeric.hexStringToByteArray(it.signature)) }

            val function = Function(
                "settleBearerBatch",
                listOf(
                    @Suppress("UNCHECKED_CAST")
                    DynamicArray(StaticStruct::class.java, tuples) as Type<*>,
                    DynamicArray(DynamicBytes::class.java, sigs) as Type<*>,
                ),
                emptyList()
            )
            val data  = FunctionEncoder.encode(function)
            val nonce = web3.ethGetTransactionCount(
                fromAddress, org.web3j.protocol.core.DefaultBlockParameterName.LATEST
            ).send().transactionCount
            val gasPrice = web3.ethGasPrice().send().gasPrice
            val gasLimit = BigInteger.valueOf(500_000L * rows.size + 200_000L)
            val tx = RawTransaction.createTransaction(
                nonce, gasPrice, gasLimit, vaultAddress, BigInteger.ZERO, data
            )
            val signed = TransactionEncoder.signMessage(
                tx, chainId, org.web3j.crypto.Credentials.create(keyPair)
            )
            val resp = web3.ethSendRawTransaction(Numeric.toHexString(signed)).send()
            if (resp.hasError()) error("submit failed: ${resp.error.message}")
            val txHash = resp.transactionHash
            // Wait for receipt; assert mined successfully.
            val rcpt = waitForReceipt(txHash)
                ?: error("settle tx not mined within 60s: $txHash")
            if (rcpt.status != "0x1") error("settle reverted on chain: $txHash")
            txHash
        }

    /// True-bearer settle, paid out to the merchant primary committed by
    /// the ESP32 endorsement. Used for B2 cards where recipient = 0x0
    /// and the merchant binding only happens at tap time.
    ///
    /// Reverts via the contract's existing checks if the voucher sig
    /// doesn't match the payer, or the endorsement sig doesn't match
    /// the device, or any of {voucher.recipient != 0, primary == 0,
    /// expired, double-spend, insufficient locked} fail.
    suspend fun settleBearerWithEndorsement(row: VoucherRow): String =
        withContext(Dispatchers.IO) {
            val device  = row.endorsementDevice  ?: error("missing endorsement device")
            val primary = row.endorsementPrimary ?: error("missing endorsement primary")
            val ts      = row.endorsementTs      ?: error("missing endorsement timestamp")
            val esig    = row.endorsementSig     ?: error("missing endorsement signature")

            val voucher  = voucherTuple(row)
            val function = Function(
                "settleBearerWithEndorsement",
                listOf(
                    voucher,
                    DynamicBytes(Numeric.hexStringToByteArray(row.signature)),
                    Address(device),
                    Address(primary),
                    Uint256(BigInteger.valueOf(ts)),
                    DynamicBytes(Numeric.hexStringToByteArray(esig)),
                ),
                emptyList()
            )
            val data  = FunctionEncoder.encode(function)
            val nonce = web3.ethGetTransactionCount(
                fromAddress, org.web3j.protocol.core.DefaultBlockParameterName.LATEST
            ).send().transactionCount
            val gasPrice = web3.ethGasPrice().send().gasPrice
            val gasLimit = BigInteger.valueOf(500_000L)
            val tx = RawTransaction.createTransaction(
                nonce, gasPrice, gasLimit, vaultAddress, BigInteger.ZERO, data
            )
            val signed = TransactionEncoder.signMessage(
                tx, chainId, org.web3j.crypto.Credentials.create(keyPair)
            )
            val resp = web3.ethSendRawTransaction(Numeric.toHexString(signed)).send()
            if (resp.hasError()) error("submit failed: ${resp.error.message}")
            val txHash = resp.transactionHash
            val rcpt = waitForReceipt(txHash)
                ?: error("settle tx not mined within 60s: $txHash")
            if (rcpt.status != "0x1") error("settle reverted on chain: $txHash")
            txHash
        }

    /// Legacy fixed-merchant batch settle (msg.sender == merchant).
    suspend fun settleBatch(rows: List<VoucherRow>): String =
        withContext(Dispatchers.IO) {
            require(rows.isNotEmpty()) { "no vouchers to settle" }
            val tuples = rows.map { voucherTuple(it) }
            val sigs   = rows.map { DynamicBytes(Numeric.hexStringToByteArray(it.signature)) }
            val function = Function(
                "settleBatch",
                listOf(
                    @Suppress("UNCHECKED_CAST")
                    DynamicArray(StaticStruct::class.java, tuples) as Type<*>,
                    DynamicArray(DynamicBytes::class.java, sigs) as Type<*>,
                ),
                emptyList()
            )
            val data  = FunctionEncoder.encode(function)
            val nonce = web3.ethGetTransactionCount(
                fromAddress, org.web3j.protocol.core.DefaultBlockParameterName.LATEST
            ).send().transactionCount
            val gasPrice = web3.ethGasPrice().send().gasPrice
            val gasLimit = BigInteger.valueOf(800_000L * rows.size + 200_000L)
            val tx = RawTransaction.createTransaction(
                nonce, gasPrice, gasLimit, vaultAddress, BigInteger.ZERO, data
            )
            val signed = TransactionEncoder.signMessage(tx, chainId, org.web3j.crypto.Credentials.create(keyPair))
            val resp = web3.ethSendRawTransaction(Numeric.toHexString(signed)).send()
            if (resp.hasError()) error("submit failed: ${resp.error.message}")
            resp.transactionHash
        }

    /// Poll Web3j for the receipt of [txHash]. Returns null if not mined
    /// within ~60s.
    private suspend fun waitForReceipt(txHash: String): org.web3j.protocol.core.methods.response.TransactionReceipt? =
        withContext(Dispatchers.IO) {
            repeat(30) {
                val r = web3.ethGetTransactionReceipt(txHash).send().transactionReceipt
                if (r.isPresent) return@withContext r.get()
                kotlinx.coroutines.delay(2000)
            }
            null
        }

    suspend fun maticBalance(addr: String): BigInteger = withContext(Dispatchers.IO) {
        web3.ethGetBalance(addr, org.web3j.protocol.core.DefaultBlockParameterName.LATEST).send().balance
    }

    /// Bulk-check `usedVouchers[voucherId]` for several voucherIds. Used by
    /// the sender side to figure out which in-flight vouchers have settled
    /// (and thus their lockedBalance has decremented).
    suspend fun usedVouchers(ids: List<String>): Map<String, Boolean> =
        withContext(Dispatchers.IO) {
            ids.associateWith { id ->
                runCatching {
                    val function = Function(
                        "usedVouchers",
                        listOf(Bytes32(Numeric.hexStringToByteArray(id))),
                        listOf<TypeReference<*>>(object : TypeReference<org.web3j.abi.datatypes.Bool>() {})
                    )
                    val data = FunctionEncoder.encode(function)
                    val resp = web3.ethCall(
                        org.web3j.protocol.core.methods.request.Transaction.createEthCallTransaction(
                            fromAddress, vaultAddress, data
                        ),
                        org.web3j.protocol.core.DefaultBlockParameterName.LATEST
                    ).send()
                    if (resp.hasError()) false
                    else BigInteger(resp.value.removePrefix("0x").ifEmpty { "0" }, 16) != BigInteger.ZERO
                }.getOrDefault(false)
            }
        }

    suspend fun lockedBalance(addr: String): BigInteger = withContext(Dispatchers.IO) {
        val function = Function(
            "lockedBalance",
            listOf(Address(addr)),
            listOf<TypeReference<*>>(object : TypeReference<Uint256>() {})
        )
        val data = FunctionEncoder.encode(function)
        val resp = web3.ethCall(
            org.web3j.protocol.core.methods.request.Transaction.createEthCallTransaction(
                fromAddress, vaultAddress, data
            ),
            org.web3j.protocol.core.DefaultBlockParameterName.LATEST
        ).send()
        if (resp.hasError()) BigInteger.ZERO
        else BigInteger(resp.value.removePrefix("0x").ifEmpty { "0" }, 16)
    }

    suspend fun approveUsdc(usdcAddr: String, spender: String, amount: BigInteger): String =
        sendWrite(usdcAddr) {
            Function("approve",
                listOf(Address(spender), Uint256(amount)),
                emptyList())
        }

    /// Submit approve + lockFunds back-to-back with explicit consecutive
    /// nonces. Halves the topup wall time. Returns the lockFunds tx hash.
    suspend fun approveAndLock(usdcAddr: String, amount: BigInteger): String =
        withContext(Dispatchers.IO) {
            val baseNonce = web3.ethGetTransactionCount(
                fromAddress, org.web3j.protocol.core.DefaultBlockParameterName.PENDING
            ).send().transactionCount
            val gasPrice = web3.ethGasPrice().send().gasPrice
            val creds    = org.web3j.crypto.Credentials.create(keyPair)

            val approveData = FunctionEncoder.encode(Function(
                "approve",
                listOf(Address(vaultAddress), Uint256(amount)),
                emptyList(),
            ))
            val approveTx = RawTransaction.createTransaction(
                baseNonce, gasPrice, BigInteger.valueOf(120_000L),
                usdcAddr, BigInteger.ZERO, approveData,
            )
            val approveSigned = TransactionEncoder.signMessage(approveTx, chainId, creds)
            val approveResp = web3.ethSendRawTransaction(Numeric.toHexString(approveSigned)).send()
            if (approveResp.hasError()) error("approve: ${approveResp.error.message}")

            val lockData = FunctionEncoder.encode(Function(
                "lockFunds",
                listOf(Uint256(amount)),
                emptyList(),
            ))
            val lockTx = RawTransaction.createTransaction(
                baseNonce + BigInteger.ONE, gasPrice, BigInteger.valueOf(180_000L),
                vaultAddress, BigInteger.ZERO, lockData,
            )
            val lockSigned = TransactionEncoder.signMessage(lockTx, chainId, creds)
            val lockResp = web3.ethSendRawTransaction(Numeric.toHexString(lockSigned)).send()
            if (lockResp.hasError()) error("lock: ${lockResp.error.message}")
            lockResp.transactionHash
        }

    suspend fun lockFunds(amount: BigInteger): String =
        sendWrite(vaultAddress) {
            Function("lockFunds",
                listOf(Uint256(amount)),
                emptyList())
        }

    private suspend fun sendWrite(to: String, fn: () -> Web3Function): String = withContext(Dispatchers.IO) {
        val data = FunctionEncoder.encode(fn())
        val nonce = web3.ethGetTransactionCount(
            fromAddress, org.web3j.protocol.core.DefaultBlockParameterName.LATEST
        ).send().transactionCount
        val gasPrice = web3.ethGasPrice().send().gasPrice
        val gasLimit = BigInteger.valueOf(200_000L)
        val tx = RawTransaction.createTransaction(nonce, gasPrice, gasLimit, to, BigInteger.ZERO, data)
        val signed = TransactionEncoder.signMessage(tx, chainId, org.web3j.crypto.Credentials.create(keyPair))
        val resp = web3.ethSendRawTransaction(Numeric.toHexString(signed)).send()
        if (resp.hasError()) error("write failed: ${resp.error.message}")
        resp.transactionHash
    }
}
