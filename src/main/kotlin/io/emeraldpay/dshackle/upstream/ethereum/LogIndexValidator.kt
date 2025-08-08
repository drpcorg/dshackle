/**
 * Copyright (c) 2024 DRPC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.emeraldpay.dshackle.upstream.ethereum

import com.fasterxml.jackson.databind.JsonNode
import io.emeraldpay.dshackle.Global
import io.emeraldpay.dshackle.upstream.ChainRequest
import io.emeraldpay.dshackle.upstream.ChainResponse
import io.emeraldpay.dshackle.upstream.SingleValidator
import io.emeraldpay.dshackle.upstream.Upstream
import io.emeraldpay.dshackle.upstream.ValidateUpstreamSettingsResult
import io.emeraldpay.dshackle.upstream.rpcclient.ListParams
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import reactor.core.publisher.Mono
import reactor.kotlin.extra.retry.retryRandomBackoff
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
/**
 * Validator to detect incorrect logIndex numbering in Ethereum nodes.
 * * Some Erigon nodes (versions 3.0.8-3.0.14) return local logIndex within transaction
 * instead of global logIndex within block. This validator detects such misconfiguration.
 * * Detection principle:
 * - In correct implementation: logIndex is global across the entire block
 * - In buggy implementation: logIndex resets to 0 for each transaction
 * - Test: Check if the first log in the second transaction has logIndex = 0
 *         (which would indicate local numbering)
 */
