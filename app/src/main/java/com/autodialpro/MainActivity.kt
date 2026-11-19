package com.autodialpro

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.TelecomManager
import android.telecom.PhoneAccountHandle
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.widget.*
import java.io.BufferedReader
import java.io.InputStreamReader

class MainActivity : Activity() {
    private val numbers = mutableListOf<String>()
    private var current = 0
    private var paused = false
    private lateinit var status: TextView
    private lateinit var listView: TextView
    private lateinit var simSpinner: Spinner
    private lateinit var manualNumberInput: EditText
    private var accounts: List<PhoneAccountHandle> = emptyList()
    private var selectedSimAccounts: List<PhoneAccountHandle?> = emptyList()
    private var activeSimCount = 0
    private var lastCalledNumber: String? = null
    private val attempts = mutableMapOf<String, Int>()
    private val lastDialAt = mutableMapOf<String, Long>()
    private val timerHandler = Handler(Looper.getMainLooper())
    private var reminderSeconds = 45
    private lateinit var maxAttemptsInput: EditText
    private lateinit var cooldownInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
        ensurePhoneStatePermission()
    }

    private fun ensurePhoneStatePermission() {
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            loadSimAccounts()
        } else {
            requestPermissions(arrayOf(Manifest.permission.READ_PHONE_STATE), 78)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 78) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                loadSimAccounts()
            } else {
                simSpinner.adapter = ArrayAdapter(
                    this,
                    android.R.layout.simple_spinner_dropdown_item,
                    listOf("Phone permission denied — enable it in Settings")
                )
                updateStatus("Allow Phone permission so AutoDial Pro can detect available SIM accounts.")
            }
        } else if (requestCode == 77) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                loadSimAccounts()
                updateStatus("Permission granted. Tap CALL NEXT again.")
            } else {
                updateStatus("Phone permission denied. Enable it in Settings.")
            }
        }
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 28, 24, 24)
        }
        root.addView(TextView(this).apply {
            text = "AutoDial Pro"
            textSize = 28f
            setTextColor(0xFF123B63.toInt())
        })
        root.addView(TextView(this).apply {
            text = "Sequential calling • Use your registered SIM number"
            textSize = 14f
            setPadding(0, 6, 0, 18)
        })
        root.addView(TextView(this).apply { text = "Choose SIM"; textSize = 16f })
        simSpinner = Spinner(this)
        root.addView(simSpinner)
        root.addView(Button(this).apply {
            text = "Import contacts (CSV)"
            setOnClickListener {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }, 10)
            }
        })
        manualNumberInput = EditText(this).apply {
            hint = "Enter phone number (e.g. +254722580578)"
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            setSingleLine(true)
        }
        root.addView(manualNumberInput)
        root.addView(Button(this).apply {
            text = "ADD NUMBER"
            setOnClickListener { addManualNumber() }
        })
        status = TextView(this).apply {
            text = "Import a CSV file containing phone numbers."
            textSize = 15f
            setPadding(0, 16, 0, 12)
        }
        root.addView(status)
        root.addView(TextView(this).apply { text = "Redial Settings"; textSize = 18f; setPadding(0, 14, 0, 4) })
        maxAttemptsInput = EditText(this).apply {
            hint = "Maximum attempts per number (1–10)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            setText("3")
        }
        root.addView(maxAttemptsInput)
        cooldownInput = EditText(this).apply {
            hint = "Cooldown between redials in seconds (minimum 30)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            setText("60")
        }
        root.addView(cooldownInput)
        root.addView(TextView(this).apply {
            text = "45-second call reminder (alert only; you must end the call yourself)."
            textSize = 12f
        })
        root.addView(Button(this).apply {
            text = "CALL NEXT"
            setOnClickListener { callNext() }
        })
        root.addView(Button(this).apply {
            text = "REDIAL LAST NUMBER (MANUAL CONFIRMATION)"
            setOnClickListener { redialLastNumber() }
        })
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        controls.addView(Button(this).apply {
            text = "Pause"
            setOnClickListener { paused = true; updateStatus("Campaign paused.") }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        controls.addView(Button(this).apply {
            text = "Resume"
            setOnClickListener { paused = false; updateStatus("Campaign ready. Tap CALL NEXT.") }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        controls.addView(Button(this).apply {
            text = "Stop"
            setOnClickListener { paused = true; current = numbers.size; updateStatus("Campaign stopped.") }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(controls)
        listView = TextView(this).apply {
            textSize = 14f
            setPadding(0, 18, 0, 0)
        }
        root.addView(ScrollView(this).apply { addView(listView) },
            LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(TextView(this).apply {
            text = "Only call people who have agreed to be contacted. Calls require your confirmation and use the selected SIM where Android supports it."
            textSize = 12f
            setPadding(0, 12, 0, 0)
        })
        setContentView(root)
    }

    @Suppress("MissingPermission")
    private fun loadSimAccounts() {
        val telecom = getSystemService(TELECOM_SERVICE) as TelecomManager
        val subscriptionManager = getSystemService(TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
        accounts = try { telecom.callCapablePhoneAccounts } catch (_: Exception) { emptyList() }
        val subscriptions: List<SubscriptionInfo> = try {
            subscriptionManager.activeSubscriptionInfoList ?: emptyList()
        } catch (_: Exception) { emptyList() }
        activeSimCount = subscriptions.size
        val labels = mutableListOf<String>()
        val handlesForChoices = mutableListOf<PhoneAccountHandle?>()
        subscriptions.sortedBy { it.simSlotIndex }.forEach { info ->
            val handle = accounts.firstOrNull { it.id == info.subscriptionId.toString() }
                ?: accounts.getOrNull(info.simSlotIndex)
            val slot = info.simSlotIndex + 1
            val name = info.displayName?.toString()?.takeIf { it.isNotBlank() } ?: "Mobile network"
            labels.add("SIM " + slot + " — " + name)
            handlesForChoices.add(handle)
        }
        if (labels.isEmpty()) {
            accounts.forEachIndexed { index, handle ->
                val label = try { telecom.getPhoneAccount(handle)?.label?.toString() } catch (_: Exception) { null }
                labels.add("SIM " + (index + 1) + " — " + (label ?: "Mobile network"))
                handlesForChoices.add(handle)
            }
        }
        if (labels.isEmpty()) { labels.add("No SIM accounts detected"); handlesForChoices.add(null) }
        selectedSimAccounts = handlesForChoices
        simSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        updateStatus("Detected " + activeSimCount + " active SIM(s); " + accounts.size + " calling account(s) available.")
    }
    @Deprecated("Deprecated in Android API; retained for compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 10 && resultCode == RESULT_OK) {
            data?.data?.let { readCsv(it) }
        }
    }

    private fun readCsv(uri: Uri) {
        try {
            val imported = mutableListOf<String>()
            val stream = contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("Android could not open this file. Copy it to Downloads and try again.")
            stream.use {
                BufferedReader(InputStreamReader(it, Charsets.UTF_8)).useLines { lines ->
                    lines.forEach { rawLine ->
                        val line = rawLine.removePrefix("\uFEFF").trim()
                        if (line.isNotEmpty()) {
                            val cells = line.split(",", ";", "\t")
                            val candidate = cells.asSequence()
                                .map { cell ->
                                    cell.trim().trim('"', '\'')
                                        .replace(Regex("[\\s()\\-]"), "")
                                }
                                .firstOrNull { it.matches(Regex("^\\+?[0-9]{7,15}$")) }
                            if (candidate != null) imported.add(candidate)
                        }
                    }
                }
            }
            numbers.clear()
            numbers.addAll(imported.mapNotNull { normalizePhoneNumber(it) }.distinct())
            current = 0
            paused = false
            refreshNumberList()
            updateStatus(if (numbers.isEmpty()) "File opened, but no valid phone numbers were found."
                else "Loaded ${numbers.size} valid phone numbers.")
        } catch (e: Exception) {
            updateStatus("Could not load CSV: ${e.message ?: "unknown file error"}")
        }
    }

    private fun normalizePhoneNumber(raw: String): String? {
        val cleaned = raw.trim().replace(Regex("[\\s()\\-]"), "")
        return cleaned.takeIf { it.matches(Regex("^\\+?[0-9]{7,15}$")) }
    }

    private fun addManualNumber() {
        val number = normalizePhoneNumber(manualNumberInput.text.toString())
        if (number == null) {
            manualNumberInput.error = "Enter a valid number, including country code if needed."
            return
        }
        if (numbers.contains(number)) {
            updateStatus("That number is already in the list.")
            return
        }
        numbers.add(number)
        manualNumberInput.text.clear()
        refreshNumberList()
        updateStatus("Added $number to the call list.")
    }

    private fun refreshNumberList() {
        listView.text = if (numbers.isEmpty()) {
            "No phone numbers added yet. Import a CSV or add a number manually."
        } else {
            numbers.mapIndexed { i, n -> "${i + 1}. $n" }.joinToString("\n")
        }
    }

    private fun callNext() {
        if (paused) { updateStatus("Campaign is paused. Tap Resume first."); return }
        if (numbers.isEmpty()) { updateStatus("Add a phone number manually or import a CSV list first."); return }
        if (current >= numbers.size) { updateStatus("All contacts processed."); return }
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE), 77)
            updateStatus("Allow phone permissions, then tap CALL NEXT again.")
            return
        }
        val number = numbers[current]
        try {
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number"))
            val selected = selectedSimAccounts.getOrNull(simSpinner.selectedItemPosition)
            if (selected != null) {
                intent.putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, selected)
            } else if (activeSimCount > 1) {
                updateStatus("Android detected multiple SIMs but did not provide a selectable calling account. Set the preferred SIM in Samsung SIM manager.")
                return
            }
            startActivity(intent)
            attempts[number] = (attempts[number] ?: 0) + 1
            lastDialAt[number] = System.currentTimeMillis()
            lastCalledNumber = number
            current++
            timerHandler.removeCallbacksAndMessages(null)
            timerHandler.postDelayed({
                Toast.makeText(this, "45 seconds have passed since dialing. End the call manually if appropriate.", Toast.LENGTH_LONG).show()
            }, 45_000L)
            updateStatus("Calling $number. Attempt " + attempts[number] + ". A reminder will appear after 45 seconds; Android does not permit this app to hang up the call automatically.")
        } catch (_: SecurityException) {
            updateStatus("Call permission was denied. Enable Phone permission in Settings.")
        } catch (e: Exception) {
            updateStatus("Could not place call: ${e.message}")
        }
    }

    private fun redialLastNumber() {
        val number = lastCalledNumber
        if (number == null) { updateStatus("No previous number to redial."); return }
        val maxAttempts = maxAttemptsInput.text.toString().toIntOrNull()?.coerceIn(1, 10) ?: 3
        val cooldown = cooldownInput.text.toString().toLongOrNull()?.coerceAtLeast(30) ?: 60L
        val used = attempts[number] ?: 0
        val lastTime = lastDialAt[number] ?: 0L
        val remainingMs = cooldown * 1000L - (System.currentTimeMillis() - lastTime)
        if (remainingMs > 0L) { updateStatus("Please wait " + ((remainingMs + 999L) / 1000L) + " more seconds before redialing $number."); return }
        if (used >= maxAttempts) { updateStatus("Attempt limit reached for $number ($used/$maxAttempts)."); return }
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            updateStatus("Grant Phone permission before redialing."); return
        }
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number"))
        val selected = selectedSimAccounts.getOrNull(simSpinner.selectedItemPosition)
        if (selected != null) intent.putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, selected)
        try {
            AlertDialog.Builder(this)
                .setTitle("Confirm redial")
                .setMessage("Call $number again? Attempt " + (used + 1) + " of $maxAttempts. Please confirm the previous call has ended and the cooldown of at least " + cooldown + " seconds has passed.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Call") { _, _ ->
                    try {
                        startActivity(intent)
                        attempts[number] = used + 1
                        lastDialAt[number] = System.currentTimeMillis()
                        timerHandler.removeCallbacksAndMessages(null)
                        timerHandler.postDelayed({ Toast.makeText(this, "45 seconds since dialing. End the call manually if appropriate.", Toast.LENGTH_LONG).show() }, 45_000L)
                        updateStatus("Redialed $number. Attempt " + attempts[number] + " of $maxAttempts.")
                    } catch (e: Exception) { updateStatus("Could not redial: " + (e.message ?: "unknown error")) }
                }.show()
        } catch (e: Exception) { updateStatus("Could not prepare redial: " + (e.message ?: "unknown error")) }
    }

    private fun updateStatus(message: String) {
        status.text = "$message\nProgress: $current / ${numbers.size}"
    }
}
