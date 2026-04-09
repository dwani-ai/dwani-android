# Session Management UX Implementation

## Overview

This document describes the UX changes implemented to allow users to:
1. **Start a new session** - Create a fresh chat conversation
2. **Switch between sessions** - View and switch to different chat sessions
3. **Delete sessions** - Remove unwanted chat sessions

## UX Components

### 1. Session List Bottom Sheet

**Location**: `SessionListBottomSheet.kt` + `bottom_sheet_session_list.xml`

A Material Design bottom sheet dialog that displays:
- **Header**: "Chat Sessions" title + "New Chat" button
- **Session List**: RecyclerView showing all sessions for the current chat type (Answer/Translate/Docs)
- **Session Items**: Each item shows:
  - Session title (or "Chat [date]" if no title)
  - Last updated timestamp (e.g., "2h ago", "Just now")
  - Delete button (trash icon)

**Behavior**:
- Opens from toolbar menu icon
- Tapping a session switches to that session
- Tapping "New Chat" creates a new session and switches to it
- Swipe/delete button removes a session (with confirmation dialog)

### 2. Toolbar Menu Icon

**Location**: `main_menu.xml` + `AnswerActivity.kt`

- Added `action_sessions` menu item with icon `ic_menu_sort_by_size`
- Shows in toolbar when space allows (`showAsAction="ifRoom"`)
- Opens the session list bottom sheet when tapped

### 3. Session Switching Logic

**Location**: `AnswerActivity.kt`

**Intent Extra**: `SESSION_ID`
- When AnswerActivity is launched with `Intent.putExtra("SESSION_ID", sessionId)`, it loads that session
- If no SESSION_ID provided, uses `getOrCreateCurrentSession()` (most recent or new)

**Methods**:
- `loadMessagesForSession(sessionId)` - Cancels previous Flow collection and starts new one for the session
- `switchToSession(sessionId)` - Updates `currentSessionId` and loads messages
- `createNewSession()` - Creates a new session and switches to it
- `showSessionListBottomSheet()` - Opens the bottom sheet with callbacks

### 4. Session Adapter

**Location**: `SessionAdapter.kt` + `item_session.xml`

Displays session cards with:
- Title (from `session.title` or auto-generated from date)
- Formatted timestamp (relative: "2h ago", "Just now", or absolute date)
- Delete button
- Click handler to switch sessions

## User Flow

### Starting a New Session

1. User taps toolbar menu icon (sessions icon)
2. Bottom sheet opens showing all Answer sessions
3. User taps "New Chat" button
4. New session is created
5. Bottom sheet closes
6. AnswerActivity switches to the new session (empty message list)

### Switching to Existing Session

1. User taps toolbar menu icon
2. Bottom sheet opens showing all Answer sessions
3. User taps a session card
4. Bottom sheet closes
5. AnswerActivity switches to that session
6. Messages for that session load and display

### Deleting a Session

1. User taps toolbar menu icon
2. Bottom sheet opens
3. User taps delete icon on a session card
4. Confirmation dialog appears: "Are you sure you want to delete this chat session?"
5. User confirms
6. Session and all its messages are deleted
7. Bottom sheet refreshes to show updated list

## Technical Details

### Flow Collection Management

- Each session switch cancels the previous `collectLatest` job
- New job starts for the selected session
- Prevents memory leaks and ensures only one Flow is active

### Session Persistence

- Sessions are stored in Room database (`ChatSession` table)
- Messages are linked via `sessionId` foreign key
- Deleting a session cascades to delete all messages (Room CASCADE)

### Bottom Sheet Lifecycle

- Uses `viewLifecycleOwner` for coroutines
- Observes `getSessions()` Flow to auto-update when sessions change
- Properly handles fragment lifecycle (dismisses on back press, etc.)

## Future Enhancements (Not Implemented)

- **Session Rename**: Allow users to set custom titles
- **Session Search**: Filter sessions by title/text
- **Session Export**: Share/export session as text file
- **Session Merge**: Combine two sessions
- **Session Archive**: Archive old sessions instead of deleting

## Integration with Other Activities

Currently implemented for **AnswerActivity** only. To add to TranslateActivity and DocsActivity:

1. Add same menu item handling in `onOptionsItemSelected`
2. Create bottom sheet with appropriate `SessionType` (TRANSLATE or DOCS)
3. Update `onCreate` to read `SESSION_ID` from Intent
4. Add `loadMessagesForSession()` method

## Testing

To test:
1. Open AnswerActivity
2. Send a few messages
3. Tap toolbar sessions icon
4. Tap "New Chat" - should create new empty session
5. Send messages in new session
6. Open sessions list again - should see both sessions
7. Tap first session - should switch back
8. Delete a session - should remove it and switch to remaining session
