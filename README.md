# Dhwani AI - Android App

**Dhwani AI** is an innovative Android application designed to provide advanced voice-based AI functionalities, including voice detection, translation, document processing, and interactive answers. The app leverages cutting-edge AI technologies to deliver a seamless and intuitive user experience, making it a versatile tool for communication and productivity.


### Release Notes
- Update the Dhwani Server URL in below files , replace example.com with your Dhwani API server
  - app/src/main/java/com/slabstech/dhwani/voiceai/Api.kt
  - app/src/main/res/xml/preferences.xml

## Features

- **Voice Detection**: Capture and process audio input for real-time voice interactions.
- **Translation**: Translate text or speech across multiple languages.
- **Document Processing**: Access and manage documents with AI-powered insights.
- **Interactive Answers**: Get intelligent responses to queries through the Answer Activity.
- **Onboarding**: Guided setup for new users to explore app features.


## App Components

The app consists of the following key activities and services, as defined in the Android manifest:

- **LoginActivity**: Entry point for user authentication.
- **DhwaniActivity**: Core activity for primary app functionalities.
- **OnboardingActivity**: Walkthrough for first-time users.
- **SettingsActivity**: Configuration options for user preferences.
- **AnswerActivity**: Displays AI-generated answers to user queries.
- **TranslateActivity**: Handles translation tasks.
- **DocsActivity**: Manages document-related features.
- **VoiceDetectionActivity**: Processes voice input for AI interactions.


## Permissions

Dhwani AI requires the following permissions to function effectively:

- **RECORD_AUDIO**: Enables voice input for voice detection and interaction.
- **READ_EXTERNAL_STORAGE** (up to SDK 32): Accesses external storage for document processing.
- **READ_MEDIA_IMAGES** (up to SDK 33): Reads images from media storage.
- **INTERNET**: Facilitates online AI processing, translation, and data retrieval.

## Prerequisites

To build and run Dhwani AI, ensure you have the following:

- **Android Studio**: Version 4.0 or higher.
- **JDK**: Version 11 or higher.
- **Android SDK**: API level 21 (Lollipop) or higher.
- **Gradle**: Compatible with the project's build configuration.
- **Device/Emulator**: Android 5.0+ for testing.

## Installation

1. **Clone the Repository**:
   ```bash
   git clone https://github.com/slabstech/dhwani-android
    ```
   
    Open in Android Studio:
        Launch Android Studio.
        Select Open an existing project and navigate to the cloned repository folder.
    Sync Project:
        Click Sync Project with Gradle Files to resolve dependencies.
    Build the App:
        Go to Build > Make Project or press Ctrl+F9.
    Run the App:
        Connect an Android device or start an emulator.
        Click Run > Run 'app' or press Shift+F10.

Project Structure
```bash
dhwani-ai/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/slabstech/dhwani/voiceai/
│   │   │   │   ├── DhwaniApp.java
│   │   │   │   ├── LoginActivity.java
│   │   │   │   ├── DhwaniActivity.java
│   │   │   │   ├── OnboardingActivity.java
│   │   │   │   ├── SettingsActivity.java
│   │   │   │   ├── AnswerActivity.java
│   │   │   │   ├── TranslateActivity.java
│   │   │   │   ├── DocsActivity.java
│   │   │   │   ├── VoiceDetectionActivity.java
│   │   │   ├── res/
│   │   │   │   ├── layout/
│   │   │   │   ├── mipmap/
│   │   │   │   ├── values/
│   │   │   ├── AndroidManifest.xml
│   ├── build.gradle
├── gradle/
├── build.gradle
├── settings.gradle
```

Configuration

    App Name: Defined in res/values/strings.xml as app_name.
    Icons: Launcher icons are stored in res/mipmap/ (ic_launcher and ic_launcher_round).
    Theme: Uses a custom light theme (Theme.DhwaniVoiceAI.Light).
    Backup: Enabled with android:allowBackup="true".

Dependencies
The app uses standard Android libraries and services, including:

    AndroidX: For modern Android components.
    WorkManager: For background tasks (SystemAlarmService, SystemJobService).
    Additional dependencies are specified in app/build.gradle.

Usage

    Launch the App: Start with LoginActivity to sign in or create an account.
    Onboarding: New users are guided through features via OnboardingActivity.
    Explore Features:
        Use VoiceDetectionActivity for voice commands.
        Access TranslateActivity for language translation.
        Manage documents in DocsActivity.
        View AI responses in AnswerActivity.
    Customize: Adjust settings in SettingsActivity.

Contributing
Contributions are welcome! To contribute:

    Fork the repository.
    Create a feature branch (git checkout -b feature/your-feature).
    Commit changes (git commit -m 'Add your feature').
    Push to the branch (git push origin feature/your-feature).
    Open a pull request.

License
This project is licensed under the MIT License. See the LICENSE file for details.


