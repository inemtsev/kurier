package kurier.twitch

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TwitchAuthTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun `validate refreshes an expired token and retries with the new one`() = runTest {
        var validateCalls = 0
        var refreshCalls = 0
        val api = TwitchApi(
            clientId = "cid",
            accessToken = "expired",
            clientSecret = "secret",
            refreshToken = "refresh",
            engine = MockEngine { request ->
                val url = request.url.toString()
                when {
                    "oauth2/validate" in url -> {
                        validateCalls++
                        if (validateCalls == 1) {
                            respond("""{"status":401,"message":"invalid access token"}""", HttpStatusCode.Unauthorized, jsonHeaders)
                        } else {
                            respond("""{"user_id":"42","login":"bot"}""", HttpStatusCode.OK, jsonHeaders)
                        }
                    }

                    "oauth2/token" in url -> {
                        refreshCalls++
                        respond("""{"access_token":"fresh","refresh_token":"r2"}""", HttpStatusCode.OK, jsonHeaders)
                    }

                    else -> respond("", HttpStatusCode.NotFound)
                }
            },
        )

        val result = api.validate()

        assertEquals("42", result.userId)
        assertEquals(1, refreshCalls, "should refresh exactly once on the 401")
        assertEquals(2, validateCalls, "should retry validate after refreshing")
    }

    @Test
    fun `validate raises a clear error on an invalid token when refresh is unavailable`() = runTest {
        val api = TwitchApi(
            clientId = "cid",
            accessToken = "expired",
            // no clientSecret/refreshToken — refresh can't run
            engine = MockEngine {
                respond("""{"status":401,"message":"invalid access token"}""", HttpStatusCode.Unauthorized, jsonHeaders)
            },
        )

        val failure = assertFailsWith<TwitchApiException> { api.validate() }

        assertEquals(401, failure.status)
        assertContains(failure.message.orEmpty(), "invalid access token")
    }
}
