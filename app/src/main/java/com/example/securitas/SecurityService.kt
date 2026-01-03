package com.example.securitas

import android.Manifest
import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
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

class SecurityService : Service() {

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var lastLocation: Location? = null
    
    private val channelId = "SecurityServiceChannel"
    private val notificationId = 1
    private val smsSentAction = "com.example.securitas.SMS_SENT"
    
    private var missedSwipeCount = 0

    private val smsSentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val contactName = intent?.getStringExtra("contact_name") ?: "Guardian"
            if (resultCode == Activity.RESULT_OK) {
                Toast.makeText(context, "Alert delivered to $contactName", Toast.LENGTH_SHORT).show()
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

        val shutdownFilter = IntentFilter(Intent.ACTION_SHUTDOWN)
        registerReceiver(shutdownReceiver, shutdownFilter)
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
            "Location unknown"
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
                if (index > 0) delay(3000)
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
        } catch (e: Exception) { Log.e("SecurityService", "Load failed", e) }
        return list
    }

    private fun sendSms(phoneNumber: String, message: String, contactName: String) {
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
            
            smsManager.sendTextMessage(phoneNumber, null, message, sentIntent, null)
        } catch (e: Exception) { Log.e("SecurityService", "SMS Exception", e) }
    }

    private fun requestLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 30000).build()
            fusedLocationClient.requestLocationUpdates(locationRequest, object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    lastLocation = result.lastLocation
                }
            }, null)
        }
    }

    private fun stopAlarm() {
        missedSwipeCount = 0
        serviceJob.cancelChildren()
    }

    private fun createNotification(content: String): Notification {
        val fullScreenIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
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
        unregisterReceiver(smsSentReceiver)
        unregisterReceiver(shutdownReceiver)
        serviceJob.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}