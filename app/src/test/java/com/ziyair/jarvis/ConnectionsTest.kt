package com.ziyair.jarvis

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The connections: Gmail, Drive backup, YouTube, Telegram, Maps, Nest, ElevenLabs and the Google sign-in. */
class ConnectionsTest {
    // ---------- keys never live in the repo ----------
    @Test fun noKeysInTheRepo() {
        val bad = Regex("(sk-ant-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{35}|GOCSPX-[A-Za-z0-9_-]{20,}|[0-9]{8,10}:AA[0-9A-Za-z_-]{30,}|github_pat_[A-Za-z0-9_]{30,}|gsk_[A-Za-z0-9]{30,})")
        val hits = File("src").walkTopDown().filter { it.isFile && it.extension in setOf("kt", "xml", "json", "html", "js") }
            .flatMap { f -> bad.findAll(f.readText()).map { "${f.name}: ${it.value.take(12)}…" } }.toList()
        assertTrue("keys found in source: $hits", hits.isEmpty())
    }

    @Test fun everyConnectionKeyHasASetupField() {
        val layout = File("src/main/res/layout/activity_main.xml").readText()
        for (id in listOf("googleClientId", "googleClientSecret", "backupPass", "youtubeKey", "youtubeChannel", "telegramToken", "mapsKey", "nestProject", "elevenKey", "elevenVoice"))
            assertTrue("missing setup field $id", layout.contains("@+id/$id\""))
    }

    // ---------- Google sign-in ----------
    @Test fun pkceMatchesTheRfcExample() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", GoogleAuth.pkceChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }

    @Test fun readsTheBrowserRedirect() {
        val q = GoogleAuth.parseRequestLine("GET /?state=abc123&code=4%2F0AQ_xyz&scope=email HTTP/1.1")
        assertEquals("4/0AQ_xyz", q["code"]); assertEquals("abc123", q["state"])
        assertTrue(GoogleAuth.parseRequestLine("GET /favicon.ico HTTP/1.1").isEmpty())
        assertEquals("access_denied", GoogleAuth.parseRequestLine("GET /?error=access_denied&state=s HTTP/1.1")["error"])
    }

    @Test fun signInUrls() {
        val g = GoogleAuth.authUrl(GoogleAuth.GOOGLE, "id.apps", "http://127.0.0.1:5000", "st", "ch")
        assertTrue(g.startsWith("https://accounts.google.com/o/oauth2/v2/auth?"))
        assertTrue(g.contains("gmail.readonly") && g.contains("drive.appdata") && g.contains("access_type=offline") && g.contains("code_challenge_method=S256"))
        assertFalse("never asks to send or change mail", g.contains("gmail.send") || g.contains("gmail.modify"))
        val n = GoogleAuth.authUrl(GoogleAuth.NEST, "id.apps", "http://127.0.0.1:5000", "st", "ch", "proj-1")
        assertTrue(n.startsWith("https://nestservices.google.com/partnerconnections/proj-1/auth?") && n.contains("sdm.service"))
    }

    // ---------- Gmail ----------
    @Test fun senderNames() {
        assertEquals("Amazon.com", Gmail.senderName("\"Amazon.com\" <auto-confirm@amazon.com>"))
        assertEquals("avista", Gmail.senderName("bills@avista.com"))
        assertEquals("avista", Gmail.senderName("<no-reply@mail.avista.com>"))
    }

    @Test fun receipts() {
        val r = Gmail.receipt("Walmart <help@walmart.com>", "Thanks for your order", "Order total: \$54.21. Items: diapers, wipes")
        assertNotNull(r); assertEquals("Walmart", r!!.store); assertEquals(54.21, r.amount!!, 0.001)
        assertNull("shipping emails go to Packages, not receipts", Gmail.receipt("Amazon <a@amazon.com>", "Your order has shipped", "Your order #123 has shipped"))
        assertNull("work pay isn't a receipt", Gmail.receipt("DoorDash <no-reply@doordash.com>", "Your order receipt", "You paid \$12.00"))
        assertNull(Gmail.receipt("Mom <mom@gmail.com>", "dinner sunday?", "are you guys coming"))
        assertEquals(20.0, Gmail.receipt("Spotify <no-reply@spotify.com>", "Your receipt", "Subtotal \$18.00 Total \$20.00")!!.amount!!, 0.001)
    }

    @Test fun gmailIsReadOnly() {
        val src = File("src/main/java/com/ziyair/jarvis/Gmail.kt").readText()
        for (bad in listOf("/send", "/trash", "/modify", "\"DELETE\"", "batchDelete")) assertFalse("Gmail must never $bad", src.contains(bad))
    }

