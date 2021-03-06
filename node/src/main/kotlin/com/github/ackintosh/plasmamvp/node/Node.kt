package com.github.ackintosh.plasmamvp.node

import com.github.ackintosh.plasmamvp.node.web3j.RootChain
import com.github.ackintosh.plasmamvp.utxo.Address
import com.github.ackintosh.plasmamvp.utxo.Chain
import com.github.ackintosh.plasmamvp.utxo.block.Block
import com.github.ackintosh.plasmamvp.utxo.block.BlockNumber
import com.github.ackintosh.plasmamvp.utxo.extensions.hexStringToByteArray
import com.github.ackintosh.plasmamvp.utxo.extensions.toHexString
import com.github.ackintosh.plasmamvp.utxo.merkletree.MerkleTree
import com.github.ackintosh.plasmamvp.utxo.transaction.CoinbaseData
import com.github.ackintosh.plasmamvp.utxo.transaction.GenerationInput
import com.github.ackintosh.plasmamvp.utxo.transaction.Output
import com.github.ackintosh.plasmamvp.utxo.transaction.OutputIndex
import com.github.ackintosh.plasmamvp.utxo.transaction.Transaction
import com.github.ackintosh.plasmamvp.utxo.transaction.TransactionVerificationService
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.protocol.http.HttpService
import org.web3j.tx.ClientTransactionManager
import org.web3j.tx.gas.DefaultGasProvider
import java.math.BigInteger
import java.util.logging.Logger

@kotlin.ExperimentalUnsignedTypes
class Node : Runnable {
    private val transactionPool : MutableList<Transaction> = mutableListOf()
    private val chain = Chain.from(ALICE_ADDRESS)

    override fun run() {
        onStart()

        while (true) {
            Thread.sleep(3000)
            createNewBlock()
        }
    }

    // TODO: race condition
    fun createNewBlock() : Boolean {
        if (transactionPool.isEmpty()) {
            logger.info("transaction pool is empty")
            return true
        }

        logger.info("Creating a new block...")
        val transactions = transactionPool.subList(0, transactionPool.size)
        // TODO: verify transactions
        // TODO: consistency of block number
        val block = Block(
            merkleRoot = MerkleTree.build(transactions.map { it.transactionHash() }),
            number = chain.nextChildBlockNumber(),
            transactions = transactions
        )
        logger.info("New block: $block")

        chain.add(block)
        logger.info("New block has been added into the chain. block_hash: $block")

        block.run {
            val transactionReceipt = rootChain()
                .submitBlock(this.merkleRoot.transactionHash.value.hexStringToByteArray())
                .send()
            logger.info("Submitted the plasma block to root chain. transaction receipt: $transactionReceipt")
        }

        chain.updateNextChildBlockNumber()
        transactionPool.clear()
        logger.info("Transaction pool has been cleared")

        return true
    }

    fun addTransaction(transaction: Transaction) =
        when (val result = TransactionVerificationService.verify(chain, transaction)) {
            is TransactionVerificationService.Result.Success -> {
                val added = transactionPool.add(transaction)
                if (added) {
                    logger.info("Added transaction into tx pool: ${transaction.transactionHash()}")
                } else {
                    logger.warning("Failed to add transaction into tx pool: ${transaction.transactionHash()}")
                }
                added
            }
            is TransactionVerificationService.Result.Failure -> {
                logger.warning("The transaction is invalid. tx_hash: ${transaction.transactionHash()} details: ${result.message}")
                false
            }
        }

    fun getGenesisBlock() = chain.genesisBlock()

    fun getLatestBlock() = chain.latestBlock()

    private fun onStart() {
        logger.info("Started Plasma Chain node")
        logger.info("address: $ALICE_ADDRESS")
        logger.info("private key (hex encoded): ${ALICE_KEY_PAIR.private.encoded.toHexString()}")
        logger.info("public key (hex encoded): ${ALICE_KEY_PAIR.public.encoded.toHexString()}")
        logger.info("Genesis block hash: ${getGenesisBlock()}")

        subscribeRootChainEvents()
    }

    private fun subscribeRootChainEvents() {
        rootChain().run {
            // DepositCreated
            this
                .depositCreatedEventFlowable(
                    DefaultBlockParameterName.EARLIEST,
                    DefaultBlockParameterName.LATEST
                )
                .subscribe({ log ->
                    logger.info("[DepositCreated] $log")

                    handleDepositedEvent(
                        Address.from(log.owner),
                        log.amount,
                        BlockNumber.from(log.blockNumber)
                    )
                }, { throw it }) // TODO: error handling

            // BlockSubmitted
            this
                .blockSubmittedEventFlowable(
                    DefaultBlockParameterName.EARLIEST,
                    DefaultBlockParameterName.LATEST
                )
                .subscribe({ log ->
                    logger.info("[BlockSubmitted] merkleRoot:${log.blockRoot.toHexString()}")
                }, { throw it }) // TODO: error handling

            // ExitStarted
            this
                .exitStartedEventFlowable(
                    DefaultBlockParameterName.EARLIEST,
                    DefaultBlockParameterName.LATEST
                ).subscribe({ log ->
                    logger.info("[ExitStarted] owner:${log.owner} blockNumber: ${log.blockNumber} txIndex: ${log.txIndex} outputIndex: ${log.outputIndex}")
                    handleExitStartedEvent(
                        BlockNumber.from(log.blockNumber),
                        log.txIndex,
                        OutputIndex.from(log.outputIndex)
                    )
                }, { throw it }) // TODO: error handling
        }
    }

    // TODO: race condition
    internal fun handleDepositedEvent(
        address: Address,
        amount: BigInteger,
        depositBlockNumber: BlockNumber
    ) {
        val generationTransaction = Transaction(
            input1 = GenerationInput(CoinbaseData("xxx")),
            output1 = Output(amount, address)
        )
        logger.info("Generation transaction: ${generationTransaction.transactionHash().value}")

        // TODO: obtain a block number from event data
        // TODO: consistency of block number
        val block = Block(
            merkleRoot = MerkleTree.build(listOf(generationTransaction.transactionHash())),
            number = depositBlockNumber,
            transactions = listOf(generationTransaction)
        )
        chain.add(block)
        logger.info("A deposit block has been added into plasma chain successfully. block: $block")
    }

    private fun handleExitStartedEvent(
        blockNumber: BlockNumber,
        transactionIndex: BigInteger,
        outputIndex: OutputIndex
    ) =
        when (chain.markAsExitStarted(blockNumber, transactionIndex, outputIndex)) {
            is Chain.MarkAsExitStarted.Success -> logger.info("$blockNumber has been marked as exit started")
            is Chain.MarkAsExitStarted.NotFound -> logger.warning("$blockNumber doesn't found")
        }

    private fun web3() = Web3j.build(HttpService())

    private fun rootChain() =
        RootChain.load(
            RootChain.getPreviouslyDeployedAddress(ROOT_CHAIN_CONTRACT_NETWORK_ID),
            web3(),
            ClientTransactionManager(web3(), OPERATOR_ADDRESS),
            DefaultGasProvider()
        )

    companion object {
        private val logger = Logger.getLogger(Node::class.java.name)
        val ALICE_KEY_PAIR = Address.generateKeyPair()
        private val ALICE_ADDRESS = Address.from(ALICE_KEY_PAIR)

        // see contract/build/contracts/RootChain.json
        private const val ROOT_CHAIN_CONTRACT_NETWORK_ID = "1557660506177"

        private const val OPERATOR_ADDRESS = "0xf17f52151EbEF6C7334FAD080c5704D77216b732"
    }
}
