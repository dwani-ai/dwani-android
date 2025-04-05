package com.slabstech.dhwani.voiceai

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class BottomSheetMenuFragment : BottomSheetDialogFragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_bottom_sheet_menu, container, false)

        val answerOption = view.findViewById<TextView>(R.id.option_answer)
        val translateOption = view.findViewById<TextView>(R.id.option_translate)
        val docsOption = view.findViewById<TextView>(R.id.option_docs)
        val voiceDetectionOption = view.findViewById<TextView>(R.id.option_voice_detection) // Add this in your layout

        answerOption.setOnClickListener {
            startActivity(Intent(requireContext(), AnswerActivity::class.java))
            dismiss()
        }

        translateOption.setOnClickListener {
            startActivity(Intent(requireContext(), TranslateActivity::class.java))
            dismiss()
        }

        docsOption.setOnClickListener {
            startActivity(Intent(requireContext(), DocsActivity::class.java))
            dismiss()
        }

        voiceDetectionOption.setOnClickListener {
            startActivity(Intent(requireContext(), VoiceDetectionActivity::class.java))
            dismiss()
        }
        return view
    }
}