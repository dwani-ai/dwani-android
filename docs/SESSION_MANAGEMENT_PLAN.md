# Session Management for Chats — Implementation Plan

## 1. Executive Summary

This document outlines how to add **on-device session management** for all chat surfaces in the Dwani Android app. Today, messages live only in memory and are lost when the user leaves an activity or the process is killed. The goal is to persist every conversation locally, support multiple sessions per chat type, and allow users to open, create, and delete sessions.

---

## 2. Current State Analysis

### 2.1 Chat Surfaces

| Surface | Base Class | Role |
|--------|------------|------|
| **AnswerActivity** | MessageActivity | Q&A chat (text/voice/image); calls `v1/indic_chat` |
| **TranslateActivity** | MessageActivity | Translation pairs (text/image); calls translate / visual query APIs |
| **DocsActivity** | AppCompatActivity | Document Q&A (PDF/image/audio); transcription, summary, extract, chat |
| **DhwaniActivity** | (own) | Has its own `Message` and message list; separate flow |

### 2.2 Message Model (Current)

- **Location**: `MessageAdaptor.kt`
- **Shape**: `Message(text, timestamp, isQuery, uri, fileType)`
- **Storage**: None. Held in `mutableListOf<Message>()` in each activity.
- **Media**: `uri` is `android.net.Uri` (content or file URI). No copy to app storage; URIs can become invalid after process death or app restart.

### 2.3 Navigation and Lifecycle

- **MainActivity** launches **VoiceDetectionActivity** by default; bottom nav opens Answer, Docs, Voice (Translate present in code but not in main nav).
- **NavigationUtils** starts Answer / Translate / Docs with `FLAG_ACTIVITY_CLEAR_TOP | SINGLE_TOP` — no extras for “current session”; each start is effectively a fresh screen.
- **DocsActivity** has an “action_clear” menu that clears the in-memory list only.

### 2.4 Dependencies

- **Persistence**: Only `PreferenceManager` (SharedPreferences). No Room, no SQLite.
- **Async**: `lifecycleScope` + coroutines; no reactive streams (Flow/LiveData) in the codebase yet.
- **Versions**: Kotlin 2.0.21, compileSdk 36, minSdk 27; version catalog in `gradle/libs.versions.toml`.

### 2.5 Gaps for Session Management

1. No local DB or file-based message store.
2. No concept of “session” or “conversation”; no session ID passed between activities.
3. Media (image/audio/PDF) not copied to app storage; URIs are transient.
4. **DocsActivity** does not extend **MessageActivity**; duplicated message list, adapter, and options (clear, share, copy).
5. No UI to list, create, or switch sessions.

---

## 3. Target Behaviour

- **All chats stored locally on-device** (no server-side chat history required for this feature).
- **Sessions are per “type”**: Answer, Translate, Docs. User can have multiple sessions per type (e.g. “Trip planning”, “Homework”, “Work doc”).
- **Each session** has: unique id, type, optional title, created/updated timestamps, and an ordered list of messages.
- **Each message** has: text, timestamp, isQuery, optional local file path (for image/audio/PDF), file type.
- **Session list UI**: User can open a session list (e.g. from toolbar or FAB), create “New chat”, open an existing session, delete/rename (optional).
- **Default session**: On first open of Answer/Translate/Docs after upgrade, either auto-create one “Default” session per type or show “New chat” and create on first message — product choice.
- **Media**: When a message has image/audio/PDF, the file is copied into app-specific storage (e.g. `files/attachments/<sessionId>/<messageId>.<ext>`), and only the path is stored in DB; Uri is recreated when loading for display.

---

## 4. Architecture Overview

```
┌─────────────────────────────────────────────────────────────────┐
│  UI Layer                                                        │
│  AnswerActivity / TranslateActivity / DocsActivity               │
│  (each bound to current Session; load/save via Repository)       │
│  + SessionListActivity or BottomSheet (list/create/delete)       │
└───────────────────────────────┬─────────────────────────────────┘
                                │
┌───────────────────────────────▼─────────────────────────────────┐
│  SessionRepository (single source of truth; suspend + Flow)      │
│  - getSessions(type): Flow<List<Session>>                        │
│  - getMessages(sessionId): Flow<List<Message>>                   │
│  - createSession(type, title?), addMessage, deleteMessage,       │
│    deleteSession, updateSessionTitle                             │
│  - copyAttachment(uri) -> local path                             │
└───────────────────────────────┬─────────────────────────────────┘
                                │
┌───────────────────────────────▼─────────────────────────────────┐
│  Room DB + File Storage                                          │
│  - ChatSession (id, type, title, createdAt, updatedAt)           │
│  - ChatMessage (id, sessionId, text, timestamp, isQuery,          │
│                 localFilePath, fileType, sortOrder)              │
│  - Attachments in getFilesDir()/attachments/<sessionId>/...      │
└─────────────────────────────────────────────────────────────────┘
```

