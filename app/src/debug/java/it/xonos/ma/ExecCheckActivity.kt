package it.xonos.ma

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Solo nella build di prova: esegue un comando da dentro il processo di Xonos, cioè con i suoi permessi, il
 * suo dominio SELinux e il suo filtro seccomp, come farebbe l'app vera. Il comando è in files/exec-args (un
 * argomento per riga), l'ambiente in files/exec-env (NOME=valore per riga). @LIB@, @FILES@ e @CACHE@
 * diventano la cartella delle librerie dell'app (l'unica da cui Android lascia avviare un programma), files/
 * e cache/. L'uscita va nel registro col tag XonosExec, riga per riga, e alla fine "fine: <codice>".
 * Serve alla prova di Soloist con proot (workflow "Soloist", tools/soloist/emulator.sh).
 */
class ExecCheckActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            setPadding(48, 96, 48, 48)
            text = "Prova di un comando dentro Xonos"
        })
        val lib = applicationInfo.nativeLibraryDir
        fun expand(s: String) = s.replace("@LIB@", lib).replace("@FILES@", filesDir.path).replace("@CACHE@", cacheDir.path)
        val args = File(filesDir, "exec-args").readLines().filter { it.isNotEmpty() }.map(::expand)
        val env = File(filesDir, "exec-env").takeIf { it.isFile }?.readLines().orEmpty().filter { '=' in it }
        thread(name = "exec-check") {
            val code = try {
                val builder = ProcessBuilder(args).redirectErrorStream(true).directory(filesDir)
                env.forEach { builder.environment()[it.substringBefore('=')] = expand(it.substringAfter('=')) }
                Log.i(TAG, "avvio: ${args.joinToString(" ")}")
                val process = builder.start()
                // L'uscita si legge a parte: un programma che si blocca non deve bloccare anche l'attesa.
                val reader = thread(name = "exec-output") {
                    runCatching { process.inputStream.bufferedReader().forEachLine { Log.i(TAG, it) } }
                }
                if (process.waitFor(TIMEOUT_S, TimeUnit.SECONDS)) {
                    reader.join(2_000)
                    process.exitValue()
                } else {
                    Log.i(TAG, "dopo $TIMEOUT_S s non è finito: lo fermo")
                    process.destroyForcibly()
                    -1
                }
            } catch (e: Exception) {
                Log.i(TAG, "errore: $e")
                -2
            }
            Log.i(TAG, "fine: $code")
        }
    }

    companion object {
        private const val TAG = "XonosExec"
        private const val TIMEOUT_S = 60L
    }
}
