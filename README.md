# dwani AI - Android App

**dwani AI** is an Android application designed for Indian Languages AI 

It provides including voice detection, translation, document processing, and interactive answers. 

-- 
[Google Play Link](https://play.google.com/store/apps/details?id=com.slabstech.dhwani.voiceai)
--

### Release Notes
- Update the dwani Server URL in below files , replace example.com with your dwani API server
  - app/src/main/java/com/slabstech/dhwani/voiceai/Api.kt
  - app/src/main/res/xml/preferences.xml

## Features

- **Voice Detection**: Capture and process audio input for real-time voice interactions.
- **Translation**: Translate text or speech across multiple languages.
- **Document Processing**: Access and manage documents with AI-powered insights.
- **Interactive Answers**: Get intelligent responses to queries through the Answer Activity.

## App Components

The app consists of the following key activities and services, as defined in the Android manifest:

- **AnswerActivity**: Displays AI-generated answers to user queries.
- **TranslateActivity**: Handles translation tasks.
- **DocsActivity**: Manages document-related features.
- **VoiceDetectionActivity**: Processes voice input for AI interactions.
- **DhwaniActivity**: Core activity for primary app functionalities.
- **SettingsActivity**: Configuration options for user preferences.


## Permissions

dwani AI requires the following permissions to function effectively:

- **RECORD_AUDIO**: Enables voice input for voice detection and interaction.
- **READ_EXTERNAL_STORAGE** (up to SDK 32): Accesses external storage for document processing.
- **READ_MEDIA_IMAGES** (up to SDK 33): Reads images from media storage.
- **INTERNET**: Facilitates online AI processing, translation, and data retrieval.

## Prerequisites

To build and run dwani AI, ensure you have the following:

- **Android Studio**: Version 4.0 or higher.
- **JDK**: Version 11 or higher.
- **Android SDK**: API level 21 (Lollipop) or higher.
- **Gradle**: Compatible with the project's build configuration.
- **Device/Emulator**: Android 5.0+ for testing.

## Installation

1. **Clone the Repository**:
   ```bash
   git clone https://github.com/dwani-ai/dwani-android/
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


License
This project is licensed under the MIT License. See the LICENSE file for details.


<!--
ffmpeg -i input.webm -vf "scale=trunc(iw/2)*2:trunc(ih/2)*2,fps=30" -c:v libx264 -crf 23 -c:a copy output.mp4
-->