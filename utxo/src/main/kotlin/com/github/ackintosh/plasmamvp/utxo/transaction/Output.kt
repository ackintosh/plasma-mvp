package com.github.ackintosh.plasmamvp.utxo.transaction

import com.github.ackintosh.plasmamvp.utxo.Address
import java.math.BigInteger

class Output(val amount: BigInteger, val address: Address) {
    private var exitStarted: Boolean = false

    fun lockingScript() : String = "OP_DUP OP_HASH160 ${address.rawString()} OP_EQUALVERIFY OP_CHECKSIG"

    fun toHexString() = amount.toString(16).padStart(16, '0')

    fun exitStarted() = exitStarted
    fun markAsExitStarted() { exitStarted = true }
}