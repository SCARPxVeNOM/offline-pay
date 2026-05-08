package com.offlinepay.wallet

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/// Backup / restore screen.
///
/// Why this UI is mandatory pre-pilot: without it, KeyBackup.backup() is
/// never called. That means
///   (a) every customer who loses a phone loses their locked USDC, and
///   (b) the nonce-skip protection on restore never activates.
///
/// The UI is deliberately bare — one screen, programmatic layout, no XML —
/// to keep this in a single file that mirrors the rest of the customer app.
class BackupRestoreActivity : AppCompatActivity() {

    private val backendBaseUrl = "http://10.0.2.2:4000"   // emulator → host

    private lateinit var backup: KeyBackup
    private lateinit var prefs: android.content.SharedPreferences

    private lateinit var status: TextView
    private lateinit var lastBackupView: TextView
    private lateinit var userIdInput: EditText
    private lateinit var passInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        backup = KeyBackup(this, backendBaseUrl)
        prefs  = getSharedPreferences("offlinepay_keys", MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }

        val title = TextView(this).apply {
            text = "Wallet backup"
            textSize = 22f
            setPadding(0, 0, 0, 16)
        }

        lastBackupView = TextView(this).apply {
            text = formatLastBackupStatus()
            textSize = 13f
            setPadding(0, 0, 0, 24)
        }

        userIdInput = EditText(this).apply {
            hint = "Account ID (email or phone)"
            inputType = InputType.TYPE_CLASS_TEXT
            setText(prefs.getString(KEY_USER_ID, defaultUserId()) ?: defaultUserId())
        }

        passInput = EditText(this).apply {
            hint = "Passphrase (≥12 chars, mix letters and digits)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        val backupBtn = Button(this).apply { text = "Back up wallet" }
        val restoreBtn = Button(this).apply { text = "Restore from backup" }

        status = TextView(this).apply {
            text = ""
            textSize = 13f
            gravity = Gravity.START
            setPadding(0, 24, 0, 0)
            typeface = android.graphics.Typeface.MONOSPACE
        }

        val warn = TextView(this).apply {
            text = "Your passphrase encrypts the key locally — it is never sent to our servers " +
                   "and cannot be recovered if forgotten."
            textSize = 11f
            setTextColor(0xFF6E7280.toInt())
            setPadding(0, 24, 0, 0)
        }

        listOf(title, lastBackupView, userIdInput, passInput, backupBtn, restoreBtn, status, warn)
            .forEach { root.addView(it) }
        setContentView(root)

        backupBtn.setOnClickListener { handleBackup(backupBtn, restoreBtn) }
        restoreBtn.setOnClickListener { handleRestore(backupBtn, restoreBtn) }
    }

    private fun handleBackup(vararg buttons: Button) {
        val userId = userIdInput.text.toString().trim()
        val pass   = passInput.text.toString().toCharArray()
        if (userId.isEmpty()) { setStatus("❌ enter an account ID"); return }

        buttons.forEach { it.isEnabled = false }
        setStatus("backing up…")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { backup.backup(userId, pass) }
            java.util.Arrays.fill(pass, '0')
            buttons.forEach { it.isEnabled = true }
            result.fold(
                onSuccess = { addr ->
                    prefs.edit {
                        putString(KEY_USER_ID, userId)
                        putLong(KEY_LAST_BACKUP_TS, System.currentTimeMillis())
                    }
                    lastBackupView.text = formatLastBackupStatus()
                    setStatus("✅ backed up\naddress: ${shortAddr(addr)}\nremember your passphrase — we cannot recover it.")
                },
                onFailure = { e ->
                    val msg = when (e) {
                        is KeyBackup.WeakPassphrase -> "❌ ${e.message}"
                        else                        -> "❌ ${e.message ?: e::class.simpleName}"
                    }
                    setStatus(msg)
                }
            )
        }
    }

    private fun handleRestore(vararg buttons: Button) {
        val userId = userIdInput.text.toString().trim()
        val pass   = passInput.text.toString().toCharArray()
        if (userId.isEmpty()) { setStatus("❌ enter your account ID"); return }

        buttons.forEach { it.isEnabled = false }
        setStatus("restoring…")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { backup.restore(userId, pass) }
            java.util.Arrays.fill(pass, '0')
            buttons.forEach { it.isEnabled = true }
            result.fold(
                onSuccess = { addr ->
                    prefs.edit { putString(KEY_USER_ID, userId) }
                    setStatus("✅ wallet restored\naddress: ${shortAddr(addr)}\n" +
                              "nonce skipped ahead by ${NonceTracker.RECOVERY_SKIP} to avoid in-flight collisions.")
                },
                onFailure = { e -> setStatus("❌ ${e.message ?: "restore failed"}") }
            )
        }
    }

    private fun formatLastBackupStatus(): String {
        val ts = prefs.getLong(KEY_LAST_BACKUP_TS, 0L)
        if (ts == 0L) return "⚠️ never backed up — funds at risk if you lose this phone"
        val days = (System.currentTimeMillis() - ts) / (24L * 60 * 60 * 1000)
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        return "last backup: ${fmt.format(Date(ts))}  (${days}d ago)"
    }

    private fun defaultUserId(): String {
        val existing = prefs.getString(KEY_USER_ID, null)
        if (existing != null) return existing
        // Generate a stable per-install id so the user has *something* even if
        // they don't enter one. They should change it to a real email/phone.
        val id = "device-" + UUID.randomUUID().toString().take(8)
        return id
    }

    private fun shortAddr(a: String) = "${a.take(6)}…${a.takeLast(4)}"

    private fun setStatus(msg: String) = runOnUiThread { status.text = msg }

    companion object {
        const val KEY_LAST_BACKUP_TS = "last_backup_ts"
        const val KEY_USER_ID = "backup_user_id"
    }
}
