package it.xonos.ma.data

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import it.xonos.ma.XonosApp

/**
 * Passa l'account di Music Assistant di Xonos a myCar, che ha Xonos dentro di sé: indirizzi, token
 * e nome utente, così lì non si riscrive niente (né si rifà l'accesso).
 *
 * Solo a myCar: il pacchetto che chiede deve essere uno di [MYCAR] e firmato con il suo certificato
 * (SHA-256). A chiunque altro, Gearslip compreso, non risponde.
 *
 * myCar chiama: contentResolver.call(Uri.parse("content://it.xonos.ma.account"), "account", null, null)
 */
class AccountProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != METHOD_ACCOUNT) return null
        val context = context ?: return null
        val caller = try {
            callingPackage
        } catch (e: SecurityException) {
            null
        } ?: return null
        if (!isMyCar(context, caller)) {
            Log.w(TAG, "Account negato a $caller")
            return null
        }
        val app = context.applicationContext as XonosApp
        // myCar si collega da sé: se Xonos usa il server sul telefono e si è fermato (Android l'ha chiuso), ripartire.
        app.startPhoneServerIfUsed()
        val settings = app.settings
        val config = settings.load()
        // Xonos non ancora configurato: niente da passare, ma la risposta dice che Xonos c'è.
        if (!config.isComplete) return Bundle()
        Log.i(TAG, "Account passato a $caller")
        return Bundle().apply {
            putString("home", config.homeUrl)
            putString("remote", config.remoteUrl)
            putString("token", config.token)
            putString("username", settings.username)
        }
    }

    /** Il pacchetto è di myCar ed è firmato con il suo certificato. */
    private fun isMyCar(context: Context, pkg: String): Boolean =
        pkg in MYCAR && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            context.packageManager.hasSigningCertificate(pkg, hex(MYCAR_CERT_SHA256), PackageManager.CERT_INPUT_SHA256)

    private fun hex(text: String): ByteArray =
        ByteArray(text.length / 2) { i -> text.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    // Nient'altro: si risponde solo a call().
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private companion object {
        const val TAG = "Xonos"
        const val METHOD_ACCOUNT = "account"

        /** myCar e la sua build di prova. */
        val MYCAR = setOf("io.github.sirionero.mycar", "io.github.sirionero.mycar.dev")

        /** Il certificato di firma di myCar (SHA-256), lo stesso che Plancia accetta. */
        const val MYCAR_CERT_SHA256 = "e477a4794b984927360e9ae4a31e87cb08a26c54e0e27e4334116d1c7cdccc31"
    }
}