class LogIndexValidator(
    private val upstream: Upstream,
) : SingleValidator<ValidateUpstreamSettingsResult> {

    private data class TransactionValidationData(
        val firstTxHash: String,
        val secondTxHash: String,
        val firstReceipt: JsonNode,
        val secondReceipt: JsonNode,
    )

    companion object {
        @JvmStatic
        val log: Logger = LoggerFactory.getLogger(LogIndexValidator::class.java)

        // Check every N validations to save resources
        private const val CHECK_FREQUENCY = 10
        private const val CHECK_TX_COUNT = 6

        // Maximum attempts to find a suitable block for validation
        private const val MAX_BLOCK_SEARCH_ATTEMPTS = 5
    }

    private val callCount = AtomicInteger(0)
    private val lastResult = AtomicReference(ValidateUpstreamSettingsResult.UPSTREAM_VALID)

    override fun validate(onError: ValidateUpstreamSettingsResult): Mono<ValidateUpstreamSettingsResult> {
        // Perform validation periodically to save resources
        val currentCount = callCount.incrementAndGet()
        if ((currentCount - 1) % CHECK_FREQUENCY != 0) {
            return Mono.just(lastResult.get())
        }

        log.debug("Starting logIndex validation for upstream ${upstream.getId()}, check #${currentCount / CHECK_FREQUENCY}")

        return findBlockWithLogsAndValidate()
            .doOnNext { result ->
                // Update lastResult only when we actually performed validation
                lastResult.set(result)
                if (result == ValidateUpstreamSettingsResult.UPSTREAM_FATAL_SETTINGS_ERROR) {
                    log.error("LogIndex validation failed for upstream ${upstream.getId()}, marking as fatal")
                }
            }
            .timeout(Duration.ofSeconds(15))
            .retryRandomBackoff(2, Duration.ofMillis(200), Duration.ofMillis(1000)) { ctx ->
                log.debug(
                    "Retry logIndex validation for ${upstream.getId()}, iteration ${ctx.iteration()}, " +
                        "error: ${ctx.exception().message}",
                )
            }
            .onErrorResume { err ->
                log.warn("Error during logIndex validation for ${upstream.getId()}: ${err.message}")
                // In case of error, return last known state to avoid false positives
                // We only mark as invalid if we explicitly detect the bug
                Mono.just(lastResult.get())
            }
    }

    /**
     * Find a suitable block with at least 2 transactions that have logs and validate it
     */
    private fun findBlockWithLogsAndValidate(): Mono<ValidateUpstreamSettingsResult> {
        // Try to use upstream's head height to avoid extra RPC call
        return try {
            val head = upstream.getHead()
            val currentHeight = head.getCurrentHeight()
            if (currentHeight != null && currentHeight > 0) {
                searchForSuitableBlock(currentHeight, 0)
            } else {
                // No head height available, skip validation
                log.debug("No head height available for ${upstream.getId()}, skipping validation")
                Mono.just(lastResult.get())
            }
        } catch (e: Exception) {
            // If getHead() is not available (e.g., in tests), fallback to RPC for compatibility
            getLatestBlockNumber()
                .flatMap { latestBlockNum ->
                    searchForSuitableBlock(latestBlockNum, 0)
                }
                .onErrorResume {
                    log.debug("Cannot get block number for ${upstream.getId()}: ${it.message}, skipping validation")
                    Mono.just(lastResult.get())
                }
        }
    }

    /**
     * Get the latest block number - used as fallback when head is not available
     */
    private fun getLatestBlockNumber(): Mono<Long> {
        return upstream.getIngressReader()
            .read(ChainRequest("eth_blockNumber", ListParams()))
            .flatMap(ChainResponse::requireStringResult)
            .map { it.removePrefix("0x").toLong(16) }
    }

    /**
     * Search backwards from the latest block to find one suitable for validation
     */
    private fun searchForSuitableBlock(
        startBlockNum: Long,
        attempt: Int,
    ): Mono<ValidateUpstreamSettingsResult> {
        if (attempt >= MAX_BLOCK_SEARCH_ATTEMPTS || startBlockNum <= 0) {
            log.debug("Could not find suitable block for logIndex validation in ${upstream.getId()} after $attempt attempts, returning last result: ${lastResult.get()}")
            return Mono.just(lastResult.get())
        }

        val blockNum = startBlockNum - attempt
        val blockHex = "0x${blockNum.toString(16)}"

        return getBlock(blockHex)
            .flatMap { block ->
                val transactions = block.get("transactions")
                if (transactions != null && transactions.isArray && transactions.size() >= 2) {
                    // We have at least 2 transactions, now check if they have logs
                    validateBlockTransactions(transactions)
                } else {
                    // Not enough transactions, try previous block
                    searchForSuitableBlock(startBlockNum, attempt + 1)
                }
            }
            .onErrorResume {
                // Error getting block, try previous one
                searchForSuitableBlock(startBlockNum, attempt + 1)
            }
    }

    /**
     * Validate transactions in a block to check for logIndex bug
     */
    private fun validateBlockTransactions(transactions: JsonNode): Mono<ValidateUpstreamSettingsResult> {
        // We need at least 2 transactions to validate
        if (transactions.size() < 2) {
            log.debug("Block has less than 2 transactions, cannot validate, returning last result: ${lastResult.get()}")
            return Mono.just(lastResult.get())
        }

        // Try to find two transactions with logs (receipts are already fetched)
        return findTwoTransactionsWithLogs(transactions)
            .map { validationData ->
                val firstLogs = validationData.firstReceipt.get("logs")
                val secondLogs = validationData.secondReceipt.get("logs")

                // Both transactions are guaranteed to have logs (found by search function)
                validateLogIndices(
                    firstLogs,
                    secondLogs,
                    validationData.firstTxHash,
                    validationData.secondTxHash,
                )
            }
            .defaultIfEmpty(lastResult.get())
    }

    /**
     * Find first two transactions that both have logs (not necessarily consecutive)
     * Returns validation data with receipts to avoid double RPC calls
     */
    private fun findTwoTransactionsWithLogs(transactions: JsonNode): Mono<TransactionValidationData> {
        // Search for first two transactions that have logs (not necessarily consecutive)
        val maxSearchCount = minOf(CHECK_TX_COUNT, transactions.size()) // Limit search to avoid excessive RPC calls

        return searchForTransactionsWithLogs(transactions, 0, maxSearchCount, mutableListOf())
    }

    /**
     * Recursively search for transactions with logs, checking receipts one by one
     * Stops as soon as we find two transactions with logs
     */
    private fun searchForTransactionsWithLogs(
        transactions: JsonNode,
        currentIndex: Int,
        maxSearchCount: Int,
        foundTxData: MutableList<Pair<String, JsonNode>>,
    ): Mono<TransactionValidationData> {
        // Stop if we found enough transactions or reached the limit
        if (foundTxData.size >= 2) {
            val first = foundTxData[0]
            val second = foundTxData[1]
            return Mono.just(
                TransactionValidationData(
                    firstTxHash = first.first,
                    secondTxHash = second.first,
                    firstReceipt = first.second,
                    secondReceipt = second.second,
                ),
            )
        }

        if (currentIndex >= maxSearchCount || currentIndex >= transactions.size()) {
            return Mono.empty() // Not enough transactions found
        }

        val txHash = transactions[currentIndex].get("hash")?.asText()
        if (txHash == null) {
            log.warn("Transaction at index $currentIndex has no hash, aborting validation due to corrupted block data")
            return Mono.empty()
        }

        return getTransactionReceipt(txHash)
            .flatMap { receipt ->
                val logs = receipt.get("logs")
                if (logs != null && logs.isArray && logs.size() > 0) {
                    // This transaction has logs, add it to our list
                    foundTxData.add(Pair(txHash, receipt))
                    log.debug("Found transaction with logs: $txHash (${logs.size()} logs)")
                }

                // Continue searching - either we have enough or need to find more
                searchForTransactionsWithLogs(transactions, currentIndex + 1, maxSearchCount, foundTxData)
            }
            .onErrorResume { error ->
                log.warn("Error getting receipt for $txHash: ${error.message}, aborting validation to avoid false results")
                Mono.empty()
            }
    }

    /**
     * Validate that logIndex is global across the block, not local to transaction
     */
    private fun validateLogIndices(
        firstTxLogs: JsonNode,
        secondTxLogs: JsonNode,
        firstTxHash: String,
        secondTxHash: String,
    ): ValidateUpstreamSettingsResult {
        // CRITICAL: We need BOTH transactions to have logs to detect the bug
        // If first tx has no logs and second tx starts at 0, that's CORRECT behavior

        // Parse all log indices safely
        val firstTxFirstLogIndex = if (firstTxLogs.size() > 0) {
            parseLogIndex(firstTxLogs[0].get("logIndex")?.asText())
        } else {
            null
        }

        val firstTxLastLogIndex = if (firstTxLogs.size() > 0) {
            parseLogIndex(firstTxLogs[firstTxLogs.size() - 1].get("logIndex")?.asText())
        } else {
            null
        }

        val secondTxFirstLogIndex = if (secondTxLogs.size() > 0) {
            parseLogIndex(secondTxLogs[0].get("logIndex")?.asText())
        } else {
            null
        }

        // Can't validate without proper data
        if (firstTxFirstLogIndex == null || firstTxLastLogIndex == null || secondTxFirstLogIndex == null) {
            log.debug(
                "Cannot validate logIndex for {}: missing log data, returning last result: {}",
                upstream.getId(),
                lastResult.get(),
            )
            return lastResult.get()
        }

        // Validation logic:
        // 1. First transaction's first log MUST start at 0 (error if not)
        // 2. Second transaction MUST continue from where first ended (error if resets to 0)

        if (firstTxFirstLogIndex != 0L) {
            // This is also a critical issue - first log in block should always be 0
            log.error(
                "Node ${upstream.getId()} has incorrect logIndex start in first transaction $firstTxHash: " +
                    "first log has logIndex=$firstTxFirstLogIndex instead of 0. " +
                    "This indicates a serious issue with logIndex numbering.",
            )
            return ValidateUpstreamSettingsResult.UPSTREAM_FATAL_SETTINGS_ERROR
        }

        // The key check: if first tx has logs AND second tx starts at 0, it's a BUG
        val expectedSecondTxStart = firstTxLastLogIndex + 1

        if (secondTxFirstLogIndex == 0L && firstTxLogs.size() > 0) {
            // This is the bug! Second transaction should not start at 0 when first has logs
            log.error(
                "Node ${upstream.getId()} uses LOCAL logIndex instead of GLOBAL. " +
                    "First tx ($firstTxHash) has ${firstTxLogs.size()} logs (indices 0-$firstTxLastLogIndex), " +
                    "but second tx ($secondTxHash) starts at logIndex=0 instead of $expectedSecondTxStart. " +
                    "This indicates Erigon bug with incorrect logIndex numbering.",
            )
            return ValidateUpstreamSettingsResult.UPSTREAM_FATAL_SETTINGS_ERROR
        }

        // Additional validation: check if indices are continuous
        if (secondTxFirstLogIndex != expectedSecondTxStart) {
            log.error(
                "Node ${upstream.getId()} has non-continuous logIndex between transactions $firstTxHash and $secondTxHash: " +
                    "second transaction starts at $secondTxFirstLogIndex, expected $expectedSecondTxStart. " +
                    "This indicates missing or incorrectly numbered logs.",
            )
            return ValidateUpstreamSettingsResult.UPSTREAM_FATAL_SETTINGS_ERROR
        }

        // If we got here, everything is correct
        return ValidateUpstreamSettingsResult.UPSTREAM_VALID
    }

    /**
     * Parse logIndex from hex string to Long
     * Returns null if parsing fails or input is invalid
     */
    private fun parseLogIndex(logIndexHex: String?): Long? {
        if (logIndexHex == null || logIndexHex.isBlank()) return null
        return try {
            val cleaned = logIndexHex.trim().removePrefix("0x").removePrefix("0X")
            if (cleaned.isEmpty()) {
                0L // "0x" or "0x0" -> 0
            } else {
                cleaned.toLong(16)
            }
        } catch (e: NumberFormatException) {
            log.warn("Failed to parse logIndex: $logIndexHex", e)
            null
        }
    }

    /**
     * Get block by number with full transaction details
     */
    private fun getBlock(blockNumber: String): Mono<JsonNode> {
        return upstream.getIngressReader()
            .read(ChainRequest("eth_getBlockByNumber", ListParams(blockNumber, true)))
            .flatMap(ChainResponse::requireResult)
            .map { Global.objectMapper.readTree(it) }
    }

    /**
     * Get transaction receipt by hash
     */
    private fun getTransactionReceipt(txHash: String): Mono<JsonNode> {
        return upstream.getIngressReader()
            .read(ChainRequest("eth_getTransactionReceipt", ListParams(txHash)))
            .flatMap(ChainResponse::requireResult)
            .map { Global.objectMapper.readTree(it) }
    }
}
