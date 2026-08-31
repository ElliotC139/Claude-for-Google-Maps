package dev.elliotc.mapsvoice

import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import dev.elliotc.mapsvoice.data.ConversationLog

/** Everything asked and answered, newest first, with a way to get it out. */
class HistoryActivity : AppCompatActivity() {

    private lateinit var transcript: TextView
    private lateinit var emptyLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        transcript = findViewById(R.id.transcript)
        emptyLabel = findViewById(R.id.emptyLabel)

        findViewById<Button>(R.id.shareButton).setOnClickListener { share() }
        findViewById<Button>(R.id.clearButton).setOnClickListener { confirmClear() }

        render()
    }

    private fun render() {
        val entries = ConversationLog.recent(this)
        emptyLabel.visibility = if (entries.isEmpty()) TextView.VISIBLE else TextView.GONE

        transcript.text = entries.joinToString("\n\n") { entry ->
            val whenSaid = DateUtils.getRelativeTimeSpanString(
                entry.at,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS
            )
            "$whenSaid\nYou: ${entry.question}\nClaude: ${entry.answer}"
        }
    }

    private fun share() {
        val text = ConversationLog.asPlainText(this)
        if (text.isBlank()) return
        val intent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.history_title))
            .putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(intent, getString(R.string.share_history)))
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setMessage(R.string.clear_history_confirm)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.clear_history) { _, _ ->
                ConversationLog.clear(this)
                render()
            }
            .show()
    }
}
