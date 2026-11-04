package com.ziyair.jarvis

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Meals: a week of cheap, healthy dinners that are quick to make (newborn at home), the grocery list built from it,
 * and which store is cheapest using Supplies' prices. Makes next week's plan by itself every Sunday.
 */
object Meals {
    private val dayName = SimpleDateFormat("EEEE", Locale.US)

    fun plan(c: Context): JSONObject = try { JSONObject(Prefs(c).sp.getString("meal_plan", "{}")) } catch (_: Exception) { JSONObject() }
    private fun savePlan(c: Context, o: JSONObject) = Prefs(c).sp.edit().putString("meal_plan", o.toString()).apply()
    fun on(c: Context) = Agents.on(c) && Agents.enabled(c, "meals")

    /** Monday of the week [at] falls in, as yyyy-MM-dd. Pure. */
    fun weekOf(at: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = at; firstDayOfWeek = Calendar.MONDAY; set(Calendar.DAY_OF_WEEK, Calendar.MONDAY) }
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
    }

    @Synchronized fun save(c: Context, day0: String, name: String, ingredients: String): String {
        val day = day0.trim().lowercase().replaceFirstChar { it.uppercase() }.let { d -> listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday").firstOrNull { it.startsWith(d.take(3)) } }
            ?: return "Which day?"
        val p = plan(c)
        val week = if (p.optString("week") == weekOf(System.currentTimeMillis()) || p.optString("week") == p.optString("next")) p.optString("week") else weekOf(System.currentTimeMillis())
        if (p.optString("week") != week) { p.put("week", week); p.put("meals", JSONObject()) }
        val meals = p.optJSONObject("meals") ?: JSONObject().also { p.put("meals", it) }
        meals.put(day, JSONObject().put("name", name.trim().take(60)).put("ing", ingredients.trim().take(300)))
        savePlan(c, p); LogBus.changed()
        return "$day: ${name.trim()}."
    }

    fun week(c: Context): String {
        val m = plan(c).optJSONObject("meals") ?: return "No meal plan yet."
        if (m.length() == 0) return "No meal plan yet."
        val order = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
        return order.filter { m.has(it) }.joinToString("; ") { "$it: ${m.getJSONObject(it).optString("name")} (${m.getJSONObject(it).optString("ing")})" } + "."
    }

    fun tonight(c: Context): String {
        val m = plan(c).optJSONObject("meals")?.optJSONObject(dayName.format(Date())) ?: return "Nothing planned for tonight yet."
        return "Tonight: ${m.optString("name")}. You'll need ${m.optString("ing")}."
    }

    /** Puts every ingredient in the plan on the shopping list (skipping what's already there). */
    fun toList(c: Context): String {
        val m = plan(c).optJSONObject("meals") ?: return "No meal plan yet."
        val items = LinkedHashSet<String>()
        for (k in m.keys()) m.getJSONObject(k).optString("ing").split(Regex(",|;| and ")).map { it.trim().lowercase() }.filter { it.length >= 3 }.forEach { items.add(it) }
        if (items.isEmpty()) return "The plan has no ingredients written down."
        val out = Lists.add(c, items.joinToString(", "), null)
        Supplies.noticeListAdd(c, items.joinToString(", "))
        return out
    }

    /** Which store came out cheapest on the last price check: the one with the most lowest prices. Pure (on Supplies' data). */
    fun cheapestStore(items: JSONArray): String? {
        val count = HashMap<String, Int>()
        for (i in 0 until items.length()) items.getJSONObject(i).optJSONObject("last")?.optString("store")?.takeIf { it.isNotBlank() }?.let { s ->
            val k = s.lowercase().split(" ").first(); count[k] = (count[k] ?: 0) + 1 }
        val best = count.maxByOrNull { it.value } ?: return null
        val name = (0 until items.length()).mapNotNull { items.getJSONObject(it).optJSONObject("last")?.optString("store") }.first { it.lowercase().startsWith(best.key) }
        return "$name had the lowest price on ${best.value} of the ${count.values.sum()} things I've checked."
    }

    fun cheapest(c: Context): String = cheapestStore(Supplies.all(c)) ?: "I haven't checked grocery prices yet; I'll know after the next weekly check, or say 'check prices now'."

    /** Every Sunday from 9 AM: next week's plan, made once, without being asked (one small Claude call). */
    fun maybePlanWeek(c: Context) {
        if (!on(c)) return
        val cal = Calendar.getInstance()
        if (cal.get(Calendar.DAY_OF_WEEK) != Calendar.SUNDAY || cal.get(Calendar.HOUR_OF_DAY) < 9) return
        val next = weekOf(System.currentTimeMillis() + 2 * 86_400_000L)
        val p = Prefs(c)
        if (p.sp.getString("meals_auto_week", "") == next) return
        p.sp.edit().putString("meals_auto_week", next).apply()
        Thread { try {
            val out = Agents.run(c, "meals", "Plan next week's dinners (Monday to Sunday) and save each one with meal_save. Don't add anything to the shopping list yet.", false)
            val pl = plan(c); pl.put("week", next); pl.put("fresh", true); savePlan(c, pl)
            Trace.log(c, "meals", "planned next week: ${out.take(120)}")
        } catch (e: Exception) { Problems.log(c, "meals: ${e.message}") } }.start()
    }

    fun promptBlock(c: Context): String {
        if (!on(c)) return ""
        val m = plan(c).optJSONObject("meals")?.optJSONObject(dayName.format(Date())) ?: return ""
        return "\nTonight's dinner on the meal plan: ${m.optString("name")}."
    }
}

