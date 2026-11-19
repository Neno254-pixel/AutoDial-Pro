package com.autodialpro

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.telecom.TelecomManager
import android.telecom.PhoneAccountHandle
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
    private var accounts: List<PhoneAccountHandle> = emptyList()


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
        loadSimAccounts()
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
        val import = Button(this).apply {
            text = "Import contacts (CSV)"
            setOnClickListener { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }, 10) }
        }
        root.addView(import)
        status = TextView(this).apply {
            text = "Import a CSV file containing phone numbers."
            textSize = 15f
            setPadding(0, 16, 0, 12)
        }
        root.addView(status)
        val start = Button(this).apply {
            text = "CALL NEXT"
            setOnClickListener { callNext() }
        }
        root.addView(start)
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

    private fun loadSimAccounts() {
        val telecom = getSystemService(TELECOM_SERVICE) as TelecomManager
        accounts = try { telecom.callCapablePhoneAccounts } catch (_: Exception) { emptyList() }
        val labels = if (accounts.isEmpty()) listOf("Default SIM (choose in phone settings)")
                    else accounts.mapIndexed { i, handle -> "SIM ${i + 1}: ${telecom.getPhoneAccount(handle)?.label ?: "Mobile network"}" }
        simSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {\n        super.onActivityResult(requestCode, resultCode, data)\n        if (requestCode == 10 && resultCode == RESULT_OK) data?.data?.let { readCsv(it) }\n    }\n\n    private fun readCsv(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { stream ->
                BufferedReader(InputStreamReader(stream)).useLines { lines ->
                    numbers.clear()
                    lines.forEach { line ->
                        val value = line.split(",", ";", "\t").firstOrNull()?.trim()?.trim('"') ?: ""
                        val cleaned = value.replace(" ", "").replace("-", "")
                        if (cleaned.matches(Regex("^\\+?[0-9]{7,15}$"))) numbers.add(cleaned)
                    }
                }
            }
            current = 0
            paused = false
            listView.text = numbers.mapIndexed { i, n -> "${i + 1}. $n" }.joinToString("\n")
            updateStatus("Loaded ${numbers.size} valid phone numbers.")
        } catch (e: Exception) { updateStatus("Could not read file: ${e.message}") }
    }

    private fun callNext() {
        if (paused) { updateStatus("Campaign is paused. Tap Resume first."); return }
        if (numbers.isEmpty()) { updateStatus("Import a CSV contact list first."); return }
        if (current >= numbers.size) { updateStatus("All contacts processed."); return }
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE), 77)
            updateStatus("Allow phone permissions, then tap CALL NEXT again.")
            return
        }
        val number = numbers[current]
        try {
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number"))
            if (accounts.isNotEmpty()) {
                val selected = accounts.getOrNull(simSpinner.selectedItemPosition)
                if (selected != null) intent.putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, selected)
            }
            startActivity(intent)
            current++
            updateStatus("Calling $number. When the call ends, return here and tap CALL NEXT.")
        } catch (e: SecurityException) {
            updateStatus("Call permission was denied. Enable Phone permission in Settings.")
        } catch (e: Exception) {
            updateStatus("Could not place call: ${e.message}")
        }
    }

    private fun updateStatus(message: String) {
        status.text = "$message\nProgress: $current / ${numbers.size}"
    }
}
