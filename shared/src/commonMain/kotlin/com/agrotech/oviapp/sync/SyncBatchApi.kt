package com.agrotech.oviapp.sync

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

interface SyncBatchApi {
    suspend fun sendBatch(request: SyncBatchRequest): SyncBatchResponse
}

class SyncBatchHttpException(
    val statusCode: Int,
    message: String,
) : Exception(message)

class SyncBatchAuthException(message: String) : Exception(message)

/**
 * Cliente Ktor para `POST /api/v1/sync/batch` (BE-011).
 * Acepta `baseUrl` con o sin sufijo `/api/v1`.
 */
class KtorSyncBatchApi(
    private val httpClient: HttpClient,
    baseUrl: String,
    private val accessToken: suspend () -> String?,
) : SyncBatchApi {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    private val endpoint = baseUrl.trimEnd('/').removeSuffix("/api/v1") + "/api/v1/sync/batch"

    override suspend fun sendBatch(request: SyncBatchRequest): SyncBatchResponse {
        val jwt = accessToken()?.takeIf { it.isNotBlank() }
            ?: throw SyncBatchAuthException("No hay sesión activa para sincronizar.")

        val response: HttpResponse = httpClient.post(endpoint) {
            bearerAuth(jwt)
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(request))
        }

        val bodyText = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw SyncBatchHttpException(
                statusCode = response.status.value,
                message = "El servidor respondió ${response.status.value} al sincronizar el lote.",
            )
        }

        if (bodyText.isBlank()) {
            return SyncBatchResponse()
        }
        return json.decodeFromString<SyncBatchResponse>(bodyText)
    }
}