    // ---------- Drive backup ----------
    @Test fun backupRoundTripAndWrongPassphrase() {
        val plain = "his memory, lists and baby log".toByteArray()
        val blob = DriveBackup.encrypt(plain, "correct horse battery")
        assertFalse(String(blob).contains("baby log"))
        assertArrayEquals(plain, DriveBackup.decrypt(blob, "correct horse battery"))
        assertNull(DriveBackup.decrypt(blob, "wrong passphrase"))
        assertNull(DriveBackup.decrypt("not a backup at all, really not".toByteArray(), "x"))
        assertArrayEquals(plain, DriveBackup.gunzip(DriveBackup.gzip(plain)))
    }

    @Test fun backupNeverHoldsKeys() {
        val all = mapOf("claude_key" to "sk-ant-x", "telegram_token" to "1:AA", "maps_key" to "k", "eleven_key" to "k", "google_client_secret" to "s",
            "g_refresh_google" to "r", "g_access_nest" to "a", "backup_pass" to "p", "github_token" to "t", "youtube_key" to "y",
            "memory" to "[{\"t\":\"likes Fear by NF\"}]", "running" to true, "agent_count_money" to 4, "life_swept" to 123L, "rate" to 1.5f, "tags" to setOf("a", "b"))
        val j = DriveBackup.pack(all, "{\"t\":1}", 5L)
        val p = j.getJSONObject("prefs")
        for (k in listOf("claude_key", "telegram_token", "maps_key", "eleven_key", "google_client_secret", "g_refresh_google", "g_access_nest", "backup_pass", "github_token", "youtube_key"))
            assertFalse("$k must never be in the backup", p.has(k))
        assertEquals("s", p.getJSONObject("memory").getString("t")); assertEquals("b", p.getJSONObject("running").getString("t"))
        assertEquals("i", p.getJSONObject("agent_count_money").getString("t")); assertEquals("l", p.getJSONObject("life_swept").getString("t"))
        assertEquals("ss", p.getJSONObject("tags").getString("t")); assertEquals("{\"t\":1}", j.getString("timeline"))
        for (k in Prefs::class.java.declaredMethods.map { it.name }.filter { it.startsWith("get") && it.endsWith("Key") }) assertTrue(k.isNotBlank())
    }

    // ---------- YouTube ----------
    @Test fun youtubeChannels() {
        assertEquals("forHandle=%40marcus", YouTube.channelQuery("@marcus"))
        assertEquals("forHandle=%40marcus", YouTube.channelQuery("https://www.youtube.com/@marcus/"))
        assertEquals("id=UCabcdefghijklmnopqrstuv", YouTube.channelQuery("https://youtube.com/channel/UCabcdefghijklmnopqrstuv"))
        val ch = YouTube.parseChannel("""{"items":[{"snippet":{"title":"Marcus"},"statistics":{"subscriberCount":"1520","viewCount":"98000","videoCount":"12","hiddenSubscriberCount":false},"contentDetails":{"relatedPlaylists":{"uploads":"UUxyz"}}}]}""")!!
        assertEquals(1520L, ch.subs); assertEquals("UUxyz", ch.uploads)
        assertNull(YouTube.parseChannel("""{"items":[]}"""))
        assertEquals(" (up 20)", YouTube.delta(1520, 1500)); assertEquals("", YouTube.delta(5, null)); assertEquals("1.2M", YouTube.n(1_234_567)); assertEquals("98K", YouTube.n(98_000))
    }

    // ---------- Telegram ----------
    @Test fun telegramOnlyAnswersHim() {
        val ups = Telegram.parseUpdates("""{"ok":true,"result":[
            {"update_id":7,"message":{"chat":{"id":42,"type":"private"},"from":{"id":42,"first_name":"Marcus"},"text":"/start 482913"}},
            {"update_id":8,"message":{"chat":{"id":99,"type":"private"},"from":{"id":99},"text":"/start"}},
            {"update_id":9,"message":{"chat":{"id":-500,"type":"group"},"from":{"id":42},"text":"/start 482913"}},
            {"update_id":10,"message":{"chat":{"id":42,"type":"private"},"from":{"id":42},"text":"what's on my list"}}]}""")
        assertEquals(4, ups.size); assertEquals(42L, ups[0].chat); assertEquals("Marcus", ups[0].name); assertEquals(42L, ups[0].from)
        assertTrue("his code pairs", Telegram.pairs("482913", ups[0]))
        assertFalse("a bare /start from a stranger never pairs", Telegram.pairs("482913", ups[1]))
        assertFalse("a group can never pair", Telegram.pairs("482913", ups[2]))
        assertFalse("wrong code", Telegram.pairs("111111", ups[0]))
        assertTrue(Telegram.allowed(42L, 42L, ups[3]))
        assertFalse("strangers are ignored", Telegram.allowed(42L, 42L, ups[1]))
        assertFalse("not in a group, even from him", Telegram.allowed(42L, 42L, ups[2]))
        assertFalse("nobody before pairing", Telegram.allowed(0L, 0L, ups[3]))
        assertEquals(3, Telegram.chunks("x".repeat(9000)).size)
    }

