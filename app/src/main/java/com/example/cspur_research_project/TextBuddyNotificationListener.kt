package com.example.cspur_research_project

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.speech.tts.TextToSpeech
import android.util.Log
import com.google.ai.client.generativeai.GenerativeModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

class TextBuddyNotificationListener : NotificationListenerService(), TextToSpeech.OnInitListener {

    private lateinit var tts: TextToSpeech

    private val generativeModel = GenerativeModel(
        modelName = "gemini-2.5-flash",
        apiKey = "AQ.Ab8RN6KlKt73eY_LknHgzX74KBW79DOqZJIeWVrZpfMr1QnSUA"
    )

    // This runs when the background service first boots up
    override fun onCreate() {
        super.onCreate()
        // Using applicationContext guarantees a stable OS context handle for the audio drivers
        tts = TextToSpeech(applicationContext, this)
    }

    // This verifies the engine initialized successfully and sets the language
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e("TEXT_BUDDY_TTS", "US English Language is not supported on this device.")
            } else {
                Log.d("TEXT_BUDDY_TTS", "Text-to-Speech Engine is fully ready!")
            }
        } else {
            Log.e("TEXT_BUDDY_TTS", "Initialization failed.")
        }
    }

    // This triggers automatically whenever the phone receives ANY notification
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        val extras = sbn.notification.extras
        val title = extras.getString(Notification.EXTRA_TITLE)
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

        if (text != null && title != null) {
            // Your Facebook / Messaging filter block
            if (packageName.contains("messaging") || packageName.contains("whatsapp") || packageName.contains("facebook") || packageName.contains("discord") || packageName.contains("sansung.android.messaging")) {

                Log.d("TEXT_BUDDY_MONITOR", "Incoming text caught! Processing via AI...")

                // Launch a background thread to make the cloud network request
                CoroutineScope(Dispatchers.IO).launch {
                    getSmartReplies(title, text)
                }
            }
        }
    }

    private suspend fun getSmartReplies(sender: String, originalMessage: String) {
        val drivingPrompt = """
            You are a driving assistant app. The user just received a message from $sender that says: $originalMessage
            Provide exactly 3 short, distinct, one-sentence reply choices the driver can click. 
            Keep them ultra-concise (under 5 words each) because they are driving.
            Format your response exactly like this, separated by commas only, with no introductory text or numbering:
            Choice 1, Choice 2, Choice 3
        """.trimIndent()

        try {
            val response = generativeModel.generateContent(drivingPrompt)
            val cleanReplyText = response.text

            // ... your existing code inside getSmartReplies ...

            Log.d("TEXT_BUDDY_AI", "Gemini Suggested Options: $cleanReplyText")

// --- UPDATE THE VOICE ANNOUNCEMENT HERE ---
            if (cleanReplyText != null) {
                // This will now read: "New message from Jamie. They said: [Message contents]. Your choices are: ..."
                val spokenAnnouncement = "New message from $sender. They said: $originalMessage. Your choices are: $cleanReplyText"
                speakOutLoud(spokenAnnouncement)
            }

        } catch (e: Exception) {
            Log.e("TEXT_BUDDY_AI", "API Network Error: ${e.message}")
        }
    }

    private fun speakOutLoud(textToSpeak: String) {
        Log.d("TEXT_BUDDY_TTS", "Speaking: $textToSpeak")
        tts.speak(textToSpeak, TextToSpeech.QUEUE_FLUSH, null, "TextBuddyTTSID")
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {}

    override fun onDestroy() {
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
        super.onDestroy()
    }
}