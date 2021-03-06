package com.github.ackintosh.plasmamvp.utxo.transaction

import com.github.ackintosh.plasmamvp.utxo.Chain
import com.github.ackintosh.plasmamvp.utxo.extensions.hexStringToByteArray
import com.github.ackintosh.plasmamvp.utxo.extensions.toHexString
import org.bouncycastle.util.encoders.Base64
import org.kethereum.keccakshortcut.keccak
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.ArrayDeque

class TransactionVerificationService {
    @kotlin.ExperimentalUnsignedTypes
    companion object {
        fun verify(chain: Chain, transaction: Transaction) : Result {
            // Input
            listOf(transaction.input1, transaction.input2)
                .filterNotNull()
                .filterIsInstance(Input::class.java)
                .forEach {
                    val output = chain.snapshot().findOutput(it.transactionHash(), it.outputIndex())
                    output ?: return Result.Failure("the output is not found. tx_hash: ${it.transactionHash()} oindex: ${it.outputIndex()}")

                    val result = verifyTransactionScript(transaction.input1, output)
                    if (result is Result.Failure) {
                        return result
                    }
                }

            // TODO: verify GenerationInput

            // Ensure that Exit isn't started
            listOf(transaction.input1, transaction.input2)
                .filterNotNull()
                .forEach {
                    val output = chain.snapshot().findOutput(it.transactionHash(), it.outputIndex())
                    output ?: return Result.Failure("the output is not found. tx_hash: ${it.transactionHash()} oindex: ${it.outputIndex()}")
                    if (output.exitStarted()) {
                        return Result.Failure("the output is in Exit procedure. tx_hash: ${it.transactionHash()} oindex: ${it.outputIndex()}")
                    }
                }

            return Result.Success()
        }

        fun verifyTransactionScript(input: TransactionInput, utxo: Output) : Result {
            val stack = ArrayDeque<String>()

            val unlockingScript = input.unlockingScript()
            unlockingScript.split(' ').forEach { stack.push(it) }

            utxo.lockingScript().split(' ').forEach {
                when (it) {
                    "OP_DUP" -> stack.push(stack.first)
                    // TODO: change operation name
                    "OP_HASH160" -> {
                        val elem = stack.pop()
                        val bytes = elem.hexStringToByteArray()
                        stack.push(bytes.keccak().copyOfRange(12, 32).toHexString())
                    }
                    "OP_EQUALVERIFY" -> {
                        val elem1 = stack.pop()
                        val elem2 = stack.pop()
                        if (elem1 != elem2) return Result.Failure("The OP_EQUALVERIFY operation detects invalid values")
                    }
                    "OP_CHECKSIG" -> {
                        val publicKeyString = stack.pop()
                        val signatureString = stack.pop()

                        val signature = Base64.decode(signatureString)
                        val instance = Signature.getInstance("NONEwithECDSA")

                        val publicKeyByteArray = publicKeyString.hexStringToByteArray()
                        val publicKeySpec = X509EncodedKeySpec(publicKeyByteArray)
                        val keyfactory = KeyFactory.getInstance("EC")
                        val publicKey = keyfactory.generatePublic(publicKeySpec)

                        instance.initVerify(publicKey)
                        instance.update("${input.transactionHash().value}${input.outputIndex().toHexString()}".hexStringToByteArray())
                        if (instance.verify(signature)) {
                            stack.push("TRUE")
                        }
                    }
                    else -> stack.push(it)
                }
            }

            return if (stack.size == 1 && stack.pop() == "TRUE") {
                Result.Success()
            } else {
                Result.Failure("Verifying the TransactionScript has resulted in FALSE")
            }
        }
    }

    sealed class Result {
        class Success : Result()
        class Failure(val message: String) : Result()
    }
}