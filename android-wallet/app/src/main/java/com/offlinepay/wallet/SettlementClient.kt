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
            DynamicStruct(
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
                DynamicArray(DynamicStruct::class.java, voucherTuples) as Type<*>,
                DynamicArray(DynamicBytes::class.java, sigs) as Type<*>
            ),
            emptyList()
        )
        val data = FunctionEncoder.encode(function)

        val nonce = web3.ethGetTransactionCount(
            fromAddress, org.web3j.protocol.core.DefaultBlockParameterName.PENDING
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

    suspend fun maticBalance(addr: String): BigInteger = withContext(Dispatchers.IO) {
        web3.ethGetBalance(addr, org.web3j.protocol.core.DefaultBlockParameterName.LATEST).send().balance
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

    suspend fun lockFunds(amount: BigInteger): String =
        sendWrite(vaultAddress) {
            Function("lockFunds",
                listOf(Uint256(amount)),
                emptyList())
        }

    private suspend fun sendWrite(to: String, fn: () -> Function): String = withContext(Dispatchers.IO) {
        val data = FunctionEncoder.encode(fn())
        val nonce = web3.ethGetTransactionCount(
            fromAddress, org.web3j.protocol.core.DefaultBlockParameterName.PENDING
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
