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

/// Calls OfflineVault.settleBatch directly from this device's wallet.
/// Caller MUST have MATIC for gas. Returns the tx hash on success.
class SettlementClient(
    private val rpcUrl: String,
    private val vaultAddress: String,
    private val chainId: Long,
    private val keyPair: ECKeyPair,
    private val fromAddress: String,
) {
    private val web3 = Web3j.build(HttpService(rpcUrl))

    suspend fun settleBatch(rows: List<VoucherRow>): String = withContext(Dispatchers.IO) {
        require(rows.isNotEmpty()) { "no vouchers to settle" }

        val voucherTuples = rows.map { r ->
            // Web3j's DynamicStruct adds an extra offset header that the contract's
            // static-struct ABI doesn't expect — it would shift every field by one
            // word and the merchant slot would resolve to a non-zero address,
            // tripping the bearer check. StaticStruct is the right encoder here
            // because every Voucher field is a static type.
            StaticStruct(
                Address(r.payer),
                Address(r.merchant),
                Uint256(BigInteger(r.amount)),
                Uint256(BigInteger.valueOf(r.expiry)),
                Uint256(BigInteger.valueOf(r.nonce)),
                Bytes32(Numeric.hexStringToByteArray(r.voucherId))
            )
        }
        val sigs = rows.map { r ->
            DynamicBytes(Numeric.hexStringToByteArray(r.signature))
        }

        val function = Function(
            "settleBatch",
            listOf(
                @Suppress("UNCHECKED_CAST")
                DynamicArray(StaticStruct::class.java, voucherTuples) as Type<*>,
                DynamicArray(DynamicBytes::class.java, sigs) as Type<*>
            ),
            emptyList()
        )
        val data = FunctionEncoder.encode(function)

        val nonce = web3.ethGetTransactionCount(
            fromAddress, org.web3j.protocol.core.DefaultBlockParameterName.LATEST
        ).send().transactionCount

        val gasPrice = web3.ethGasPrice().send().gasPrice
        val gasLimit = BigInteger.valueOf(800_000L * rows.size + 200_000L)

        val tx = RawTransaction.createTransaction(
            nonce, gasPrice, gasLimit, vaultAddress, BigInteger.ZERO, data
        )
        val signed = TransactionEncoder.signMessage(tx, chainId, org.web3j.crypto.Credentials.create(keyPair))
        val hex = Numeric.toHexString(signed)
        val send = web3.ethSendRawTransaction(hex).send()
        if (send.hasError()) error("submit failed: ${send.error.message}")
        send.transactionHash
    }

    /// Bearer settle: receiver phone is msg.sender. Skips per-payer nonce
    /// check; voucherId uniqueness is the sole replay guard. Funds go to
    /// `recipient` (caller specifies — typically the receiver's own address).
    suspend fun settleBearerBatch(rows: List<VoucherRow>, recipient: String): String =
        withContext(Dispatchers.IO) {
            require(rows.isNotEmpty()) { "no vouchers to settle" }

            // StaticStruct (not DynamicStruct) — Voucher's fields are all static
            // types, so the contract ABI expects an inline tuple without the
            // offset header that DynamicStruct adds. DynamicStruct shifts every
            // field by one word: merchant ends up reading the payer address,
            // failing `merchant == 0` and reverting "not bearer".
            val voucherTuples = rows.map { r ->
                StaticStruct(
                    Address(r.payer),
                    Address(r.merchant),
                    Uint256(BigInteger(r.amount)),
                    Uint256(BigInteger.valueOf(r.expiry)),
                    Uint256(BigInteger.valueOf(r.nonce)),
                    Bytes32(Numeric.hexStringToByteArray(r.voucherId))
                )
            }
            val sigs = rows.map { r -> DynamicBytes(Numeric.hexStringToByteArray(r.signature)) }

            val function = Function(
                "settleBearerBatch",
                listOf(
                    @Suppress("UNCHECKED_CAST")
                    DynamicArray(StaticStruct::class.java, voucherTuples) as Type<*>,
                    DynamicArray(DynamicBytes::class.java, sigs) as Type<*>,
                    Address(recipient) as Type<*>
                ),
                emptyList()
            )
            val data = FunctionEncoder.encode(function)
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
            // Poll for receipt up to 60s; reject if status != 1 (reverted on chain).
            val rcpt = waitForReceipt(txHash) ?: error("settle tx not mined within 60s: $txHash")
            if (rcpt.status != "0x1") error("settle reverted on chain: $txHash")
            txHash
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

    /// Pre-flight: locked-balance lookup. Returns the payer's vault locked
    /// USDC. Used to weed out dead vouchers before paying gas.
    private suspend fun lockedBalanceFor(payer: String): BigInteger =
        lockedBalance(payer)

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
    /// nonces. Halves the topup wall time vs querying the chain twice and
    /// waiting for the first to mine. Returns the lockFunds tx hash.
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
