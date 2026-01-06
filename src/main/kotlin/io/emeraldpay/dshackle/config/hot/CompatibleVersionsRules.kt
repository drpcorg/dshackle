package io.emeraldpay.dshackle.config.hot

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class CompatibleVersionsRules(
    @param:JsonProperty("rules")
    val rules: List<CompatibleVersionsRule>,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CompatibleVersionsRule(
    @param:JsonProperty("client")
    val client: String,
    @param:JsonProperty("blacklist")
    val blacklist: List<String>?,
    @param:JsonProperty("whitelist")
    val whitelist: List<String>?,
)
