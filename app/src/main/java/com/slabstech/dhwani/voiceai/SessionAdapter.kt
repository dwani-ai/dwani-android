package com.slabstech.dhwani.voiceai

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.RecyclerView
import com.slabstech.dhwani.voiceai.db.ChatSession
import java.text.SimpleDateFormat
import java.util.*

class SessionAdapter(
    private val sessions: List<ChatSession>,
    private val onSessionClick: (ChatSession) -> Unit,
    private val onDeleteClick: (ChatSession) -> Unit
) : RecyclerView.Adapter<SessionAdapter.SessionViewHolder>() {

    class SessionViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val cardView: CardView = itemView as CardView
        val titleText: TextView = itemView.findViewById(R.id.sessionTitle)
        val timestampText: TextView = itemView.findViewById(R.id.sessionTimestamp)
        val deleteButton: ImageButton = itemView.findViewById(R.id.deleteButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SessionViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_session, parent, false)
        return SessionViewHolder(view)
    }

    override fun onBindViewHolder(holder: SessionViewHolder, position: Int) {
        val session = sessions[position]
        val context = holder.itemView.context

        // Set title (use first message text or default)
        val title = session.title ?: "Chat ${formatDate(session.updatedAt)}"
        holder.titleText.text = title

        // Set timestamp
        holder.timestampText.text = formatTimestamp(session.updatedAt)

        // Click to open session
        holder.cardView.setOnClickListener {
            onSessionClick(session)
        }

        // Delete button
        holder.deleteButton.setOnClickListener {
            onDeleteClick(session)
        }
    }

    override fun getItemCount(): Int = sessions.size

    private fun formatTimestamp(timestamp: Long): String {
        val now = System.currentTimeMillis()
        val diff = now - timestamp
        val seconds = diff / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        val days = hours / 24

        return when {
            days > 7 -> SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(timestamp))
            days > 0 -> "${days}d ago"
            hours > 0 -> "${hours}h ago"
            minutes > 0 -> "${minutes}m ago"
            else -> "Just now"
        }
    }

    private fun formatDate(timestamp: Long): String {
        return SimpleDateFormat("MMM dd", Locale.getDefault()).format(Date(timestamp))
    }
}
