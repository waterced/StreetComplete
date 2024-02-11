package de.westnordost.streetcomplete.data.user.oauth

import de.westnordost.streetcomplete.ApplicationConstants
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.client.engine.mock.respondOk
import io.ktor.http.HeadersBuilder
import io.ktor.http.HttpStatusCode
import io.ktor.http.ParametersBuilder
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URL
import java.net.URLDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OAuthAuthorizationTest {
    @Test fun createAuthorizationUrl() {
        val url = URL(createOAuth().authorizationRequestUrl)

        assertEquals("https", url.protocol)
        assertEquals("test.me", url.host)
        assertEquals("/auth", url.path)

        val parameters = url.queryParameters
        assertEquals("code", parameters["response_type"])
        assertEquals("ClientId %#+!", parameters["client_id"])
        assertEquals("localhost://oauth", parameters["redirect_uri"])
        assertEquals("one! 2 THREE+(1/2)", parameters["scope"])
        assertEquals("S256", parameters["code_challenge_method"])
        assertTrue(parameters["code_challenge"]!!.length <= 128)
        assertTrue(parameters["code_challenge"]!!.length >= 43)
    }

    @Test fun `createAuthorizationUrl with state`() {
        val parameters = URL(createOAuth("123").authorizationRequestUrl).queryParameters
        assertEquals("123", parameters["state"])
    }

    @Test fun `generates different code challenge for each instance`() {
        val url1 = URL(createOAuth().authorizationRequestUrl)
        val url2 = URL(createOAuth().authorizationRequestUrl)
        assertTrue(url1.queryParameters["code_challenge"] != url2.queryParameters["code_challenge"])
    }

    @Test fun `serializes correctly`() {
        val oauth1 = createOAuth()
        val oauth1String = Json.encodeToString(oauth1)
        val oauth2 = Json.decodeFromString<OAuthAuthorizationParams>(oauth1String)
        val oauth2String = Json.encodeToString(oauth2)

        assertEquals(oauth1String, oauth2String)
    }

    @Test fun `itsForMe with state`() {
        val state = "123"
        val oauth = createOAuth(state)

        assertFalse(oauth.itsForMe("This isn't::::a valid URL"))
        assertFalse(oauth.itsForMe("localhost://oauth")) // no state
        assertFalse(oauth.itsForMe("localhost://oauth?state=abc")) // different state
        assertTrue(oauth.itsForMe("localhost://oauth?state=$state")) // same state
        // different uri
        assertFalse(oauth.itsForMe("localhost://oauth3?state=$state"))
        assertFalse(oauth.itsForMe("localhost://oauth/path?state=$state"))
        assertFalse(oauth.itsForMe("localboost://oauth?state=$state"))
    }

    @Test fun `itsForMe without state`() {
        val oauth = createOAuth()

        assertTrue(oauth.itsForMe("localhost://oauth")) // no state
        assertFalse(oauth.itsForMe("localhost://oauth?state=abc")) // different state
        // different uri
        assertFalse(oauth.itsForMe("localhost://oauth3"))
        assertFalse(oauth.itsForMe("localhost://oauth/path"))
        assertFalse(oauth.itsForMe("localboost://oauth"))
    }

    @Test fun `extractAuthorizationCode fails with useful error messages`() {
        // server did not respond correctly with "error"
        assertFailsWith<OAuthConnectionException> {
            extractAuthorizationCode("localhost://oauth?e=something")
        }

        try {
            extractAuthorizationCode("localhost://oauth?error=hey%2Bwhat%27s%2Bup")
        } catch (e: OAuthException) {
            assertEquals("hey what's up", e.message)
        }

        try {
            extractAuthorizationCode("localhost://oauth?error=A%21&error_description=B%21")
        } catch (e: OAuthException) {
            assertEquals("A!: B!", e.message)
        }

        try {
            extractAuthorizationCode("localhost://oauth?error=A%21&error_uri=http%3A%2F%2Fabc.de")
        } catch (e: OAuthException) {
            assertEquals("A! (see http://abc.de)", e.message)
        }

        try {
            extractAuthorizationCode("localhost://oauth?error=A%21&error_description=B%21&error_uri=http%3A%2F%2Fabc.de")
        } catch (e: OAuthException) {
            assertEquals("A!: B! (see http://abc.de)", e.message)
        }
    }

    @Test fun extractAuthorizationCode() {
        assertEquals(
            "my code",
            extractAuthorizationCode("localhost://oauth?code=my%20code")
        )
    }

    @Test fun `retrieveAccessToken generates valid access token`() = runBlocking {
        val service = OAuthService(HttpClient(MockEngine { _ -> respondOk("""{
            "access_token": "TOKEN",
            "token_type": "bearer",
            "scope": "A B C"
        }""")
        }))

        val tokenResponse = service.retrieveAccessToken(dummyOAuthAuthorization(), "")

        assertEquals(AccessTokenResponse("TOKEN", listOf("A", "B", "C")), tokenResponse)
    }

    @Test fun `retrieveAccessToken throws OAuthConnectionException with invalid response token_type`(): Unit = runBlocking {
        val service = OAuthService(HttpClient(MockEngine { _ -> respondOk("""{
            "access_token": "TOKEN",
            "token_type": "an_unusual_token_type",
            "scope": "A B C"
        }""")
        }))

        val exception = assertFailsWith<OAuthConnectionException> { service.retrieveAccessToken(dummyOAuthAuthorization(), "") }

        assertEquals("OAuth 2 token endpoint returned an unknown token type (an_unusual_token_type)", exception.message)
    }

    @Test fun `retrieveAccessToken throws OAuthException when error response`(): Unit = runBlocking {
        val service = OAuthService(HttpClient(MockEngine { _ ->
            respondError(
                HttpStatusCode.BadRequest, """{
                    "error": "Missing auth code",
                    "error_description": "Please specify a code",
                    "error_uri": "code"
                }"""
            )
        }))

        val exception = assertFailsWith<OAuthException> {
            service.retrieveAccessToken(dummyOAuthAuthorization(), "")
        }

        assertEquals("Missing auth code", exception.error)
        assertEquals("Please specify a code", exception.description)
        assertEquals("code", exception.uri)
        assertEquals("Missing auth code: Please specify a code (see code)", exception.message)
    }

    @Test fun `retrieveAccessToken generates correct request URL`(): Unit = runBlocking {
        val mockEngine = MockEngine { _ -> respondOk() }
        val auth = OAuthAuthorizationParams(
            "",
            "https://www.openstreetmap.org",
            "OAuthClientId",
            listOf(),
            "scheme://there"
        )

        assertFails { OAuthService(HttpClient(mockEngine)).retrieveAccessToken(auth, "C0D3") }

        val expectedParams = ParametersBuilder()
        expectedParams.append("grant_type", "authorization_code")
        expectedParams.append("client_id", "OAuthClientId")
        expectedParams.append("code", "C0D3")
        expectedParams.append("redirect_uri", "scheme://there")
        expectedParams.append("code_verifier", auth.codeVerifier)

        assertEquals(1, mockEngine.requestHistory.size)
        assertEquals(expectedParams.build(), mockEngine.requestHistory[0].url.parameters)
        assertEquals("www.openstreetmap.org", mockEngine.requestHistory[0].url.host)
    }

    @Test fun `retrieveAccessToken generates request headers`(): Unit = runBlocking {
        val mockEngine = MockEngine { _ -> respondOk() }

        assertFails { OAuthService(HttpClient(mockEngine)).retrieveAccessToken(dummyOAuthAuthorization(), "") }

        val expectedHeaders = HeadersBuilder()
        expectedHeaders.append("User-Agent", ApplicationConstants.USER_AGENT)
        expectedHeaders.append("Content-Type", "application/x-www-form-urlencoded")
        expectedHeaders.append("Accept-Charset", "UTF-8")
        expectedHeaders.append("Accept", "*/*")

        assertEquals(1, mockEngine.requestHistory.size)
        assertEquals(expectedHeaders.build(), mockEngine.requestHistory[0].headers)
    }
}

private fun dummyOAuthAuthorization() = OAuthAuthorizationParams("", "", "", listOf(), "")

private fun createOAuth(state: String? = null) = OAuthAuthorizationParams(
    "https://test.me/auth",
    "https://test.me/token",
    "ClientId %#+!",
    listOf("one!", "2", "THREE+(1/2)"),
    "localhost://oauth",
    state
)

private val URL.queryParameters get(): Map<String, String> =
    query.split('&').associate {
        val parts = it.split('=')
        parts[0] to URLDecoder.decode(parts[1], "US-ASCII")
    }
