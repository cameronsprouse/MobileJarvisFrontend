package com.cameronhightower.aivoiceassistant

import ai.picovoice.porcupine.PorcupineActivationException
import ai.picovoice.porcupine.PorcupineManager
import ai.picovoice.porcupine.PorcupineManagerCallback
import ai.picovoice.porcupine.Porcupine
import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import android.content.pm.ServiceInfo
import com.cameronhightower.aivoiceassistant.MainActivity
import com.cameronhightower.aivoiceassistant.utils.PermissionUtils
import com.cameronhightower.aivoiceassistant.voice.VoiceManager
import com.cameronhightower.aivoiceassistant.voice.VoiceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Service that listens for the wake word "Jarvis" in the background
 */
class WakeWordService : Service() {
    
    private val TAG = "WakeWordService"
    private var porcupineManager: PorcupineManager? = null
    private var isRunning = false
    private var serviceScope = CoroutineScope(Dispatchers.Main)
    private var stateMonitorJob: Job? = null
    
    override fun onCreate() {
        try {
            super.onCreate()
            Log.i(TAG, "Service onCreate called")
            createNotificationChannel()
            
            // Starting foreground service with type on Android 10+ (API 29+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, createNotification())
            }
            
            Toast.makeText(this, "Jarvis detection service starting...", Toast.LENGTH_SHORT).show()
            
            // Check if we can access the Porcupine BuiltInKeyword class
            if (!checkPorcupineLibrary()) {
                Log.e(TAG, "Cannot access Porcupine library classes")
                Toast.makeText(this, "Porcupine library not properly initialized", Toast.LENGTH_LONG).show()
                stopSelf()
                return
            }
            
            // Set up voice state monitoring
            setupVoiceStateMonitoring()
            
            initWakeWordDetection()
        } catch (e: UnsatisfiedLinkError) {
            // Specifically catch native library errors
            Log.e(TAG, "Native library error: ${e.message}", e)
            Toast.makeText(this, "Device compatibility issue: Missing native library", Toast.LENGTH_LONG).show()
            stopSelf()
        } catch (e: Exception) {
            Log.e(TAG, "Fatal error in service onCreate: ${e.message}", e)
            Toast.makeText(this, "Service startup failed: ${e.message}", Toast.LENGTH_LONG).show()
            stopSelf()
        }
    }
    
    /**
     * Set up monitoring of VoiceManager state to pause/resume wake word detection
     */
    private fun setupVoiceStateMonitoring() {
        // Cancel any existing job
        stateMonitorJob?.cancel()
        
        // Start a new monitoring job
        stateMonitorJob = serviceScope.launch {
            try {
                VoiceManager.getInstance().voiceState.collect { state ->
                    when (state) {
                        is VoiceManager.VoiceState.WAKE_WORD_DETECTED -> {
                            // Pause wake word detection when wake word detected
                            pauseWakeWordDetection()
                        }
                        is VoiceManager.VoiceState.IDLE -> {
                            // Resume wake word detection when returning to IDLE
                            resumeWakeWordDetection()
                        }
                        else -> {
                            // No action for other states
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in voice state monitoring", e)
            }
        }
    }
    
    /**
     * Pause wake word detection temporarily
     */
    private fun pauseWakeWordDetection() {
        if (isRunning) {
            try {
                Log.i(TAG, "Pausing wake word detection during conversation")
                porcupineManager?.stop()
            } catch (e: Exception) {
                Log.e(TAG, "Error pausing wake word detection", e)
            }
        }
    }
    
    /**
     * Resume wake word detection
     */
    private fun resumeWakeWordDetection() {
        if (isRunning) {
            try {
                // Small delay to make sure conversation is completely done
                serviceScope.launch {
                    delay(500)
                    Log.i(TAG, "Resuming wake word detection after conversation")
                    try {
                        porcupineManager?.start()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error resuming wake word detection", e)
                        // Try to reinitialize if resume fails
                        initWakeWordDetection()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error scheduling wake word detection resume", e)
            }
        }
    }
    
    private fun checkPorcupineLibrary(): Boolean {
        return try {
            // Try to access the BuiltInKeyword class to see if it exists
            val keywordValue = ai.picovoice.porcupine.Porcupine.BuiltInKeyword.JARVIS.name
            Log.d(TAG, "Successfully accessed Porcupine BuiltInKeyword: $keywordValue")
            true
        } catch (e: NoClassDefFoundError) {
            Log.e(TAG, "Failed to find Porcupine classes: ${e.message}", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error checking Porcupine library: ${e.message}", e)
            false
        }
    }
    
    private fun initWakeWordDetection() {
        try {
            // First ensure we have necessary permissions
            if (!PermissionUtils.hasPermission(this, Manifest.permission.RECORD_AUDIO)) {
                Log.e(TAG, "Missing RECORD_AUDIO permission, cannot start wake word detection")
                Toast.makeText(
                    this, 
                    "Microphone permission required for wake word detection", 
                    Toast.LENGTH_LONG
                ).show()
                
                // Try to launch main activity to request permissions
                val intent = Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    putExtra("REQUEST_AUDIO_PERMISSION", true)
                }
                startActivity(intent)
                
                stopSelf()
                return
            }
            
            // Get access key from ConfigManager
            val accessKey = try {
                ConfigManager.getInstance().getPicovoiceAccessKey()
            } catch (e: Exception) {
                Log.e(TAG, "Error getting access key from ConfigManager: ${e.message}", e)
                Toast.makeText(
                    this, 
                    "Error retrieving access key: ${e.message}", 
                    Toast.LENGTH_LONG
                ).show()
                stopSelf()
                return
            }
            
            Log.d(TAG, "Access key retrieved, length: ${accessKey.toString().length}")
            
            if (accessKey.toString().isEmpty()) {
                Log.e(TAG, "Access key is empty. Please set it in config.properties")
                Toast.makeText(
                    this, 
                    "Picovoice access key is not set. Please add it to config.properties", 
                    Toast.LENGTH_LONG
                ).show()
                stopSelf()
                return
            }

            try {
                Log.d(TAG, "Initializing PorcupineManager with built-in keyword JARVIS")
                porcupineManager = PorcupineManager.Builder()
                    .setAccessKey(accessKey)
                    .setKeyword(Porcupine.BuiltInKeyword.JARVIS) // Using built-in "Jarvis" keyword
                    .setSensitivity(0.7f) // Adjust sensitivity as needed (0.0-1.0)
                    .build(this, wakeWordCallback)

                Log.d(TAG, "PorcupineManager initialized successfully")
                Toast.makeText(this, "Wake word detection initialized", Toast.LENGTH_SHORT).show()

                porcupineManager?.start()
                isRunning = true
                Log.i(TAG, "Wake word detection started - listening for 'Jarvis'")
                Toast.makeText(this, "Now listening for 'Jarvis'", Toast.LENGTH_SHORT).show()
            } catch (e: PorcupineActivationException) {
                handlePorcupineError("Failed to initialize Porcupine: ${e.message}", e)
            } catch (e: Exception) {
                handlePorcupineError("Error initializing wake word detection: ${e.message}", e)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error in initWakeWordDetection: ${e.message}", e)
            Toast.makeText(
                this,
                "Unexpected error: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
            stopSelf()
        }
    }
    
    private fun handlePorcupineError(message: String, error: Exception) {
        Log.e(TAG, message, error)
        Toast.makeText(
            this,
            message,
            Toast.LENGTH_LONG
        ).show()
        stopSelf()
    }
    
    private val wakeWordCallback = PorcupineManagerCallback { keywordIndex ->
        try {
            Log.i(TAG, "Wake word 'Jarvis' detected! Keyword index: $keywordIndex")
            
            // Wake word detection is now automatically paused by the state monitoring
            
            // Direct communication with VoiceManager
            val processed = VoiceManager.getInstance().onWakeWordDetected(System.currentTimeMillis())
            
            if (processed) {
                // Wake word was processed (not a duplicate)
                Log.i(TAG, "VoiceManager processed wake word detection - speech recognition should start automatically")
                
                // Bring application to foreground
                val intent = Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                startActivity(intent)
                
                // Show toast feedback
                Toast.makeText(this, "Jarvis activated! Listening...", Toast.LENGTH_SHORT).show()
                
                // Add logging to track if MainActivity was brought to foreground
                Log.d(TAG, "MainActivity activity started in foreground")
            } else {
                Log.d(TAG, "VoiceManager did not process wake word (likely a duplicate or already processing)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in wake word callback: ${e.message}", e)
        }
    }
    
    private fun createNotificationChannel() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Wake Word Detection",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Background service for wake word detection"
                    setShowBadge(false)
                }
                
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.createNotificationChannel(channel)
                Log.d(TAG, "Notification channel created")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating notification channel: ${e.message}", e)
        }
    }
    
    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Voice Assistant")
            .setContentText("Listening for 'Jarvis'")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "Service onStartCommand called")
        
        if (!isRunning) {
            initWakeWordDetection()
        }
        
        return START_STICKY
    }
    
    override fun onDestroy() {
        Log.i(TAG, "Service onDestroy called")
        Toast.makeText(this, "Stopping Jarvis detection", Toast.LENGTH_SHORT).show()
        try {
            isRunning = false
            stateMonitorJob?.cancel()
            porcupineManager?.stop()
            Log.d(TAG, "PorcupineManager stopped")
            porcupineManager?.delete()
            Log.d(TAG, "PorcupineManager resources released")
            porcupineManager = null
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up wake word detection: ${e.message}", e)
        }
        Log.i(TAG, "Wake word detection service stopped")
        super.onDestroy()
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    companion object {
        private const val CHANNEL_ID = "wake_word_channel"
        private const val NOTIFICATION_ID = 1
    }
} 