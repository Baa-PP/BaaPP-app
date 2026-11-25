package com.agrotech.oviapp.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.agrotech.oviapp.db.OviAppDatabase
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OfflineSyncEngineTest {

    private lateinit var database: OviAppDatabase
    private lateinit var queue: PendingSyncQueue
    private var nowMillis = 1_795_361_405_000L
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeTest
    fun setUp() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        OviAppDatabase.Schema.create(driver)
        database = OviAppDatabase(driver)
        queue = PendingSyncQueue(database, nowMillis = { nowMillis })
    }

    @Test
    fun sendsOrderedBatchToSyncEndpointAndMarksSyncedByMutationId() = runTest {
        val m1 = queue.writeAndEnqueue(SyncEntityType.ANIMAL, SyncOperation.CREATE, """{"id":"a1"}""") {}
        val m2 = queue.writeAndEnqueue(SyncEntityType.BIRTH_EVENT, SyncOperation.CREATE, """{"id":"b1"}""") {}
        val m3 = queue.writeAndEnqueue(SyncEntityType.WEIGHT_MEASUREMENT, SyncOperation.CREATE, """{"id":"w1"}""") {}

        var capturedMethod: HttpMethod? = null
        var capturedUrl: String? = null
        var capturedAuth: String? = null
        var capturedBody: SyncBatchRequest? = null

        val api = KtorSyncBatchApi(
            httpClient = HttpClient(MockEngine { request ->
                capturedMethod = request.method
                capturedUrl = request.url.toString()
                capturedAuth = request.headers[HttpHeaders.Authorization]
                capturedBody = json.decodeFromString<SyncBatchRequest>((request.body as TextContent).text)
                respond(
                    content = """
                        {
                          "results": [
                            {"mutation_id": "${m1.mutationId}", "status": "APPLIED"},
                            {"mutation_id": "${m2.mutationId}", "status": "DUPLICATE"},
                            {"mutation_id": "${m3.mutationId}", "status": "APPLIED"}
                          ]
                        }
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            }),
            baseUrl = "https://api.baapp.mx/api/v1",
            accessToken = { "jwt-token" },
        )

        val engine = OfflineSyncEngine(queue = queue, api = api)
        val outcome = engine.syncPending()

        assertEquals(SyncOutcome.Success(syncedCount = 3, failedCount = 0), outcome)
        assertEquals(HttpMethod.Post, capturedMethod)
        assertEquals("https://api.baapp.mx/api/v1/sync/batch", capturedUrl)
        assertEquals("Bearer jwt-token", capturedAuth)
        assertEquals(
            listOf(m1.mutationId, m2.mutationId, m3.mutationId),
            capturedBody?.mutations?.map { it.mutationId },
        )
        assertEquals(0L, queue.pendingCount())
    }

    @Test
    fun retryAfterFailureDoesNotResendAlreadySyncedChunks() = runTest {
        val m1 = queue.writeAndEnqueue(SyncEntityType.ANIMAL, SyncOperation.CREATE, """{"id":"a1"}""") {}
        val m2 = queue.writeAndEnqueue(SyncEntityType.WEIGHT_MEASUREMENT, SyncOperation.CREATE, """{"id":"w1"}""") {}

        var callCount = 0
        val sentBatches = mutableListOf<List<String>>()
        val api = KtorSyncBatchApi(
            httpClient = HttpClient(MockEngine { request ->
                callCount += 1
                val body = json.decodeFromString<SyncBatchRequest>((request.body as TextContent).text)
                sentBatches += body.mutations.map { it.mutationId }
                when (callCount) {
                    1 -> respond(
                        content = """{"results":[{"mutation_id":"${m1.mutationId}","status":"APPLIED"}]}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                    2 -> respondError(HttpStatusCode.ServiceUnavailable)
                    else -> respond(
                        content = """{"results":[{"mutation_id":"${m2.mutationId}","status":"ALREADY_PROCESSED"}]}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
            }),
            baseUrl = "https://api.baapp.mx",
            accessToken = { "jwt-token" },
        )

        val engine = OfflineSyncEngine(queue = queue, api = api, batchSize = 1)

        val firstAttempt = engine.syncPending()
        assertIs<SyncOutcome.RetryLater>(firstAttempt)
        assertEquals(listOf(m2.mutationId), queue.pending().map { it.mutationId })

        val secondAttempt = engine.syncPending()
        assertEquals(SyncOutcome.Success(syncedCount = 1, failedCount = 0), secondAttempt)
        assertEquals(0L, queue.pendingCount())
        assertEquals(listOf(listOf(m1.mutationId), listOf(m2.mutationId), listOf(m2.mutationId)), sentBatches)
    }

    @Test
    fun returnsNothingToSyncOrNotAuthenticatedWithoutCallingApi() = runTest {
        var called = false
        val api = object : SyncBatchApi {
            override suspend fun sendBatch(request: SyncBatchRequest): SyncBatchResponse {
                called = true
                return SyncBatchResponse()
            }
        }
        val engine = OfflineSyncEngine(queue = queue, api = api)

        assertEquals(SyncOutcome.NothingToSync, engine.syncPending())
        queue.writeAndEnqueue(SyncEntityType.ANIMAL, SyncOperation.CREATE, "{}") {}
        assertEquals(SyncOutcome.NotAuthenticated, engine.syncPending(isAuthenticated = { false }))
        assertTrue(!called)
    }
}
