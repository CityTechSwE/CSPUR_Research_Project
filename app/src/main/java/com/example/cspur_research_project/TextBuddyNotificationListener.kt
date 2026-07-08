package com.example.cspur_research_project

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.google.ai.client.generativeai.GenerativeModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

class TextBuddyNotificationListener : NotificationListenerService(), TextToSpeech.OnInitListener {

    private lateinit var tts: TextToSpeech
    private var activeSbn: StatusBarNotification? = null

    // Store choices in memory so the speech listener can match what the user says
    private var choice1 = ""
    private var choice2 = ""
    private var choice3 = ""

    private val generativeModel = GenerativeModel(
        modelName = "gemini-2.5-flash",
        apiKey = "AQ.Ab8RN6KlKt73eY_LknHgzX74KBW79DOqZJIeWVrZpfMr1QnSUA"
    )

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(applicationContext, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e("TEXT_BUDDY_TTS", "US English Language is not supported on this device.")
            } else {
                Log.d("TEXT_BUDDY_TTS", "Text-to-Speech Engine is fully ready!")

                // CRUCIAL: Listen for when TTS finishes speaking so we can open the mic safely
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        if (utteranceId == "TextBuddyTTSID") {
                            Log.d("TEXT_BUDDY_TTS", "Speech finished. Activating Voice Recognition...")
                            listenForDriverChoice()
                        }
                    }
                    override fun onError(utteranceId: String?) {}
                })
            }
        } else {
            Log.e("TEXT_BUDDY_TTS", "Initialization failed.")
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        val extras = sbn.notification.extras
        val title = extras.getString(Notification.EXTRA_TITLE)
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

        if (text != null && title != null) {
            if (packageName.contains("messaging") || packageName.contains("whatsapp") || packageName.contains("facebook") || packageName.contains("discord") || packageName.contains("sansung.android.messaging")) {
                Log.d("TEXT_BUDDY_MONITOR", "Incoming text caught! Processing via AI...")

                // Hold onto the active notification object so we have the system handle to reply to
                activeSbn = sbn

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

            Log.d("TEXT_BUDDY_AI", "Gemini Suggested Options: $cleanReplyText")

            if (cleanReplyText != null) {
                // Parse out individual choices to compare them later against spoken text input
                val tokens = cleanReplyText.split(",")
                if (tokens.size >= 3) {
                    choice1 = tokens[0].trim().lowercase()
                    choice2 = tokens[1].trim().lowercase()
                    choice3 = tokens[2].trim().lowercase()
                }

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

    // --- NEW: MICROPHONE INBOUND VOICE ENGINE ---
    private fun listenForDriverChoice() {
        // SpeechRecognizer requires execution on the main operating system thread
        Handler(Looper.getMainLooper()).post {
            val speechRecognizer = SpeechRecognizer.createSpeechRecognizer(applicationContext)
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            }

            speechRecognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    Log.d("TEXT_BUDDY_VOICE", "Microphone channel open. Listening for driver selection...")
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        val spokenText = matches[0].trim().lowercase()
                        Log.d("TEXT_BUDDY_VOICE", "Driver said: $spokenText")

                        // Determine what the driver chose
                        var chosenReply = ""
                        if (spokenText.contains("one") || spokenText.contains("first") || (choice1.isNotEmpty() && spokenText.contains(choice1))) {
                            chosenReply = choice1
                        } else if (spokenText.contains("two") || spokenText.contains("second") || (choice2.isNotEmpty() && spokenText.contains(choice2))) {
                            chosenReply = choice2
                        } else if (spokenText.contains("three") || spokenText.contains("third") || (choice3.isNotEmpty() && spokenText.contains(choice3))) {
                            chosenReply = choice3
                        } else {
                            // Default to whatever phrase they spoke if it wasn't a strict keyword match
                            chosenReply = spokenText
                        }

                        Log.d("TEXT_BUDDY_VOICE", "Executing outbound reply target text: $chosenReply")

                        activeSbn?.notification?.let { notification ->
                            sendDirectReply(notification, chosenReply)
                        }
                    }
                }

                override fun onError(error: Int) { Log.e("TEXT_BUDDY_VOICE", "Speech Error Code: $error") }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            speechRecognizer.startListening(intent)
        }
    }

    // --- NEW: OUTBOUND SYSTEM ACTION PIPELINE ---
    private fun sendDirectReply(notification: Notification, messageToSend: String): Boolean {
        val actions = notification.actions ?: return false
        for (action in actions) {
            val remoteInputs = action.remoteInputs ?: continue
            for (remoteInput in remoteInputs) {
                if (remoteInput.resultKey != null) {
                    val results = Bundle().apply {
                        putString(remoteInput.resultKey, messageToSend)
                    }
                    val intent = Intent()
                    RemoteInput.addResultsToIntent(arrayOf(remoteInput), intent, results)
                    try {
                        action.actionIntent.send(applicationContext, 0, intent)
                        Log.d("TEXT_BUDDY_ACTION", "Outbound payload delivered successfully over network!")
                        return true
                    } catch (e: Exception) {
                        Log.e("TEXT_BUDDY_ACTION", "Failed to fire system intent", e)
                    }
                }
            }
        }
        return false
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