    @Test fun googleConsentGaps() {
        assertTrue(GoogleAuth.missing("https://www.googleapis.com/auth/gmail.readonly https://www.googleapis.com/auth/drive.appdata").isEmpty())
        assertEquals(listOf("Drive backup"), GoogleAuth.missing("https://www.googleapis.com/auth/gmail.readonly"))
    }

    @Test fun telegramSendIsOnlyThePhoneAgents() {
        assertFalse(Agents.CORE.contains("telegram_send")); assertTrue(Agents.PHONE.tools.contains("telegram_send"))
    }

    // ---------- Maps ----------
    @Test fun routesAndTraffic() {
        val r = Maps.parseRoute("""{"routes":[{"duration":"1500s","staticDuration":"1080s","distanceMeters":19950}]}""")!!
        assertEquals(1500L, r.seconds)
        assertEquals("To Costco: 25 min (12.4 miles), about 7 min slower than usual because of traffic.", Maps.describe("Costco", r))
        assertTrue(Maps.describe("Costco", r.copy(seconds = 1100)).endsWith("traffic's normal."))
        assertEquals("1 h 5 min", Maps.mins(3900)); assertEquals("2 h", Maps.mins(7200))
        assertNull(Maps.parseRoute("{}"))
    }

    @Test fun cheapestGasFirst() {
        val body = """{"places":[
          {"displayName":{"text":"Shell"},"formattedAddress":"1 Division St, Spokane","location":{"latitude":47.66,"longitude":-117.41},"fuelOptions":{"fuelPrices":[{"type":"REGULAR_UNLEADED","price":{"units":"3","nanos":899000000}}]}},
          {"displayName":{"text":"Costco Gas"},"formattedAddress":"2 Hwy, Spokane","location":{"latitude":47.70,"longitude":-117.40},"fuelOptions":{"fuelPrices":[{"type":"PREMIUM","price":{"units":"4"}},{"type":"REGULAR_UNLEADED","price":{"units":"3","nanos":459000000}}]}},
          {"displayName":{"text":"No Price Mart"},"formattedAddress":"3 Ave","location":{"latitude":47.65,"longitude":-117.42}}]}"""
        val list = Maps.rankGas(Maps.parseGas(body, 47.658, -117.426))
        assertEquals("Costco Gas", list[0].name); assertEquals(3.459, list[0].regular!!, 0.0001)
        assertEquals("No Price Mart", list[2].name); assertNull(list[2].regular)
        assertTrue(list[0].miles!! in 1.0..5.0)
    }

    // ---------- Nest ----------
    @Test fun nestDevices() {
        val d = Nest.parse("""{"devices":[{"name":"enterprises/p/devices/d1","type":"sdm.devices.types.THERMOSTAT","traits":{
            "sdm.devices.traits.Info":{"customName":""},"sdm.devices.traits.Temperature":{"ambientTemperatureCelsius":21.0},
            "sdm.devices.traits.ThermostatMode":{"mode":"HEAT"},"sdm.devices.traits.ThermostatTemperatureSetpoint":{"heatCelsius":20.0}},
            "parentRelations":[{"displayName":"Hallway"}]}]}""")
        assertEquals(1, d.size); assertEquals("Hallway", d[0].label)
        assertEquals("Hallway (thermostat): 70°F inside, mode heat, heat set to 68°F", Nest.describe(d[0]))
        assertEquals(20.0, Nest.c(68.0), 0.01)
        assertTrue(Nest.parse("{}").isEmpty())
        val heating = Nest.parse("""{"devices":[{"name":"n","type":"sdm.devices.types.THERMOSTAT","traits":{"sdm.devices.traits.Info":{"customName":"Hall"},"sdm.devices.traits.ThermostatHvac":{"status":"HEATING"}}}]}""")
        assertEquals("Hall (thermostat): heating", Nest.describe(heating[0]))
    }

    // ---------- ElevenLabs ----------
    @Test fun elevenLabsStepsAsideOnAccountTrouble() {
        assertTrue(ElevenVoice.accountProblem(401, "{\"detail\":{\"status\":\"quota_exceeded\"}}"))
        assertTrue(ElevenVoice.accountProblem(400, "quota_exceeded"))
        assertFalse(ElevenVoice.accountProblem(500, "server error"))
        assertFalse(ElevenVoice.accountProblem(429, "too many concurrent requests"))
    }

    @Test fun newAgentsCanNeverSend() {
        val senders = setOf("send_text", "reply_message", "call", "phone_task", "answer_call", "telegram_send")
        for (d in listOf(Agents.INBOX, Agents.ROADS, Agents.HEALTH)) assertTrue((d.tools intersect senders).isEmpty())
        assertFalse("health stays out of memory", Agents.HEALTH.tools.contains("remember"))
        assertNotNull(JSONObject("{}"))
    }
}
