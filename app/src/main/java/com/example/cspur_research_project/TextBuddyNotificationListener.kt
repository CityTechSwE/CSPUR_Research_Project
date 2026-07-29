package com.example.cspur_research_project

import android.app.Notification
import android.app.PendingIntent
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
import androidx.core.app.NotificationCompat

class TextBuddyNotificationListener : NotificationListenerService(), TextToSpeech.OnInitListener {

    private lateinit var tts: TextToSpeech
    private var activeSbn: StatusBarNotification? = null

    // Store choices in memory so the speech listener can match what the user says
    private var choice1 = ""
    private var choice2 = ""
    private var choice3 = ""
    private var isProcessing = false
    private var pendingReplyIntent: PendingIntent? = null
    private var replyRemoteInputKey: String? = null
    private var lastProcessedText = ""
    private var lastProcessedSender = ""
    private var lastProcessedTime = 0L

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

                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        Log.d("TEXT_BUDDY_TTS", "TTS playback started: $utteranceId")
                    }

                    override fun onDone(utteranceId: String?) {
                        Log.d("TEXT_BUDDY_TTS", "TTS playback finished for ID: $utteranceId")
                        if (utteranceId == "TextBuddyTTSID") {
                            listenForDriverChoice()
                        }
                    }

                    override fun onError(utteranceId: String?) {
                        Log.e("TEXT_BUDDY_TTS", "TTS error occurred on ID: $utteranceId")
                        isProcessing = false
                    }
                })
            }
        } else {
            Log.e("TEXT_BUDDY_TTS", "Initialization failed.")
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (isProcessing) return

        val notification = sbn.notification
        val extras = notification.extras
        val packageName = sbn.packageName

        // 1. App Filter
        val isTargetApp = packageName.contains("messaging") ||
                packageName.contains("whatsapp") ||
                packageName.contains("facebook.orca") ||
                packageName.contains("discord") ||
                packageName.contains("samsung.android.messaging")

        if (!isTargetApp) return

        // 2. DM Category & Style Checks
        val isMessageCategory = notification.category == Notification.CATEGORY_MESSAGE
        val isMessagingStyle = extras.containsKey(Notification.EXTRA_MESSAGES) ||
                extras.containsKey(Notification.EXTRA_CONVERSATION_TITLE) ||
                extras.getString(Notification.EXTRA_TEMPLATE) == "android.app.Notification${'$'}MessagingStyle" ||
                extras.getString(NotificationCompat.EXTRA_TEMPLATE) == "androidx.core.app.NotificationCompat${'$'}MessagingStyle"

        if (!isMessageCategory && !isMessagingStyle) return

        val title = extras.getString(Notification.EXTRA_TITLE)
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

        if (!title.isNullOrEmpty() && !text.isNullOrEmpty()) {

            // --- DUPLICATE PREVENTION GUARD ---
            val currentTime = System.currentTimeMillis()
            val isDuplicate = (text == lastProcessedText && title == lastProcessedSender) &&
                    (currentTime - lastProcessedTime < 30000) // Within 30 seconds

            if (isDuplicate) {
                Log.d("TEXT_BUDDY_MONITOR", "Ignored duplicate notification update from $title.")
                return
            }

            // --- INSTANT ACTION EXTRACTION ---
            pendingReplyIntent = null
            replyRemoteInputKey = null

            val actions = notification.actions
            if (actions != null) {
                for (action in actions) {
                    val remoteInputs = action.remoteInputs ?: continue
                    for (remoteInput in remoteInputs) {
                        if (remoteInput.allowFreeFormInput || remoteInput.resultKey != null) {
                            pendingReplyIntent = action.actionIntent
                            replyRemoteInputKey = remoteInput.resultKey
                            Log.d("TEXT_BUDDY_ACTION", "Successfully locked reply intent and key: ${remoteInput.resultKey}")
                            break
                        }
                    }
                    if (pendingReplyIntent != null) break
                }
            }

            if (pendingReplyIntent != null && replyRemoteInputKey != null) {
                Log.d("TEXT_BUDDY_MONITOR", "Valid Direct Message with Reply Action from $title! Processing...")

                // Update our duplicate tracker so re-posts of this exact message are ignored
                lastProcessedText = text
                lastProcessedSender = title
                lastProcessedTime = currentTime

                isProcessing = true
                activeSbn = sbn

                CoroutineScope(Dispatchers.IO).launch {
                    getSmartReplies(title, text)
                }
            } else {
                Log.e("TEXT_BUDDY_ACTION", "Caught message from $packageName but no direct reply RemoteInput was provided by the app.")
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
                val tokens = cleanReplyText.split(",")
                if (tokens.size >= 3) {
                    choice1 = tokens[0].trim().lowercase()
                    choice2 = tokens[1].trim().lowercase()
                    choice3 = tokens[2].trim().lowercase()
                }

                val spokenAnnouncement = "New message from $sender. They said: $originalMessage. Your choices are: $cleanReplyText"
                speakOutLoud(spokenAnnouncement)
            } else {
                isProcessing = false
            }

        } catch (e: Exception) {
            Log.e("TEXT_BUDDY_AI", "API Network Error: ${e.message}")
            isProcessing = false
        }
    }

    private fun speakOutLoud(textToSpeak: String) {
        Log.d("TEXT_BUDDY_TTS", "Speaking: $textToSpeak")

        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "TextBuddyTTSID")
        }

        tts.speak(textToSpeak, TextToSpeech.QUEUE_FLUSH, params, "TextBuddyTTSID")
    }

    private fun listenForDriverChoice() {
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

                        var chosenReply = ""
                        if (spokenText.contains("one") || spokenText.contains("first") || (choice1.isNotEmpty() && spokenText.contains(choice1))) {
                            chosenReply = choice1
                        } else if (spokenText.contains("two") || spokenText.contains("second") || (choice2.isNotEmpty() && spokenText.contains(choice2))) {
                            chosenReply = choice2
                        } else if (spokenText.contains("three") || spokenText.contains("third") || (choice3.isNotEmpty() && spokenText.contains(choice3))) {
                            chosenReply = choice3
                        } else {
                            chosenReply = spokenText
                        }

                        Log.d("TEXT_BUDDY_VOICE", "Executing outbound reply target text: $chosenReply")

                        val sent = sendDirectReply(chosenReply)
                        Log.d("TEXT_BUDDY_VOICE", "Reply attempt completed. Success: $sent")
                    } else {
                        isProcessing = false
                    }
                }

                override fun onError(error: Int) {
                    Log.e("TEXT_BUDDY_VOICE", "Speech Error Code: $error")
                    isProcessing = false
                }

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

    private fun sendDirectReply(messageToSend: String): Boolean {
        val intentToUse = pendingReplyIntent
        val keyToUse = replyRemoteInputKey

        if (intentToUse == null || keyToUse == null) {
            Log.e("TEXT_BUDDY_ACTION", "Cannot send reply: Missing pre-locked PendingIntent or RemoteInput key.")
            isProcessing = false
            return false
        }

        val remoteInput = RemoteInput.Builder(keyToUse).build()
        val results = Bundle().apply {
            putString(keyToUse, messageToSend)
        }

        val fillInIntent = Intent()
        RemoteInput.addResultsToIntent(arrayOf(remoteInput), fillInIntent, results)

        return try {
            intentToUse.send(applicationContext, 0, fillInIntent)
            Log.d("TEXT_BUDDY_ACTION", "Outbound reply fired directly via pre-locked intent: $messageToSend")
            isProcessing = false
            true
        } catch (e: Exception) {
            Log.e("TEXT_BUDDY_ACTION", "Failed to fire pre-locked intent", e)
            isProcessing = false
            false
        }
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