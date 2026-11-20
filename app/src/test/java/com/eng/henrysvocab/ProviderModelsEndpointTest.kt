package com.eng.henrysvocab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderModelsEndpointTest {
    @Test
    fun usesTheProjectsDefaultV1BaseWithoutDuplicatingTheVersion() {
        assertEquals("https://api.deepseek.com/v1/models", providerModelsEndpoint(ModelConfig()))
    }

    @Test
    fun preservesVersionInChatPathAndConfiguredOrigin() {
        val config = ModelConfig(
            baseUrl = "https://provider.example:8443/gateway/",
            path = " /v1/chat/completions ",
            model = "",
        )
        assertEquals("https://provider.example:8443/gateway/v1/models", providerModelsEndpoint(config))
    }

    @Test
    fun nonstandardChatPathFallsBackToTheBasePath() {
        val config = ModelConfig(
            baseUrl = " https://provider.example/v2/ ",
            path = "/custom/chat/completions/stream",
        )
        assertEquals("https://provider.example/v2/models", providerModelsEndpoint(config))
    }

    @Test
    fun rejectsInvalidBaseUrlsEvenOnFallback() {
        for (baseUrl in listOf(
            "http://provider.example/v1",
            "https://provider.example@other.example/v1",
            "https://provider.example/v1?key=value",
        )) {
            assertNull(providerModelsEndpoint(ModelConfig(baseUrl = baseUrl, path = "/custom")))
        }
    }

    @Test
    fun rejectsOriginSwitchesAndTraversalInInferredPaths() {
        for (path in listOf(
            "//other.example/v1/chat/completions",
            "https://other.example/v1/chat/completions",
            "/../v1/chat/completions",
        )) {
            assertNull(providerModelsEndpoint(ModelConfig(path = path)))
        }
    }
}