- **Single Repository** used by all three chat surfaces (and optionally DhwaniActivity if we unify later).
- **UI** observes `Flow<List<Message>>` for the current session and updates RecyclerView; on send, call `addMessage` then refresh from DB (or Repository can expose a Flow so UI just collects).

---

## 5. Data Layer Design

### 5.1 Room Entities

**ChatSession**

| Column | Type | Notes |
|--------|------|--------|
| id | TEXT PK | UUID string |
| type | TEXT | "answer" \| "translate" \| "docs" |
| title | TEXT? | Nullable; can be derived from first message later |
| createdAt | INTEGER | System.currentTimeMillis() |
| updatedAt | INTEGER | Updated on every new message |

**ChatMessage**

| Column | Type | Notes |
|--------|------|--------|
| id | TEXT PK | UUID string |
| sessionId | TEXT FK → ChatSession.id | Indexed |
| text | TEXT | Display text (e.g. "Query: ...", "Answer: ...") |
| timestamp | TEXT | Keep current format for compatibility |
| isQuery | INTEGER (Boolean) | 0/1 |
| localFilePath | TEXT? | Path under app files dir; null for text-only |
| fileType | TEXT? | "image" \| "audio" \| "pdf" |
| sortOrder | INTEGER | Order within session (0, 1, 2, …) |

- **No Uri in DB**: Store only `localFilePath`. When loading, build `Uri.fromFile(File(context.filesDir, relativePath))` (or use `FileProvider` if you need to expose outside app).

### 5.2 Attachment Storage

- **Directory**: e.g. `context.getFilesDir()/attachments/<sessionId>/`
- **Filename**: `<messageId>.<ext>` (ext from MIME or fileType: jpg, png, mp3, pdf, etc.)
- **On insert**: When user adds a message with `uri != null`, copy from content resolver to this path; store path in `ChatMessage.localFilePath`.
- **On delete session**: Delete folder `attachments/<sessionId>` and all messages for that session.
- **On delete single message**: Delete file at `localFilePath` if present, then delete row.

### 5.3 DAOs

- **ChatSessionDao**: `@Insert` session, `@Query` all sessions, by type, by id; `@Update` (title, updatedAt); `@Delete` session.
- **ChatMessageDao**: `@Insert` message, `@Query` messages by sessionId ORDER BY sortOrder, `@Delete` message, `@Delete` by sessionId.

Use **suspend** and **Flow** for queries so UI can collect in coroutines.

### 5.4 Database and Provisioning

- **Room database**: One database (e.g. `DhwaniDatabase`) with `ChatSession` and `ChatMessage` tables, single `@Database` class, version starting at 1.
- **Migrations**: Start with version 1; no migration from “no DB” needed. For future schema changes, add Migration and bump version.
- **Provisioning**: Provide DB in **DhwaniApp** or via a simple holder (e.g. `DatabaseHolder.get(context)`) so that Repository receives the DAOs.

---

## 6. Repository Layer

- **SessionRepository** (or `ChatRepository`):
  - **getSessions(type: SessionType): Flow<List<ChatSession>>** — from DAO.
  - **getMessages(sessionId: String): Flow<List<ChatMessage>>** — from DAO; map to domain `Message` (with Uri from localFilePath when non-null).
  - **createSession(type: SessionType, title: String? = null): ChatSession** — insert and return.
  - **addMessage(sessionId, text, timestamp, isQuery, localFilePath?, fileType?)** — generate id, sortOrder = max+1, insert; update session.updatedAt.
  - **deleteMessage(messageId)** — delete file if any, then row; update session.updatedAt.
  - **deleteSession(sessionId)** — delete attachment dir, delete messages, delete session.
  - **updateSessionTitle(sessionId, title)** — optional for Phase 2.
  - **copyAttachmentToStorage(context, sessionId, messageId, uri, fileType): String?** — copy to `attachments/<sessionId>/<messageId>.<ext>`, return relative path.

- **Domain model for UI**: Keep existing `Message(text, timestamp, isQuery, uri, fileType)` for RecyclerView. Repository returns `Message` with `uri` rebuilt from `localFilePath` when loading from DB.

