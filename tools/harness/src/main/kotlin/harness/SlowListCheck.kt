package harness

import it.xonos.ma.data.MaClient
import it.xonos.ma.data.MaException
import it.xonos.ma.data.Problem
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONArray

/**
 * Gli elenchi lunghi che arrivano a pezzi lenti ([MaClient], tempo contato dall'ultimo pezzo) contro un finto
 * Music Assistant (tools/harness/slow_ma.py, SLOW_URL): un elenco di 3100 elementi in 6 pezzi distanti 0,8 s con
 * 2 s di tempo arriva intero, come trasformato e come JSON; uno che si ferma a metà finisce con "non risponde"; i
 * pezzi di una richiesta lasciata non restano in memoria.
 */
fun main(): Unit = runBlocking {
    val url = System.getenv("SLOW_URL") ?: "http://127.0.0.1:18095"
    var failures = 0
    fun check(name: String, ok: Boolean, detail: String = "") {
        println((if (ok) "OK   " else "MALE ") + name + if (detail.isNotEmpty()) ": $detail" else "")
        if (!ok) failures++
    }
    val http = OkHttpClient()
    val client = MaClient(http, "en_US")
    client.connect(url, "finto")

    var t = System.nanoTime()
    var parts = 0
    val items = client.items("slow/list", 2_000, { it.optString("item_id") }, onPart = { parts++ })
    check("Elenco trasformato intero, oltre il tempo concesso", items.size == 3100 && parts == 6, "${items.size} in ${(System.nanoTime() - t) / 1_000_000} ms, $parts pezzi")

    t = System.nanoTime()
    val json = client.command("slow/list", 2_000) as? JSONArray
    check("Elenco JSON intero", json?.length() == 3100, "${json?.length()} in ${(System.nanoTime() - t) / 1_000_000} ms")

    t = System.nanoTime()
    val stalled = runCatching { client.command("slow/stall", 1_500) { put("parts", 2); put("gap", 0.5) } }.exceptionOrNull()
    val ms = (System.nanoTime() - t) / 1_000_000
    check("Elenco fermo a metà: non risponde", (stalled as? MaException)?.problem == Problem.NO_ANSWER && ms in 2_300..3_500, "$ms ms")

    val abandoned = runCatching { client.command("slow/abandon", 500) { put("parts", 3); put("gap", 1.0) } }.exceptionOrNull()
    check("Richiesta lasciata: non risponde", (abandoned as? MaException)?.problem == Problem.NO_ANSWER)
    delay(4_500)
    val field = MaClient::class.java.getDeclaredField("partials").apply { isAccessible = true }
    val partials = field.get(client) as Map<*, *>
    check("I pezzi arrivati dopo non restano in memoria", partials.isEmpty(), "${partials.size} elenchi")

    client.close()
    http.dispatcher.executorService.shutdown()
    println(if (failures == 0) "Tutto bene" else "$failures prove non passate")
    kotlin.system.exitProcess(if (failures == 0) 0 else 1)
}
