package com.agrotech.oviapp.notifications

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
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
}
