package com.agrotech.oviapp.notifications

import com.agrotech.oviapp.auth.data.KtorAuthService
import com.agrotech.oviapp.auth.data.TokenRefreshInterceptor
import com.agrotech.oviapp.auth.domain.AuthSession
import com.agrotech.oviapp.auth.domain.SessionStorage
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KtorDeviceTokenApiTest {

    @Test
    fun postsTheTokenAsJsonWithTheUserBearerToken() = runTest {
        var method: HttpMethod? = null
        var url: String? = null
        var authorization: String? = null
        var body: String? = null
        val engine = MockEngine { request ->
            method = request.method
            url = request.url.toString()
            authorization = request.headers["Authorization"]
            body = (request.body as TextContent).text
            respond(content = "", status = HttpStatusCode.NoContent)
        }
        val api = KtorDeviceTokenApi(HttpClient(engine), baseUrl = "https://api.baapp.mx/", accessToken = { "jwt-123" })

        api.register(DeviceTokenRequest(token = "fcm-token", platform = "ANDROID"))

        assertEquals(HttpMethod.Post, method)
        assertEquals("https://api.baapp.mx/api/v1/notifications/device-token", url)
        assertEquals("Bearer jwt-123", authorization)
        assertEquals("""{"token":"fcm-token","platform":"ANDROID"}""", body)
    }

    @Test
    fun aServerErrorFailsSoTheRegistrarRetriesLater() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.ServiceUnavailable) }
        val api = KtorDeviceTokenApi(HttpClient(engine), baseUrl = "https://api.baapp.mx", accessToken = { "jwt-123" })

        assertFailsWith<DeviceTokenRegistrationException> {
            api.register(DeviceTokenRequest(token = "fcm-token", platform = "ANDROID"))
        }
    }

    @Test
    fun baseUrlWithApiV1DoesNotDuplicatePathAndRefreshesExpiredJwtOn401() = runTest {
        val storage = FakeSessionStorage(
            AuthSession(
                accessToken = "expired-jwt",
                refreshToken = "refresh-1",
                username = "dueno",
                role = "OWNER",
                ranchId = "r1",
                sessionId = "sess-1",
            )
        )
        val seenUrls = mutableListOf<String>()
        val seenAuth = mutableListOf<String?>()
        val apiClient = HttpClient(MockEngine { request ->
            seenUrls += request.url.toString()
            seenAuth += request.headers[HttpHeaders.Authorization]
            if (request.headers[HttpHeaders.Authorization] == "Bearer expired-jwt") {
                respondError(HttpStatusCode.Unauthorized)
            } else {
                respond(content = "", status = HttpStatusCode.NoContent)
            }
        })
        val refreshClient = HttpClient(MockEngine { request ->
            assertEquals("${KtorAuthService.DEFAULT_BASE_URL}/auth/refresh", request.url.toString())
            respond(
                content = """{"accessToken":"fresh-jwt","refreshToken":"refresh-2"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val interceptor = TokenRefreshInterceptor(storage, KtorAuthService.DEFAULT_BASE_URL, refreshClient)
        interceptor.attachTo(apiClient)

        val api = KtorDeviceTokenApi(
            httpClient = apiClient,
            baseUrl = KtorAuthService.DEFAULT_BASE_URL,
            accessToken = { storage.getSession()?.accessToken },
        )
        api.register(DeviceTokenRequest(token = "fcm-token", platform = "ANDROID"))

        val expectedEndpoint = "${KtorAuthService.DEFAULT_BASE_URL}/notifications/device-token"
        assertEquals(listOf(expectedEndpoint, expectedEndpoint), seenUrls)
        assertEquals(listOf<String?>("Bearer expired-jwt", "Bearer fresh-jwt"), seenAuth)
        assertEquals("fresh-jwt", storage.getSession()?.accessToken)
    }

    private class FakeSessionStorage(private var session: AuthSession?) : SessionStorage {
        override fun saveSession(session: AuthSession) { this.session = session }
        override fun getSession(): AuthSession? = session
        override fun clearSession() { session = null }
        override fun hasSession(): Boolean = session != null
        override fun updateSessionIfMatches(
            expectedSessionId: String,
            expectedRefreshToken: String?,
            expectedAccessToken: String?,
            transform: (AuthSession) -> AuthSession,
        ): Boolean {
            val cur = session ?: return false
            if (cur.sessionId != expectedSessionId || cur.refreshToken != expectedRefreshToken) return false
            if (expectedAccessToken != null && cur.accessToken != expectedAccessToken) return false
            session = transform(cur)
            return true
        }
        override fun clearSessionIfMatches(
            expectedSessionId: String,
            expectedRefreshToken: String?,
            expectedAccessToken: String?,
        ): Boolean {
            val cur = session ?: return false
            if (cur.sessionId != expectedSessionId || cur.refreshToken != expectedRefreshToken) return false
            if (expectedAccessToken != null && cur.accessToken != expectedAccessToken) return false
            session = null
            return true
        }
    }
}
