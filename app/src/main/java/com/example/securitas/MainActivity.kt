@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.securitas

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.securitas.ui.theme.SecuritasTheme
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.sqrt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        }

        enableEdgeToEdge()
        setContent {
            SecuritasTheme {
                MainScreen()
            }
        }
    }
}

@Composable
fun MainScreen() {
    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("SecuritasPrefs", Context.MODE_PRIVATE) }
    
    var appStatus by remember { mutableStateOf("Safe") }
    var timerValue by remember { mutableIntStateOf(sharedPrefs.getInt("timer_setting", 30)) }
    var countdown by remember { mutableIntStateOf(timerValue) }
    var contacts by remember { mutableStateOf(loadContacts(context)) }
    var showAddContact by remember { mutableStateOf(false) }
    var editingContact by remember { mutableStateOf<Contact?>(null) }
    var showSettings by remember { mutableStateOf(false) }

    val permissionsToRequest = mutableListOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.SEND_SMS,
        Manifest.permission.READ_PHONE_STATE
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (!permissions.values.all { it }) {
            Toast.makeText(context, "All permissions are required for background safety logic.", Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(Unit) {
        launcher.launch(permissionsToRequest)
        context.startForegroundService(Intent(context, SecurityService::class.java).apply {
            action = "START_MONITORING"
        })
    }

    LaunchedEffect(appStatus, timerValue) {
        if (appStatus == "Awaiting Response") {
            while (appStatus == "Awaiting Response") {
                countdown = timerValue
                while (countdown > 0 && appStatus == "Awaiting Response") {
                    delay(1000)
                    countdown--
                }
                if (countdown == 0 && appStatus == "Awaiting Response") {
                    context.startService(Intent(context, SecurityService::class.java).apply {
                        action = "MISSED_SWIPE"
                    })
                    delay(500)
                }
            }
        }
    }

    if (showSettings) {
        TimerSettingsDialog(
            currentValue = timerValue,
            onDismiss = { showSettings = false },
            onSave = { newValue ->
                timerValue = newValue
                sharedPrefs.edit().putInt("timer_setting", newValue).apply()
                showSettings = false
            }
        )
    }

    if (contacts.isEmpty() || showAddContact || editingContact != null) {
        AddContactDialog(
            initialContact = editingContact,
            onDismiss = { 
                showAddContact = false
                editingContact = null
            },
            onSave = { newContact ->
                val cleanedNumber = if (newContact.number.startsWith("+")) {
                    "+" + newContact.number.filter { it.isDigit() }
                } else {
                    newContact.number.filter { it.isDigit() }
                }
                
                val cleanedContact = newContact.copy(number = cleanedNumber)

                val updatedList = if (editingContact != null) {
                    contacts.map { if (it.id == editingContact!!.id) cleanedContact else it }
                } else {
                    contacts + cleanedContact
                }

                saveContacts(context, updatedList)
                contacts = updatedList
                showAddContact = false
                editingContact = null
            }
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("SECURITAS") },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                    IconButton(onClick = { showAddContact = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Add Contact")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(8.dp))
                StatusDisplay(status = appStatus)

                if (appStatus == "Awaiting Response") {
                    Text(
                        text = "Escalation in: $countdown s",
                        fontSize = 24.sp,
                        color = Color.Red,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }

                if (contacts.isNotEmpty()) {
                    Text("Contacts", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                    Box(modifier = Modifier.heightIn(max = 120.dp).fillMaxWidth()) {
                        LazyColumn {
                            items(contacts) { contact ->
                                ListItem(
                                    headlineContent = { Text(contact.name, fontSize = 14.sp) },
                                    supportingContent = { Text("${contact.category.name} - ${contact.number}", fontSize = 12.sp) },
                                    trailingContent = {
                                        Row {
                                            IconButton(onClick = { editingContact = contact }, modifier = Modifier.size(36.dp)) {
                                                Icon(Icons.Default.Edit, "Edit", modifier = Modifier.size(18.dp))
                                            }
                                            IconButton(onClick = {
                                                val updated = contacts.filter { it.id != contact.id }
                                                saveContacts(context, updated)
                                                contacts = updated
                                            }, modifier = Modifier.size(36.dp)) {
                                                Icon(Icons.Default.Delete, "Delete", modifier = Modifier.size(18.dp))
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Text("Gesture Pad", fontWeight = FontWeight.Bold, color = Color.Gray)
                GesturePad(
                    onGestureDetected = { gesture ->
                        when (gesture) {
                            "O" -> {
                                appStatus = "Safe"
                                context.startService(Intent(context, SecurityService::class.java).apply { action = "STOP_ALARM" })
                                Toast.makeText(context, "SAFE", Toast.LENGTH_SHORT).show()
                            }
                            "U" -> {
                                appStatus = "Awaiting Response"
                                Toast.makeText(context, "UNSAFE", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            Button(
                onClick = { 
                    context.startService(Intent(context, SecurityService::class.java).apply { action = "TRIGGER_ALARM" })
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(bottom = 8.dp),
                shape = CircleShape
            ) {
                Text("CLOSE", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun TimerSettingsDialog(currentValue: Int, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var textValue by remember { mutableStateOf(currentValue.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set Timer Duration") },
        text = {
            Column {
                Text("Enter the countdown duration in seconds (e.g., 30):")
                TextField(
                    value = textValue,
                    onValueChange = { if (it.all { char -> char.isDigit() }) textValue = it },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        },
        confirmButton = {
            Button(onClick = { 
                val value = textValue.toIntOrNull() ?: 30
                onSave(if (value > 0) value else 30)
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun AddContactDialog(initialContact: Contact? = null, onDismiss: () -> Unit, onSave: (Contact) -> Unit) {
    var name by remember { mutableStateOf(initialContact?.name ?: "") }
    var number by remember { mutableStateOf(initialContact?.number ?: "") }
    var category by remember { mutableStateOf(initialContact?.category ?: ContactCategory.FAMILY) }
    var expanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialContact == null) "Add Contact" else "Edit Contact") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField(value = name, onValueChange = { name = it }, label = { Text("Name") })
                TextField(value = number, onValueChange = { number = it }, label = { Text("Phone Number") })
                
                Box {
                    OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Category: ${category.name}")
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        ContactCategory.values().forEach { cat ->
                            DropdownMenuItem(
                                text = { Text(cat.name) },
                                onClick = {
                                    category = cat
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (name.isNotEmpty() && number.isNotEmpty()) {
                    onSave(Contact(
                        id = initialContact?.id ?: System.currentTimeMillis().toString(),
                        name = name,
                        number = number,
                        category = category
                    ))
                }
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

fun saveContacts(context: Context, contacts: List<Contact>) {
    val array = JSONArray()
    contacts.forEach { contact ->
        val obj = JSONObject()
        obj.put("id", contact.id)
        obj.put("name", contact.name)
        obj.put("number", contact.number)
        obj.put("category", contact.category.name)
        array.put(obj)
    }
    context.getSharedPreferences("SecuritasPrefs", Context.MODE_PRIVATE)
        .edit().putString("contacts_json", array.toString()).apply()
}

fun loadContacts(context: Context): List<Contact> {
    val json = context.getSharedPreferences("SecuritasPrefs", Context.MODE_PRIVATE)
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

@Composable
fun GesturePad(onGestureDetected: (String) -> Unit) {
    var points = remember { mutableStateListOf<Offset>() }
    Box(
        modifier = Modifier
            .size(240.dp)
            .background(Color(0xFFF5F5F5), RoundedCornerShape(16.dp))
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { points.clear() },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        points.add(change.position)
                    },
                    onDragEnd = {
                        val gesture = recognizeGesture(points)
                        if (gesture != null) onGestureDetected(gesture)
                        points.clear()
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (points.size > 1) {
                for (i in 0 until points.size - 1) {
                    drawLine(
                        color = Color.Blue,
                        start = points[i],
                        end = points[i + 1],
                        strokeWidth = 8f,
                        cap = StrokeCap.Round
                    )
                }
            }
        }
        if (points.isEmpty()) Text("Draw 'O' or 'U' here", color = Color.LightGray)
    }
}

fun recognizeGesture(points: List<Offset>): String? {
    if (points.size < 10) return null
    val minX = points.minOf { it.x }; val maxX = points.maxOf { it.x }
    val minY = points.minOf { it.y }; val maxY = points.maxOf { it.y }
    val width = maxX - minX; val height = maxY - minY
    if (width < 40 || height < 40) return null
    val start = points.first(); val end = points.last()
    val distStartEnd = sqrt((start.x - end.x) * (start.x - end.x) + (start.y - end.y) * (start.y - end.y))
    if (distStartEnd < width * 0.5) return "O"
    val midY = points.maxOf { it.y }
    if (start.y < midY - height * 0.4 && end.y < midY - height * 0.4) return "U"
    return null
}

@Composable
fun StatusDisplay(status: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when(status) {
                "Safe" -> Color(0xFFE8F5E9)
                "ALARM TRIGGERED" -> Color(0xFFFFEBEE)
                else -> Color(0xFFFFF3E0)
            }
        )
    ) {
        Box(modifier = Modifier.padding(12.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(status.uppercase(), fontSize = 18.sp, fontWeight = FontWeight.Black,
                color = when(status) {
                    "Safe" -> Color(0xFF2E7D32)
                    "ALARM TRIGGERED" -> Color(0xFFC62828)
                    else -> Color(0xFFEF6C00)
                }
            )
        }
    }
}