---

## 7. UI and Integration Plan

### 7.1 Session Selection and “Current Session”

- **Intent extra**: When opening Answer / Translate / Docs, pass optional `SESSION_ID`. If absent, use a “default” behaviour: e.g. open the most recent session of that type, or create a new one (product decision).
- **In Activity**: Read `sessionId` from intent; if null, either get “last used” from Repository or create new session and store its id (e.g. in a variable or SavedStateHandle if you move to ViewModel later). All `addMessage` / `deleteMessage` use this `sessionId`.

### 7.2 Loading and Showing Messages

- **Replace in-memory list with DB-backed flow**:
  - In **MessageActivity** (and DocsActivity): For current `sessionId`, collect `repository.getMessages(sessionId)` in `lifecycleScope` and replace `messageList` contents, then `notifyDataSetChanged()` or use DiffUtil for smoother updates.
  - Alternatively, keep a single `messageList` that you clear and refill when the Flow emits; or migrate to a single source of truth (e.g. list from Flow) and notify adapter.

### 7.3 Sending a Message

- **Text/response only**: Call `repository.addMessage(sessionId, text, timestamp, isQuery, null, null)` then let the Flow emit the updated list (or reload once).
- **With attachment**: First copy file via `repository.copyAttachmentToStorage(...)`, then `addMessage(..., localFilePath, fileType)`. Use the returned path for building Uri in the domain Message when emitting from Repository.

### 7.4 Deleting a Message / Clearing Chat

- **Single message**: Call `repository.deleteMessage(messageId)`. Flow will emit updated list.
- **Clear chat**: Either delete all messages for the session (and optionally attachment folder) or treat “Clear” as “delete this session and create a new one” for the same type — recommend “delete all messages” so session id stays same and title can be kept.

### 7.5 Session List UI

- **Entry point**: Toolbar icon (e.g. list or “chats”) or a FAB that opens a **SessionListActivity** or **BottomSheet**.
- **SessionList**:
  - Filter by current type (Answer / Translate / Docs) so user sees only sessions for that screen.
  - Show title (or “Session <date>” if no title), updatedAt.
  - Tap → start same Activity with `Intent.putExtra(SESSION_ID, id)` and finish the list.
  - “New chat” → create session, pass its id to the Activity, and open it.
  - Swipe or long-press to delete session (confirm dialog).
- **Optional**: Rename session (Phase 2).

### 7.6 NavigationUtils and MainActivity

- When starting Answer / Translate / Docs, do not pass session id by default (so they use “recent or new” logic). When starting from SessionList, pass the selected session id.

### 7.7 DocsActivity Alignment

- **Option A**: Refactor DocsActivity to extend MessageActivity and use the same session/repository; only the input methods (file picker, PDF, etc.) and API calls differ. Reduces duplication.
- **Option B**: Keep DocsActivity as is but add SessionRepository and session id handling (and its own message list backed by Flow). More consistent with current structure, less refactor.

Recommend **Option A** long-term so one code path handles “current session” and message list; Option B is acceptable for a first phase.

---

## 8. Message and Adapter Compatibility

- **MessageAdapter** today takes `MutableList<Message>` and uses `message.text`, `.timestamp`, `.isQuery`, `.uri`, `.fileType`. Keep this interface.
- **Message** (UI model): Keep `uri: Uri?`. When building from `ChatMessage`, if `localFilePath != null`, create `Uri.fromFile(File(context.filesDir, localFilePath))` (or equivalent with FileProvider). So the adapter does not need to change; only the source of the list changes from in-memory to Repository Flow.
- **MessageActivity**: Replace direct `messageList.add(...)` with `repository.addMessage(...)` and observe the Flow to update `messageList` and adapter. Same for delete.

---

## 9. Implementation Phases

### Phase 1 — Foundation (DB + Repository + single surface)

1. Add **Room** (and Kotlin coroutines dependency if not already) to `build.gradle.kts` / version catalog.
2. Create **ChatSession** and **ChatMessage** entities, DAOs, and **DhwaniDatabase**.
3. Implement **SessionRepository** with createSession, addMessage, getMessages (Flow), getSessions (Flow), deleteMessage, deleteSession, and **copyAttachmentToStorage**.
4. Integrate **AnswerActivity** only:
   - On create: get or create session (e.g. most recent or new), store `sessionId`.
   - Load messages: collect `getMessages(sessionId)` and update messageList + adapter.
   - On send (text/voice/image): copy attachment if needed, then addMessage; rely on Flow or one-off reload.
   - Wire delete message and “clear” to repository.
