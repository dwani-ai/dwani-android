package com.slabstech.dhwani.voiceai

import android.content.Context
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

data class Message(
    val text: String,
    val timestamp: String,
    val isQuery: Boolean,
    val uri: Uri? = null,
    val fileType: String? = null // e.g., "image", "audio", "pdf"
)
class MessageAdapter(
    val messages: MutableList<Message>,
    private val onLongClick: (Int) -> Unit,
    private val onClick: (Message, ImageButton) -> Unit
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
        val context = holder.itemView.context

        // Set message text and timestamp
        holder.messageText.text = message.text
        holder.timestampText.text = message.timestamp

        // Align message based on isQuery
        val layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        layoutParams.gravity = if (message.isQuery) android.view.Gravity.END else android.view.Gravity.START
        holder.messageContainer.layoutParams = layoutParams

        // Set message bubble styling
        val backgroundDrawable = ContextCompat.getDrawable(context, R.drawable.whatsapp_message_bubble)?.mutate()
        backgroundDrawable?.let {
            it.setTint(ContextCompat.getColor(
                context,
                if (message.isQuery) R.color.whatsapp_message_out else R.color.whatsapp_message_in
            ))
            holder.messageText.background = it
            holder.messageText.elevation = 2f
            holder.messageText.setPadding(16, 16, 16, 16)
        }

        holder.messageText.setTextColor(ContextCompat.getColor(context, R.color.whatsapp_text))
        holder.timestampText.setTextColor(ContextCompat.getColor(context, R.color.whatsapp_timestamp))

        // Handle file icon/thumbnail
        holder.thumbnailImage.visibility = View.GONE
        holder.audioControlButton.visibility = View.GONE

        message.uri?.let { uri ->
            val thumbnailLayoutParams = holder.thumbnailImage.layoutParams
            when (message.fileType) {
                "image" -> {
                    // Display image thumbnail
                    thumbnailLayoutParams.width = (100 * context.resources.displayMetrics.density).toInt()
                    thumbnailLayoutParams.height = (100 * context.resources.displayMetrics.density).toInt()
                    holder.thumbnailImage.layoutParams = thumbnailLayoutParams
                    holder.thumbnailImage.setImageURI(uri)
                    holder.thumbnailImage.scaleType = ImageView.ScaleType.CENTER_CROP
                    holder.thumbnailImage.visibility = View.VISIBLE
                    holder.thumbnailImage.setOnClickListener {
                        onClick(message, holder.audioControlButton)
                    }
                }
                "audio" -> {
                    // Display audio icon
                    thumbnailLayoutParams.width = (24 * context.resources.displayMetrics.density).toInt()
                    thumbnailLayoutParams.height = (24 * context.resources.displayMetrics.density).toInt()
                    holder.thumbnailImage.layoutParams = thumbnailLayoutParams
                    holder.thumbnailImage.setImageDrawable(
                        ContextCompat.getDrawable(context, R.drawable.ic_audio)
                    )
                    holder.thumbnailImage.scaleType = ImageView.ScaleType.FIT_CENTER
                    holder.thumbnailImage.visibility = View.VISIBLE
                    holder.audioControlButton.visibility = View.VISIBLE
                    holder.audioControlButton.setColorFilter(ContextCompat.getColor(context, R.color.whatsapp_green))
                    holder.audioControlButton.setOnClickListener {
                        onClick(message, holder.audioControlButton)
                    }
                }
                "pdf" -> {
                    // Display PDF icon
                    thumbnailLayoutParams.width = (24 * context.resources.displayMetrics.density).toInt()
                    thumbnailLayoutParams.height = (24 * context.resources.displayMetrics.density).toInt()
                    holder.thumbnailImage.layoutParams = thumbnailLayoutParams
                    holder.thumbnailImage.setImageDrawable(
                        ContextCompat.getDrawable(context, R.drawable.ic_pdf)
                    )
                    holder.thumbnailImage.scaleType = ImageView.ScaleType.FIT_CENTER
                    holder.thumbnailImage.visibility = View.VISIBLE
                }
            }
        }

        // Apply fade-in animation
        val animation = AnimationUtils.loadAnimation(context, android.R.anim.fade_in)
        holder.itemView.startAnimation(animation)

        // Handle long click for options dialog
        holder.itemView.setOnLongClickListener {
            onLongClick(position)
            true
        }
    }

    override fun getItemCount(): Int = messages.size
}