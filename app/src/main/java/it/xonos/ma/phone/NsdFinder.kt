package it.xonos.ma.phone

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import it.xonos.ma.phone.model.FoundServer
import it.xonos.ma.phone.model.ServerFinder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Cerca Music Assistant nella rete con il servizio mDNS di Android (NsdManager), senza permessi
 * in più. Gli annunci trovati si risolvono uno alla volta (le versioni vecchie di Android non ne
 * sopportano due insieme) e i loro campi TXT dicono nome, versione e indirizzi del server.
 */
class NsdFinder(context: Context) : ServerFinder {

    private val nsd = context.getSystemService(NsdManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    private val _servers = MutableStateFlow<List<FoundServer>>(emptyList())
    override val servers: StateFlow<List<FoundServer>> = _servers.asStateFlow()

    private val _searching = MutableStateFlow(false)
    override val searching: StateFlow<Boolean> = _searching.asStateFlow()

    private var listener: NsdManager.DiscoveryListener? = null
    private val waiting = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    // Il nome mDNS di ogni server trovato, per toglierlo quando sparisce.
    private val names = mutableMapOf<String, String>()

    // Quante schermate vogliono la ricerca. Fra due pagine delle impostazioni (schede Home e Libreria) la nuova chiede
    // start() prima che la vecchia, che sta uscendo, chieda stop(): si smette solo quando l'ultima non la vuole più.
    private var users = 0

    override fun start() {
        users++
        if (listener != null || nsd == null) return
        val discovery = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onServiceFound(info: NsdServiceInfo) {
                main.post { enqueue(info) }
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                main.post {
                    val id = names.remove(info.serviceName) ?: return@post
                    _servers.value = _servers.value.filter { it.id != id }
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                main.post { _searching.value = false }
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                main.post {
                    Log.w(TAG, "Ricerca di Music Assistant non partita ($errorCode)")
                    listener = null
                    _searching.value = false
                }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        listener = discovery
        _searching.value = true
        try {
            nsd.discoverServices(FoundServer.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery)
        } catch (e: Exception) {
            Log.w(TAG, "Ricerca di Music Assistant non partita", e)
            listener = null
            _searching.value = false
        }
    }

    override fun stop() {
        if (users > 0) users--
        if (users > 0) return
        val discovery = listener ?: return
        listener = null
        waiting.clear()
        runCatching { nsd?.stopServiceDiscovery(discovery) }
        _searching.value = false
    }

    private fun enqueue(info: NsdServiceInfo) {
        if (listener == null) return
        waiting.addLast(info)
        next()
    }

    private fun next() {
        if (resolving || listener == null) return
        val info = waiting.removeFirstOrNull() ?: return
        resolving = true
        @Suppress("DEPRECATION")
        nsd?.resolveService(
            info,
            object : NsdManager.ResolveListener {
                override fun onServiceResolved(resolved: NsdServiceInfo) {
                    main.post {
                        resolving = false
                        found(resolved)
                        next()
                    }
                }

                override fun onResolveFailed(failed: NsdServiceInfo, errorCode: Int) {
                    main.post {
                        resolving = false
                        // Già in risoluzione da un'altra parte: si riprova tra poco.
                        if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE) main.postDelayed({ enqueue(failed) }, 500)
                        next()
                    }
                }
            },
        )
    }

    private fun found(info: NsdServiceInfo) {
        val txt = info.attributes.mapValues { (_, bytes) -> bytes?.toString(Charsets.UTF_8) }
        @Suppress("DEPRECATION")
        val host = info.host?.hostAddress
        val server = FoundServer.from(txt, host, info.port) ?: return
        names[info.serviceName] = server.id
        _servers.value = _servers.value.filter { it.id != server.id } + server
    }

    private companion object {
        const val TAG = "Xonos"
    }
}
