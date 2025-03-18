package com.slabstech.dhwani.voiceai

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import java.io.File

data class Message(
    val text: String,
    val timestamp: String,
    val isQuery: Boolean,
    val imageUri: Uri? = null,
    var audioFile: File? = null
)

class MessageAdapter(
    val messages: MutableList<Message>,
    private val onLongClick: (Int) -> Unit,
    private val onClick: (Message, ImageButton) -> Unit // Handles both thumbnail and audio clicks
) : RecyclerView.Adapter<MessageAdapter.MessageViewHolder>() {

    class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val messageText: TextView = itemView.findViewById(R.id.messageText)
        val timestampText: TextView = itemView.findViewById(R.id.timestampText)
        val messageContainer: LinearLayout = itemView.findViewById(R.id.messageContainer)
        val audioControlButton: ImageButton = itemView.findViewById(R.id.audioControlButton)
        val thumbnailImage: ImageView = itemView.findViewById(R.id.thumbnailImage)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_message, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val message = messages[position]
        holder.messageText.text = message.text
        holder.timestampText.text = message.timestamp

        val layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        layoutParams.gravity = if (message.isQuery) android.view.Gravity.END else android.view.Gravity.START
        holder.messageContainer.layoutParams = layoutParams

        val backgroundDrawable = ContextCompat.getDrawable(holder.itemView.context, R.drawable.whatsapp_message_bubble)?.mutate()
        backgroundDrawable?.let {
            it.setTint(ContextCompat.getColor(
                holder.itemView.context,
                if (message.isQuery) R.color.whatsapp_message_out else R.color.whatsapp_message_in
            ))
            holder.messageText.background = it
            holder.messageText.elevation = 2f
            holder.messageText.setPadding(16, 16, 16, 16)
        }

        holder.messageText.setTextColor(ContextCompat.getColor(holder.itemView.context, R.color.whatsapp_text))
        holder.timestampText.setTextColor(ContextCompat.getColor(holder.itemView.context, R.color.whatsapp_timestamp))

        if (message.isQuery && message.imageUri != null) {
            holder.thumbnailImage.visibility = View.VISIBLE
            holder.thumbnailImage.setImageURI(message.imageUri)
            holder.thumbnailImage.setOnClickListener {
                onClick(message, holder.audioControlButton)
            }
            holder.audioControlButton.visibility = View.GONE
        } else if (!message.isQuery && message.audioFile != null) {
            holder.thumbnailImage.visibility = View.GONE
            holder.audioControlButton.visibility = View.VISIBLE
            holder.audioControlButton.setOnClickListener {
                onClick(message, holder.audioControlButton)
            }
            holder.audioControlButton.setColorFilter(ContextCompat.getColor(holder.itemView.context, R.color.whatsapp_green))
        } else {
            holder.thumbnailImage.visibility = View.GONE
            holder.audioControlButton.visibility = View.GONE
        }

        val animation = AnimationUtils.loadAnimation(holder.itemView.context, android.R.anim.fade_in)
        holder.itemView.startAnimation(animation)

        holder.itemView.setOnLongClickListener {
            onLongClick(position)
            true
        }
    }

    override fun getItemCount(): Int = messages.size
}