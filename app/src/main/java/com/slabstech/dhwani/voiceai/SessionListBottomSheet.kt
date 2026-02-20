package com.slabstech.dhwani.voiceai

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.slabstech.dhwani.voiceai.db.ChatSession
import com.slabstech.dhwani.voiceai.repository.SessionRepository
import com.slabstech.dhwani.voiceai.repository.SessionType
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SessionListBottomSheet : BottomSheetDialogFragment() {

    private var sessionType: SessionType = SessionType.ANSWER
    private var onSessionSelected: ((String) -> Unit)? = null
    private var onNewSessionRequested: (() -> Unit)? = null

    private lateinit var sessionsRecyclerView: androidx.recyclerview.widget.RecyclerView
    private lateinit var newChatButton: com.google.android.material.button.MaterialButton
    private lateinit var repository: SessionRepository
    private lateinit var adapter: SessionAdapter

    companion object {
        private const val ARG_SESSION_TYPE = "session_type"

        fun newInstance(
            sessionType: SessionType,
            onSessionSelected: (String) -> Unit,
            onNewSessionRequested: () -> Unit
        ): SessionListBottomSheet {
            return SessionListBottomSheet().apply {
                this.sessionType = sessionType
                this.onSessionSelected = onSessionSelected
                this.onNewSessionRequested = onNewSessionRequested
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.bottom_sheet_session_list, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        repository = (requireActivity().application as DhwaniApp).sessionRepository
        android.util.Log.d("SessionListBottomSheet", "onViewCreated: sessionType=${sessionType.value}")

        sessionsRecyclerView = view.findViewById(R.id.sessionsRecyclerView)
        newChatButton = view.findViewById(R.id.newChatButton)

        adapter = SessionAdapter(
            sessions = emptyList(),
            onSessionClick = { session ->
                android.util.Log.d("SessionListBottomSheet", "Session clicked: ${session.id}")
                onSessionSelected?.invoke(session.id)
                dismiss()
            },
            onDeleteClick = { session ->
                showDeleteConfirmation(session)
            }
        )

        sessionsRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        sessionsRecyclerView.adapter = adapter

        newChatButton.setOnClickListener {
            android.util.Log.d("SessionListBottomSheet", "New chat button clicked")
            onNewSessionRequested?.invoke()
            dismiss()
        }

        loadSessions()
    }

    private fun loadSessions() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                repository.getSessions(sessionType).collect { sessions ->
                    android.util.Log.d("SessionListBottomSheet", "Sessions loaded: type=${sessionType.value}, count=${sessions.size}")
                    adapter = SessionAdapter(
                        sessions = sessions,
                        onSessionClick = { session ->
                            android.util.Log.d("SessionListBottomSheet", "Session selected: ${session.id}")
                            onSessionSelected?.invoke(session.id)
                            dismiss()
                        },
                        onDeleteClick = { session ->
                            showDeleteConfirmation(session)
                        }
                    )
                    sessionsRecyclerView.adapter = adapter
                }
            } catch (e: Exception) {
                android.util.Log.e("SessionListBottomSheet", "Error loading sessions: ${e.message}", e)
            }
        }
    }

    private fun showDeleteConfirmation(session: ChatSession) {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Delete Session")
            .setMessage("Are you sure you want to delete this chat session?")
            .setPositiveButton("Delete") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    repository.deleteSession(session.id)
                    withContext(Dispatchers.Main) {
                        loadSessions()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
