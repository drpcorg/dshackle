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
/**
 * Validator to detect incorrect logIndex numbering in Ethereum nodes.
 * 
 * Some Erigon nodes (versions 3.0.8-3.0.14) return local logIndex within transaction
 * instead of global logIndex within block. This validator detects such misconfiguration.
 * 
 * Detection principle:
 * - In correct implementation: logIndex is global across the entire block
 * - In buggy implementation: logIndex resets to 0 for each transaction
 * - Test: Check if the first log in the second transaction has logIndex = 0
 *         (which would indicate local numbering)
 */
class LogIndexValidator(
    private val upstream: Upstream,
) : SingleValidator<ValidateUpstreamSettingsResult> {

    companion object {
        @JvmStatic
        val log: Logger = LoggerFactory.getLogger(LogIndexValidator::class.java)
        
        // Check every N validations to save resources
        private const val CHECK_FREQUENCY = 10
        
        // Maximum attempts to find a suitable block for validation
        private const val MAX_BLOCK_SEARCH_ATTEMPTS = 5
    }

    private var callCount: Int = 0
    override fun validate(onError: ValidateUpstreamSettingsResult): Mono<ValidateUpstreamSettingsResult> {
        // Perform validation periodically to save resources
        if (callCount % CHECK_FREQUENCY != 0) {
            callCount++
            return Mono.just(ValidateUpstreamSettingsResult.UPSTREAM_VALID)
        }
        callCount++

        return findBlockWithLogsAndValidate()
            .timeout(Duration.ofSeconds(15))
            .retryRandomBackoff(2, Duration.ofMillis(200), Duration.ofMillis(1000)) { ctx ->
                log.debug(
                    "Retry logIndex validation for ${upstream.getId()}, iteration ${ctx.iteration()}, " +
                    "error: ${ctx.exception().message}"
                )
            }
            .onErrorResume { err ->
                log.debug("Error during logIndex validation for ${upstream.getId()}: ${err.message}")
                // In case of error, assume valid to avoid false positives
                Mono.just(ValidateUpstreamSettingsResult.UPSTREAM_VALID)
            }
    }

    /**
     * Find a suitable block with at least 2 transactions that have logs and validate it
     */
    private fun findBlockWithLogsAndValidate(): Mono<ValidateUpstreamSettingsResult> {
        return getLatestBlockNumber()
            .flatMap { latestBlockNum ->
                searchForSuitableBlock(latestBlockNum, 0)
            }    }

    /**
     * Search backwards from the latest block to find one suitable for validation
     */
    private fun searchForSuitableBlock(
        startBlockNum: Long,
        attempt: Int
    ): Mono<ValidateUpstreamSettingsResult> {
        if (attempt >= MAX_BLOCK_SEARCH_ATTEMPTS || startBlockNum <= 0) {
            log.debug("Could not find suitable block for logIndex validation in ${upstream.getId()} after $attempt attempts")
            return Mono.just(ValidateUpstreamSettingsResult.UPSTREAM_VALID)
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
        // Get first two transaction hashes
        val firstTxHash = transactions[0].get("hash")?.asText()
        val secondTxHash = transactions[1].get("hash")?.asText()

        if (firstTxHash == null || secondTxHash == null) {
            return Mono.just(ValidateUpstreamSettingsResult.UPSTREAM_VALID)
        }

        // Get receipts for both transactions
        return Mono.zip(
            getTransactionReceipt(firstTxHash),
            getTransactionReceipt(secondTxHash)
        ).map { receipts ->
            val firstReceipt = receipts.t1
            val secondReceipt = receipts.t2

            val firstLogs = firstReceipt.get("logs")
            val secondLogs = secondReceipt.get("logs")

            // Check if both transactions have logs
            if (firstLogs == null || !firstLogs.isArray || firstLogs.size() == 0 ||
                secondLogs == null || !secondLogs.isArray || secondLogs.size() == 0) {
                // Can't validate without logs in both transactions
                ValidateUpstreamSettingsResult.UPSTREAM_VALID
            } else {
                // Perform the actual validation
                validateLogIndices(firstLogs, secondLogs)
            }
        }
    }
    /**
     * Validate that logIndex is global across the block, not local to transaction
     */
    private fun validateLogIndices(firstTxLogs: JsonNode, secondTxLogs: JsonNode): ValidateUpstreamSettingsResult {
        // Get the first log index from the first transaction
        val firstTxFirstLogIndex = parseLogIndex(firstTxLogs[0].get("logIndex")?.asText())
        
        // Get the last log index from the first transaction
        val firstTxLastLogIndex = parseLogIndex(firstTxLogs[firstTxLogs.size() - 1].get("logIndex")?.asText())
        
        // Get the first log index from the second transaction
        val secondTxFirstLogIndex = parseLogIndex(secondTxLogs[0].get("logIndex")?.asText())

        // Validation logic:
        // 1. First transaction's first log should start at 0
        // 2. Second transaction's first log should continue from where first transaction ended
        
        if (firstTxFirstLogIndex != 0L) {
            log.warn(
                "Node ${upstream.getId()} has unexpected logIndex start: " +
                "first transaction's first log has logIndex=$firstTxFirstLogIndex instead of 0"
            )
        }

        if (secondTxFirstLogIndex == 0L) {
            // This is the bug! Second transaction should not start at 0
            log.error(
                "Node ${upstream.getId()} uses LOCAL logIndex instead of GLOBAL. " +
                "Second transaction in block has logIndex=0x0 for first log. " +
                "This indicates Erigon bug with incorrect logIndex numbering. " +
                "First tx logs: ${firstTxLogs.size()}, last logIndex: $firstTxLastLogIndex. " +
                "Second tx first logIndex: $secondTxFirstLogIndex (should be ${firstTxLastLogIndex + 1})"
            )
            return ValidateUpstreamSettingsResult.UPSTREAM_FATAL_SETTINGS_ERROR
        }
        // Additional validation: second tx should continue from where first ended
        val expectedSecondTxStart = firstTxLastLogIndex + 1
        if (secondTxFirstLogIndex != expectedSecondTxStart) {
            log.warn(
                "Node ${upstream.getId()} has non-continuous logIndex: " +
                "second transaction starts at $secondTxFirstLogIndex, expected $expectedSecondTxStart"
            )
            // This might be a different issue, but not the specific bug we're looking for
        }

        return ValidateUpstreamSettingsResult.UPSTREAM_VALID
    }

    /**
     * Parse logIndex from hex string to Long
     */
    private fun parseLogIndex(logIndexHex: String?): Long {
        if (logIndexHex == null) return -1
        return try {
            logIndexHex.removePrefix("0x").toLong(16)
        } catch (e: NumberFormatException) {
            log.warn("Failed to parse logIndex: $logIndexHex")
            -1
        }
    }

    /**
     * Get the latest block number
     */
    private fun getLatestBlockNumber(): Mono<Long> {
        return upstream.getIngressReader()
            .read(ChainRequest("eth_blockNumber", ListParams()))
            .flatMap(ChainResponse::requireStringResult)
            .map { it.removePrefix("0x").toLong(16) }
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
