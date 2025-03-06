package com.slabstech.dhwani.voiceai

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.slabstech.dhwani.voiceai.databinding.ActivityOnboardingBinding

class OnboardingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOnboardingBinding
    private val onboardingItems = listOf(
        OnboardingItem(R.drawable.ic_mic_onboarding, "Record Your Voice", "Press and hold the mic button to start recording your voice."),
        OnboardingItem(R.drawable.ic_messages_onboarding, "Manage Messages", "View, repeat, or delete your transcriptions and responses."),
        OnboardingItem(R.drawable.ic_settings_onboarding, "Customize Settings", "Adjust language, endpoints, and theme from the drawer.")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Setup ViewPager2
        binding.onboardingViewPager.adapter = OnboardingAdapter(onboardingItems)
        binding.onboardingViewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                updateIndicators(position)
                binding.getStartedButton.isVisible = position == onboardingItems.size - 1
                binding.skipButton.isVisible = position != onboardingItems.size - 1
            }
        })

        // Setup Indicators
        setupIndicators()

        // Button Listeners
        binding.skipButton.setOnClickListener {
            finishOnboarding()
        }
        binding.getStartedButton.setOnClickListener {
            finishOnboarding()
        }
    }

    private fun setupIndicators() {
        val indicators = arrayOfNulls<View>(onboardingItems.size)
        val layoutParams = LinearLayout.LayoutParams(16, 16).apply {
            setMargins(8, 0, 8, 0)
        }
        for (i in indicators.indices) {
            indicators[i] = View(this).apply {
                setBackgroundResource(android.R.drawable.btn_radio)
                layoutParams.width = 16
                layoutParams.height = 16
            }
            binding.indicatorLayout.addView(indicators[i], layoutParams)
        }
        updateIndicators(0)
    }

    private fun updateIndicators(position: Int) {
        for (i in 0 until binding.indicatorLayout.childCount) {
            val indicator = binding.indicatorLayout.getChildAt(i)
            indicator.isEnabled = i == position
        }
    }

    private fun finishOnboarding() {
        PreferenceManager.getDefaultSharedPreferences(this)
            .edit()
            .putBoolean("is_first_launch", false)
            .commit() // Changed from apply() to commit() for synchronous save
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}

data class OnboardingItem(val imageRes: Int, val title: String, val description: String)

class OnboardingAdapter(private val items: List<OnboardingItem>) :
    RecyclerView.Adapter<OnboardingAdapter.ViewHolder>() {

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val image: ImageView = itemView.findViewById(R.id.onboarding_image)
        val title: TextView = itemView.findViewById(R.id.onboarding_title)
        val description: TextView = itemView.findViewById(R.id.onboarding_description)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.onboarding_item, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.image.setImageResource(item.imageRes)
        holder.title.text = item.title
        holder.description.text = item.description
    }

    override fun getItemCount(): Int = items.size
}