/**
 * Money, watched for him: the purchase notifications his bank, card and Cash App already send are read on the phone
 * (no Claude call), sorted into gas, groceries, food, bills, subscriptions, shopping and transfers, and kept on the
 * phone only. Spots repeat charges, says where the money went, and gives a one-line recap on Monday mornings.
 */
object Spending {
    data class Tx(val amount: Double, val merchant: String, val spend: Boolean)

    private val SKIP = Regex("(?i)\\b(code|verification|verify|otp|password|sign[- ]in|login|declined|security alert|suspicious|available balance is|balance:|low balance|statement is ready|due on|is due|minimum payment|will be charged)\\b")
    private val SPEND = Regex("(?i)\\b(you spent|spent|purchase|purchased|charged|card was used|transaction|paid|payment to|you sent|sent|debit|withdrawal|pos)\\b")
    private val INCOME = Regex("(?i)\\b(received|deposit|deposited|credited|refund|refunded|paid you|sent you|direct deposit|cashed out|transfer from)\\b")

    /** A purchase or money-in notification, or null. Pure, unit-tested. */
    fun parse(text: String): Tx? {
        if (text.length > 400 || SKIP.containsMatchIn(text)) return null
        val amount = Regex("\\$\\s?([0-9]{1,3}(?:,[0-9]{3})*(?:\\.[0-9]{2})?)").find(text)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull() ?: return null
        if (amount <= 0 || amount > 20000) return null
        val income = INCOME.containsMatchIn(text) && !Regex("(?i)you sent|payment to").containsMatchIn(text)
        if (!income && !SPEND.containsMatchIn(text)) return null
        val merchant = (Regex("(?i)\\b(?:at|to|from|with)\\s+([A-Z0-9][A-Za-z0-9&'.*#\\- ]{1,40}?)(?:\\s+(?:on|for|using|with|ending|card)\\b|[.,!]|$)").find(text)?.groupValues?.get(1)
            ?: "unknown").trim().replace(Regex("\\s+"), " ").trimEnd('.', '*', '#').take(40)
        return Tx(amount, merchant, !income)
    }

    private val CATS = listOf(
        "gas" to "shell|chevron|arco|exxon|76|maverik|conoco|holiday|circle k|sinclair|costco gas|fuel|gas station|cenex|pilot|loves",
        "groceries" to "winco|fred meyer|safeway|rosauers|yoke|grocery outlet|albertsons|aldi|trader joe|walmart|costco|super 1|bargain giant|grocery|market",
        "food" to "mcdonald|taco|chipotle|starbucks|dutch bros|doordash|uber eats|grubhub|pizza|burger|wendy|sonic|subway|panda|chick-fil|jack in the box|arby|kfc|popeyes|restaurant|cafe|grill|wingstop|raising cane",
        "subscriptions" to "netflix|spotify|hulu|disney|youtube|apple\\.com|apple music|icloud|google one|google storage|prime video|amazon prime|xbox|playstation|chatgpt|openai|anthropic|claude|paramount|max\\.com|hbo|peacock|crunchyroll|patreon|onlyfans|snapchat\\+|duolingo",
        "bills" to "t-mobile|tmobile|verizon|at&t|att|avista|insurance|progressive|geico|state farm|rent|apartments|comcast|xfinity|spectrum|water|utility|cash app repayment|afterpay|klarna|affirm",
        "shopping" to "amazon|target|shein|temu|best buy|ebay|etsy|nike|ross|tj maxx|marshalls|old navy|walgreens|cvs|dollar",
        "transfers" to "cash app|venmo|zelle|paypal")

    /** Gas, groceries, food, bills, subscriptions, shopping, transfers or other. Pure. */
    fun category(merchant: String): String { val m = merchant.lowercase(); return CATS.firstOrNull { Regex("\\b(${it.second})").containsMatchIn(m) }?.first ?: "other" }

    fun all(c: Context): JSONArray = try { JSONArray(Prefs(c).sp.getString("spend_tx", "[]")) } catch (_: Exception) { JSONArray() }
    fun on(c: Context) = Agents.on(c) && Agents.enabled(c, "money") && Brain(c).config().optBoolean("spending_watch", true)

