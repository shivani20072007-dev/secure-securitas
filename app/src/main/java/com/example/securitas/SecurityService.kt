package com.example.securitas

import android.Manifest
import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import org.json.JSONArray
import java.io.File

class SecurityService : Service() {

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var lastLocation: Location? = null
    
    private val channelId = "SecurityServiceChannel"
    private val notificationId = 1
    private val smsSentAction = "com.example.securitas.SMS_SENT"
    
    private var missedSwipeCount = 0
    
    private var mediaRecorder: MediaRecorder? = null
    private var audioCheckJob: Job? = null
    // Adjusted threshold: 15000 was too sensitive (ambient noise), 
    // 25000 was too high. Trying 21000 for "loud noises only".
    private val loudnessThreshold = 21000 

    private val smsSentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val contactName = intent?.getStringExtra("contact_name") ?: "Guardian"
            if (resultCode != Activity.RESULT_OK) {
                Log.e("SecurityService", "SMS failed to $contactName")
            }
        }
    }

    private val shutdownReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SHUTDOWN) {
                escalateAlarm(isLastGasp = true)
            }
        }
    }

    // New Receiver to bring the app to front when screen turns on
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_ON) {
                val activityIntent = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                }
                startActivity(activityIntent)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
        
        val smsFilter = IntentFilter(smsSentAction)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(smsSentReceiver, smsFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(smsSentReceiver, smsFilter)
        }

        registerReceiver(shutdownReceiver, IntentFilter(Intent.ACTION_SHUTDOWN))
        
        // Register for Screen On events
        registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "START_MONITORING" -> startMonitoring()
            "TRIGGER_ALARM" -> triggerAlarm(isDisguised = true)
            "STOP_ALARM" -> stopAlarm()
            "MISSED_SWIPE" -> {
                missedSwipeCount++
                triggerAlarm(isDisguised = false)
            }
            "ACTION_SAFE" -> {
                stopAlarm()
                Toast.makeText(this, "Marked as SAFE", Toast.LENGTH_SHORT).show()
            }
        }

        startForeground(notificationId, createNotification("Safety Monitoring Active"))
        return START_STICKY
    }

    private fun startMonitoring() {
        requestLocationUpdates()
        missedSwipeCount = 0
        startAudioMonitoring()
    }

    private fun startAudioMonitoring() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return

        try {
            stopAudioMonitoring()
            
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                setOutputFile(File(cacheDir, "temp_audio.3gp").absolutePath)
                prepare()
                start()
            }

            audioCheckJob = serviceScope.launch {
                while (isActive) {
                    delay(300)
                    val amplitude = mediaRecorder?.maxAmplitude ?: 0
                    if (amplitude > loudnessThreshold) {
                        Log.d("SecurityService", "Loud noise detected! Amplitude: $amplitude")
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@SecurityService, "DISTRESS NOISE DETECTED!", Toast.LENGTH_SHORT).show()
                        }
                        triggerAudioDistressAlarm()
                        // Longer cooldown after trigger to prevent multiple SMS for one event
                        delay(15000) 
                    }
                }
            }
        } catch (e: Exception) { Log.e("SecurityService", "Audio start failed", e) }
    }

    private fun stopAudioMonitoring() {
        audioCheckJob?.cancel()
        audioCheckJob = null
        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
        } catch (e: Exception) { }
        mediaRecorder = null
    }

    private fun triggerAudioDistressAlarm() {
        val contacts = loadContacts()
        val cops = contacts.filter { it.category == ContactCategory.POLICE }
        
        if (cops.isEmpty()) return

        val userLoc = lastLocation
        val locationLink = if (userLoc != null) {
            "https://maps.google.com/?q=${userLoc.latitude},${userLoc.longitude}"
        } else {
            "Location unavailable"
        }

        val message = "EMERGENCY, USER UNSAFE, LOUD NOISES DETECTED!!! Location: $locationLink"
        
        cops.forEach { contact ->
            sendSms(contact.number, message, contact.name)
        }
    }

    private fun triggerAlarm(isDisguised: Boolean) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                if (location != null) lastLocation = location
                escalateAlarm(isDisguised = isDisguised)
            }.addOnFailureListener {
                escalateAlarm(isDisguised = isDisguised)
            }
        } else {
            escalateAlarm(isDisguised = isDisguised)
        }
    }

    private fun escalateAlarm(isDisguised: Boolean = false, isLastGasp: Boolean = false) {
        val contacts = loadContacts()
        if (contacts.isEmpty()) return

        val userLoc = lastLocation
        val locationLink = if (userLoc != null) {
            "https://maps.google.com/?q=${userLoc.latitude},${userLoc.longitude}"
        } else {
            "Location unavailable"
        }

        val message = "I need help. My location: $locationLink"
        
        val targets = when {
            isDisguised || isLastGasp || missedSwipeCount >= 3 -> contacts
            missedSwipeCount == 1 -> contacts.filter { it.category == ContactCategory.FAMILY }
            missedSwipeCount == 2 -> contacts.filter { it.category != ContactCategory.POLICE }
            else -> emptyList()
        }

        serviceScope.launch {
            targets.forEachIndexed { index, contact ->
                if (index > 0) delay(4000)
                sendSms(contact.number, message, contact.name)
            }
        }
    }

    private fun loadContacts(): List<Contact> {
        val json = getSharedPreferences("SecuritasPrefs", Context.MODE_PRIVATE)
            .getString("contacts_json", null) ?: return emptyList()
        val list = mutableListOf<Contact>()
        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(Contact(
                    obj.getString("id"),
                    obj.getString("name"),
                    obj.getString("number"),
                    ContactCategory.valueOf(obj.getString("category"))
                ))
            }
        } catch (e: Exception) { }
        return list
    }

    private fun sendSms(phoneNumber: String, message: String, contactName: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) return

        try {
            val subId = SubscriptionManager.getDefaultSmsSubscriptionId()
            val smsManager: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val systemSms = this.getSystemService(SmsManager::class.java)
                if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) systemSms.createForSubscriptionId(subId) else systemSms
            } else {
                @Suppress("DEPRECATION")
                if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) SmsManager.getSmsManagerForSubscriptionId(subId) else SmsManager.getDefault()
            }

            val sentIntent = PendingIntent.getBroadcast(
                this, 
                phoneNumber.hashCode() + System.currentTimeMillis().toInt(), 
                Intent(smsSentAction).putExtra("contact_name", contactName), 
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            
            if (message.length > 160) {
                val parts = smsManager.divideMessage(message)
                val sentIntents = ArrayList<PendingIntent>()
                for (i in parts.indices) sentIntents.add(sentIntent)
                smsManager.sendMultipartTextMessage(phoneNumber, null, parts, sentIntents, null)
            } else {
                smsManager.sendTextMessage(phoneNumber, null, message, sentIntent, null)
            }
        } catch (e: Exception) { Log.e("SecurityService", "SMS Exception", e) }
    }

    private fun requestLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000).build()
            fusedLocationClient.requestLocationUpdates(locationRequest, object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    lastLocation = result.lastLocation
                }
            }, null)
        }
    }

    private fun stopAlarm() {
        missedSwipeCount = 0
        stopAudioMonitoring()
        serviceJob.cancelChildren()
    }

    private fun createNotification(content: String): Notification {
        val fullScreenIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(this, 0, fullScreenIntent, PendingIntent.FLAG_IMMUTABLE)

        val safeIntent = Intent(this, SecurityService::class.java).apply { action = "ACTION_SAFE" }
        val safePendingIntent = PendingIntent.getService(this, 10, safeIntent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Securitas")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .addAction(android.R.drawable.checkbox_on_background, "I'M SAFE", safePendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(channelId, "Security Service Channel", NotificationManager.IMPORTANCE_HIGH)
            getSystemService(NotificationManager::class.java).createNotificationChannel(serviceChannel)
        }
    }

    override fun onDestroy() {
        stopAudioMonitoring()
        try { 
            unregisterReceiver(smsSentReceiver)
            unregisterReceiver(shutdownReceiver)
            unregisterReceiver(screenReceiver)
        } catch (e: Exception) { }
        serviceJob.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