5. Provide DB in Application (DhwaniApp) or holder; inject or obtain Repository in Activity (e.g. from Application or a simple getter).

**Deliverable**: Answer chats persist across process death and app restart; one session per Answer screen until we add session list.

### Phase 2 — Session list and multiple sessions

6. Add **SessionListActivity** (or bottom sheet): list sessions by type, “New chat”, open by session id, delete session.
7. Toolbar or FAB in AnswerActivity (and later Translate/Docs) opens SessionList filtered by type; on select, start Activity with `SESSION_ID` extra.
8. “New chat” creates a session and launches Answer with that session id.
9. Default behaviour when no SESSION_ID: e.g. “open most recent session of this type” or “create new” — decide and implement.

**Deliverable**: User can have multiple Answer sessions and switch between them.

### Phase 3 — Translate and Docs

10. **TranslateActivity**: Same pattern — session type “translate”, load/save via Repository, session id from intent or “recent/new”.
11. **DocsActivity**: Either refactor to extend MessageActivity and use same session/repository (Option A) or add Repository + session id + Flow to current structure (Option B). Implement “clear” as delete all messages of current session or delete session and create new.
12. Session list for Translate and Docs (same SessionList UI, filter by type).

**Deliverable**: All three chat surfaces use local session management.

### Phase 4 — Polish and edge cases

13. **Session title**: Optional auto-title from first message or user-editable in list.
14. **Attachment lifecycle**: On session delete, remove `attachments/<sessionId>`; on message delete, remove single file. Handle external storage and permissions if you ever read from external paths.
15. **DhwaniActivity**: If it should persist messages, add a session type “dhwani” (or reuse “answer”) and wire to Repository; otherwise leave as is.
16. **Export/backup**: Optional — export session as file or share (out of scope for minimal plan).

---

## 10. Dependency and Version Additions

- **Room**: Add `androidx.room:room-runtime` and `room-ktx` (for coroutines/Flow), `room-compiler` (kapt or KSP). Use a version compatible with Kotlin 2.0 and compileSdk 36 (e.g. Room 2.6.x).
- **KSP** (recommended for Room): Add `ksp` plugin and `room-compiler` with KSP for faster builds.
- **Version catalog**: Add room version and libraries in `libs.versions.toml`, then reference in `app/build.gradle.kts`.

Example (adjust versions to match your catalog):

```kotlin
// build.gradle.kts (app)
dependencies {
    // ...
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
}
```

(If you prefer kapt, use `kapt("androidx.room:room-compiler:2.6.1")` and add the kapt plugin.)

---

## 11. File and Package Layout (Suggested)

- **db**: `com.slabstech.dhwani.voiceai.db`  
  - `DhwaniDatabase`, `ChatSession`, `ChatMessage`, `ChatSessionDao`, `ChatMessageDao`
- **repository**: `com.slabstech.dhwani.voiceai.repository`  
  - `SessionRepository`, `SessionType` (enum: ANSWER, TRANSLATE, DOCS)
- **UI**: Keep activities in current package; add `SessionListActivity` (or `SessionListBottomSheet`) and optional `SessionAdapter` for RecyclerView in the list.

---

## 12. Testing and Migration

- **Unit tests**: Repository tests with in-memory Room database; test createSession, addMessage, getMessages, delete, and that Flow emits correctly.
- **Manual**: Create messages in Answer, kill app, reopen — messages should appear; switch session and return — correct messages per session.
- **Migration**: No DB migration from “nothing” to version 1. For existing users, first launch after upgrade will simply have no sessions; first use creates a new session. Optional: create one “Default” session per type on first launch after upgrade (in Application or first open of each activity).

---

## 13. Summary Checklist

- [ ] Add Room + KSP (or kapt) and create DB, entities, DAOs.
- [ ] Implement SessionRepository (sessions + messages + attachment copy).
- [ ] Integrate AnswerActivity with session id and Repository (load/save/delete).
- [ ] Add SessionList UI (list, new chat, open, delete) and wire to Answer.
- [ ] Extend to TranslateActivity and DocsActivity; align DocsActivity with MessageActivity or shared repo.
- [ ] Attachment storage under app files dir; cleanup on session/message delete.
- [ ] Optional: session title, DhwaniActivity persistence, export.

This plan keeps all chat data on-device, uses a single Room database and repository, and fits the existing Activity-based structure without requiring an immediate move to ViewModel/MVVM (though you can introduce ViewModels later and have them hold the Flow and session id).
