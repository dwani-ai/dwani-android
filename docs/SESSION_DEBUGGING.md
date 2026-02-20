# Session Management Debugging Guide

## Issue: Sessions not working for TranslateActivity and DocsActivity

### Changes Made

1. **Menu Item Visibility**: Changed `showAsAction="ifRoom"` to `showAsAction="always"` to ensure the sessions menu item always appears in the toolbar.

2. **Added Logging**: Added comprehensive logging to track:
   - Session creation/loading
   - Message loading
   - Bottom sheet opening
   - Session selection
   - Message persistence

3. **Thread Safety**: Ensured session ID assignment happens on Main thread before loading messages.

### Debugging Steps

1. **Check Logcat** for the following tags:
   - `TranslateActivity` - Session loading and message operations
   - `DocsActivity` - Session loading and message operations  
   - `SessionListBottomSheet` - Session list loading

2. **Verify Menu Item**:
   - Open TranslateActivity or DocsActivity
   - Check toolbar for sessions icon (should always be visible now)
   - Tap it to open bottom sheet

3. **Check Session Creation**:
   - Look for log: `"Session loaded: id=..., type=..."`
   - Verify `currentSessionId` is set

4. **Check Message Persistence**:
   - Send a message in TranslateActivity or DocsActivity
   - Look for log: `"Adding message to session: ..."`
   - Check if `currentSessionId` is null (would show error toast)

5. **Check Session List**:
   - Open bottom sheet
   - Look for log: `"Sessions loaded: type=..., count=..."`
   - If count is 0, sessions aren't being created
   - If count > 0 but list is empty, adapter issue

### Common Issues

1. **Menu item not showing**: 
   - Check if toolbar is properly initialized
   - Verify `onCreateOptionsMenu` is called
   - Check if `action_sessions` exists in `main_menu.xml`

2. **Sessions not loading**:
   - Check if `getOrCreateCurrentSession()` is being called
   - Verify database is accessible
   - Check for exceptions in logcat

3. **Messages not persisting**:
   - Verify `currentSessionId` is not null before adding messages
   - Check if `addMessage()` is being called
   - Verify no exceptions in repository

4. **Bottom sheet empty**:
   - Check if `getSessions()` Flow is emitting
   - Verify session type matches (TRANSLATE vs DOCS)
   - Check adapter is being updated

### Testing Checklist

- [ ] Menu item appears in TranslateActivity toolbar
- [ ] Menu item appears in DocsActivity toolbar
- [ ] Bottom sheet opens when menu item tapped
- [ ] Sessions list shows existing sessions
- [ ] "New Chat" button creates new session
- [ ] Selecting a session switches to it
- [ ] Messages persist when sent
- [ ] Messages load when switching sessions
- [ ] Messages persist across app restart