    @Synchronized fun capture(c: Context, app: String, title: String, text: String) {
        if (!on(c) || LifeWatch.workApp(app)) return
        val tx = parse("$title. $text") ?: return
        val a = all(c); val now = System.currentTimeMillis()
        // banks often send "pending" then "posted": same amount and place within an hour is one purchase
        for (i in a.length() - 1 downTo maxOf(0, a.length() - 20)) { val o = a.getJSONObject(i)
            if (now - o.optLong("at") < 3600_000L && o.optDouble("a") == tx.amount && o.optString("m").equals(tx.merchant, true)) return }
        Timeline.add(c, "purchase", "${if (tx.spend) "Paid" else "Money in"} ${money(tx.amount)} ${tx.merchant}")
        a.put(JSONObject().put("at", now).put("a", tx.amount).put("m", tx.merchant).put("c", if (tx.spend) category(tx.merchant) else "income").put("app", app.take(30)))
        val keep = JSONArray(); for (i in maxOf(0, a.length() - 1500) until a.length()) keep.put(a.get(i))
        Prefs(c).sp.edit().putString("spend_tx", keep.toString()).apply()
        LogBus.changed()
    }

    private fun money(v: Double) = "$" + (if (v >= 100) String.format(Locale.US, "%,.0f", v) else String.format(Locale.US, "%.2f", v))

    /** Where the money went between [from] and [to]: total, by category (biggest first), money in. Pure over the data. */
    fun summary(txs: List<JSONObject>, from: Long, to: Long): String {
        val w = txs.filter { it.optLong("at") in from until to }
        val spent = w.filter { it.optString("c") != "income" }
        if (spent.isEmpty()) return "No purchases picked up."
        val by = spent.groupBy { it.optString("c") }.mapValues { e -> e.value.sumOf { it.optDouble("a") } }.entries.sortedByDescending { it.value }
        val total = spent.sumOf { it.optDouble("a") }
        val inc = w.filter { it.optString("c") == "income" }.sumOf { it.optDouble("a") }
        val top = spent.groupBy { it.optString("m") }.mapValues { e -> e.value.sumOf { it.optDouble("a") } }.entries.maxByOrNull { it.value }
        return "Spent ${money(total)}: " + by.take(5).joinToString(", ") { "${it.key} ${money(it.value)}" } +
            (top?.let { ". Most at ${it.key} (${money(it.value)})" } ?: "") + (if (inc > 0) ". Money in: ${money(inc)}" else "") + "."
    }

    fun week(c: Context, lastWeek: Boolean = false): String {
        val a = all(c); val txs = (0 until a.length()).map { a.getJSONObject(it) }
        val mon = Calendar.getInstance().apply { firstDayOfWeek = Calendar.MONDAY; set(Calendar.DAY_OF_WEEK, Calendar.MONDAY); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }.timeInMillis
        val (from, to) = if (lastWeek) (mon - 7 * 86_400_000L) to mon else mon to System.currentTimeMillis() + 1
        return (if (lastWeek) "Last week: " else "This week: ") + summary(txs, from, to) +
            (if (txs.isEmpty()) " I read purchase notifications from your bank, card and Cash App as they arrive, so this fills in over the next few days." else "")
    }

    /** Charges that keep coming back about monthly at about the same amount (or are known subscription services). Pure. */
    fun repeats(txs: List<JSONObject>): List<String> {
        val out = ArrayList<String>()
        for ((m, list) in txs.filter { it.optString("c") != "income" }.groupBy { it.optString("m").lowercase() }) {
            if (m == "unknown") continue
            val sorted = list.sortedBy { it.optLong("at") }
            val monthly = sorted.zipWithNext().any { (x, y) -> val gap = (y.optLong("at") - x.optLong("at")) / 86_400_000L; gap in 25..35 && Math.abs(x.optDouble("a") - y.optDouble("a")) <= maxOf(1.0, x.optDouble("a") * 0.1) }
            if (monthly || sorted.last().optString("c") == "subscriptions") out.add("${sorted.last().optString("m")} ${money(sorted.last().optDouble("a"))}")
        }
        return out
    }

    fun subscriptions(c: Context): String {
        val a = all(c); val l = repeats((0 until a.length()).map { a.getJSONObject(it) })
        return if (l.isEmpty()) "No repeat charges spotted yet; it takes about a month of notifications to see them." else "Repeat charges: " + l.joinToString("; ") + ". Any you don't use any more are worth cancelling."
    }

    /** Monday morning, once: last week's recap, for Jarvis to mention at the end of his first reply. */
    fun mondayLine(c: Context): String? {
        if (!on(c)) return null
        val cal = Calendar.getInstance()
        if (cal.get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY || cal.get(Calendar.HOUR_OF_DAY) !in 5..13) return null
        val key = Meals.weekOf(System.currentTimeMillis())
        if (Prefs(c).sp.getString("money_recap_said", "") == key || all(c).length() == 0) return null
        return week(c, lastWeek = true)
    }
    fun mondaySaid(c: Context) { if (mondayLine(c) != null) Prefs(c).sp.edit().putString("money_recap_said", Meals.weekOf(System.currentTimeMillis())).apply() }

    fun promptBlock(c: Context): String = mondayLine(c)?.let { "\nMonday money recap (say it once, in one short sentence, at the end of your first reply today): $it" } ?: ""
}
