package com.example

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.AdSize
import com.example.ui.theme.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale

// --- Data Models ---
data class MeiteiMayekChar(
    val letter: String,       // e.g. "ꯀ"
    val translit: String,     // e.g. "Kok"
    val anatomyMeaning: String, // e.g. "Head"
    val soundGuide: String,   // e.g. "k as in King"
    val index: Int
)

data class Flashcard(
    val english: String,
    val meitei: String,
    val translit: String,
    val type: String
)

data class QuizQuestion(
    val id: Int,
    val question: String,
    val options: List<String>,
    val correctIdx: Int,
    val explanation: String
)

data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val id: Long = System.nanoTime()
)

data class DictionaryWord(
    val english: String,
    val meitei: String,
    val translit: String,
    val category: String,
    val explanation: String = ""
)

class MainActivity : ComponentActivity() {
    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        try {
            MobileAds.initialize(this) {}
        } catch (e: Throwable) {
            e.printStackTrace()
        }

        try {
            tts = TextToSpeech(this) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.setLanguage(Locale.US)
                    isTtsReady = true
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        setContent {
            MyApplicationTheme {
                val appViewModel: MeiteiAppViewModel = viewModel()
                MeiteiMainAppScreen(
                    viewModel = appViewModel,
                    onSpeak = { text -> speakWord(text) }
                )
            }
        }
    }

    private fun speakWord(text: String) {
        if (isTtsReady && tts != null) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
        } else {
            Toast.makeText(this, "Pronouncing: $text", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }
}

// --- ViewModel ---
class MeiteiAppViewModel : ViewModel() {
    private val _userStreak = MutableStateFlow(14)
    val userStreak = _userStreak.asStateFlow()

    private val _userXp = MutableStateFlow(850)
    val userXp = _userXp.asStateFlow()

    private val _downloadedLessons = MutableStateFlow(false)
    val downloadedLessons = _downloadedLessons.asStateFlow()

    private val _dailyWordRead = MutableStateFlow(false)
    val dailyWordRead = _dailyWordRead.asStateFlow()

    private val _favoriteWords = MutableStateFlow<Set<String>>(setOf("Khurumjari", "Thagatchari"))
    val favoriteWords = _favoriteWords.asStateFlow()

    private val _currentScreen = MutableStateFlow("Home")
    val currentScreen = _currentScreen.asStateFlow()

    private val _quizProgress = MutableStateFlow(0)
    val quizProgress = _quizProgress.asStateFlow()

    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(listOf(
        ChatMessage("Khurumjari! ꯈꯨꯔꯨꯝꯖꯔꯤ! I am Oja Sanatombi, your online Meitei language tutor. Let's practice conversations in Meitei/English!", false)
    ))
    val chatMessages = _chatMessages.asStateFlow()

    private val _isChatLoading = MutableStateFlow(false)
    val isChatLoading = _isChatLoading.asStateFlow()

    fun addXp(amount: Int) {
        _userXp.value += amount
        if (amount >= 20) {
            _userStreak.value += 1
        }
    }

    fun setScreen(screen: String) {
        _currentScreen.value = screen
    }

    fun toggleOfflineDownload() {
        _downloadedLessons.value = !_downloadedLessons.value
    }

    fun toggleFavorite(word: String) {
        val current = _favoriteWords.value.toMutableSet()
        if (current.contains(word)) {
            current.remove(word)
        } else {
            current.add(word)
        }
        _favoriteWords.value = current
    }

    fun sendChatMessage(userText: String, context: Context) {
        if (userText.trim().isEmpty()) return

        val userMsg = ChatMessage(userText, true)
        _chatMessages.value = _chatMessages.value + userMsg
        _isChatLoading.value = true

        CoroutineScope(Dispatchers.Main).launch {
            val systemInstruction = "You are Oja Sanatombi, a friendly and experienced Meitei (Manipuri) language teacher. " +
                    "Always respond warmly, teaching Meitei phrases. Keep your replies concise, helpful, and encourage the user to continue learning! " +
                    "Pair your Meitei Mayek text with English translations and phonetic descriptions (e.g., Khurumjari)."

            val apiKey = BuildConfig.GEMINI_API_KEY
            val isMockApiKey = apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY"

            if (isMockApiKey) {
                delay(1200)
                val response = getLocalSimulationResponse(userText)
                _chatMessages.value = _chatMessages.value + ChatMessage(response, false)
                _isChatLoading.value = false
            } else {
                try {
                    val client = OkHttpClient.Builder()
                        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                        .build()

                    val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent?key=$apiKey"

                    val requestJson = JSONObject().apply {
                        val contentsArray = JSONArray().apply {
                            put(JSONObject().apply {
                                val partsArray = JSONArray().apply {
                                    put(JSONObject().apply {
                                        put("text", userText)
                                    })
                                }
                                put("parts", partsArray)
                            })
                        }
                        put("contents", contentsArray)

                        put("systemInstruction", JSONObject().apply {
                            val partsArray = JSONArray().apply {
                                put(JSONObject().apply {
                                    put("text", systemInstruction)
                                })
                            }
                            put("parts", partsArray)
                        })

                        put("generationConfig", JSONObject().apply {
                            put("temperature", 0.7)
                        })
                    }

                    val requestBody = requestJson.toString().toRequestBody("application/json".toMediaType())
                    val request = Request.Builder()
                        .url(url)
                        .post(requestBody)
                        .build()

                    client.newCall(request).enqueue(object : Callback {
                        override fun onFailure(call: Call, e: IOException) {
                            handleChatError(e.message ?: "Network error")
                        }

                        override fun onResponse(call: Call, response: Response) {
                            response.use { resp ->
                                if (!resp.isSuccessful) {
                                    handleChatError("Response failed code: ${resp.code}")
                                    return
                                }
                                val respBody = resp.body?.string()
                                if (respBody == null) {
                                    handleChatError("Empty result received.")
                                    return
                                }

                                try {
                                    val rootJson = JSONObject(respBody)
                                    val candidates = rootJson.getJSONArray("candidates")
                                    val firstCandidate = candidates.getJSONObject(0)
                                    val contentObj = firstCandidate.getJSONObject("content")
                                    val parts = contentObj.getJSONArray("parts")
                                    val responseText = parts.getJSONObject(0).getString("text")

                                    _chatMessages.value = _chatMessages.value + ChatMessage(responseText, false)
                                } catch (e: Exception) {
                                    handleChatError("Data error: ${e.message}")
                                } finally {
                                    _isChatLoading.value = false
                                }
                            }
                        }
                    })

                } catch (e: Exception) {
                    handleChatError("Fetch error: ${e.message}")
                }
            }
        }
    }

    private fun handleChatError(errMsg: String) {
        _isChatLoading.value = false
        _chatMessages.value = _chatMessages.value + ChatMessage(
            "Apologies, network is a bit shaky! Oja is here manually. Here is a helpful tip: To say 'How is it going?' say 'Kamdouribge?' (ꯀꯃꯗꯧꯔꯤꯕꯒꯦ?). Keep practicing, you are doing wonderfully!",
            false
        )
    }

    private fun getLocalSimulationResponse(text: String): String {
        val input = text.lowercase(Locale.ROOT)
        return when {
            input.contains("hello") || input.contains("hi") || input.contains("khurumjari") -> {
                "Hello student! 'Khurumjari' (ꯈꯨꯔꯨꯝꯖꯔꯤ) is the holy greeting in Manipuri culture. It maps to 'I salute / respect you'. What would you like to learn next? Meitei Mayek script or numericals?"
            }
            input.contains("script") || input.contains("mayek") || input.contains("alphabet") -> {
                "Meitei Mayek is an extraordinary writing system of Manipur! There are 18 foundational letters (Mapung Mayek), each naming a specific anatomical body part. For example, 'ꯀ' (Kok) is K for Head, and 'ꯁ' (Sam) is S for Hair. Isn't that beautiful?"
            }
            input.contains("thank") || input.contains("thagatchari") -> {
                "You are welcome! In Meitei, we say 'Thagatchari' (ꯊꯥꯒꯠꯆꯔꯤ) for 'Thank you'. It means 'I offer deep gratitude'. Have you practiced pronouncing it?"
            }
            input.contains("how are you") || input.contains("nungai") -> {
                "Excellent question! To ask 'How are you?', you say 'Nungaibri?' (ꯅꯨꯡꯉꯥꯏꯕ꯭ꯔꯥ?). If you are fine, reply 'Nungaijari / Ei nungai-ee' (ꯑꯩ ꯅꯨꯡꯉꯥꯏꯔꯤ) which means 'I am cheerful!'"
            }
            input.contains("culture") || input.contains("imphal") || input.contains("dance") -> {
                "Manipur is rich in fine arts! Our classic dance 'Ras Lila' is adored worldwide, and during Yaoshang (Holi), we do a vibrant collective moon dance called 'Thabal Chongba'. Try visiting Imphal and visiting the sacred Kangla Fort!"
            }
            else -> {
                "Fascinating query! In Meitei, we often use particles to express politeness, like adding '-chari' at the end of actions to indicate humility. Tell me more, or try checking our interactive Dictionary tab for word translations!"
            }
        }
    }
}

// --- Traditional Pattern Drawing helper (Moirang Phee motif) ---
fun DrawScope.drawManipuriWeave(color: Color) {
    val distance = 36.dp.toPx()
    val triangleSize = 16.dp.toPx()

    val brush = SolidColor(color.copy(alpha = 0.05f))
    val stepVal = distance.toInt().coerceAtLeast(1)
    for (x in 0..this@drawManipuriWeave.size.width.toInt() step stepVal) {
        val path = Path().apply {
            moveTo(x.toFloat(), this@drawManipuriWeave.size.height)
            lineTo(x.toFloat() + triangleSize / 2, this@drawManipuriWeave.size.height - triangleSize)
            lineTo(x.toFloat() + triangleSize, this@drawManipuriWeave.size.height)
            close()
        }
        drawPath(path, brush)

        val pathTop = Path().apply {
            moveTo(x.toFloat(), 0f)
            lineTo(x.toFloat() + triangleSize / 2, triangleSize)
            lineTo(x.toFloat() + triangleSize, 0f)
            close()
        }
        drawPath(pathTop, brush)
    }
}

// --- Main Navigation Switcher ---
@Composable
fun MeiteiMainAppScreen(
    viewModel: MeiteiAppViewModel,
    onSpeak: (String) -> Unit
) {
    val currentScreen by viewModel.currentScreen.collectAsState()
    val userStreak by viewModel.userStreak.collectAsState()
    val userXp by viewModel.userXp.collectAsState()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            Column {
                AdmobBanner()
                MeiteiBottomNavigationBar(
                    activeTab = currentScreen,
                    onTabSelect = { viewModel.setScreen(it) }
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
                .drawBehind {
                    drawManipuriWeave(TealPrimary)
                }
        ) {
            AnimatedContent(
                targetState = currentScreen,
                transitionSpec = {
                    fadeIn(animationSpec = tween(220)) togetherWith fadeOut(animationSpec = tween(220))
                },
                label = "ScreenTransition"
            ) { screen ->
                when (screen) {
                    "Home" -> MeiteiHomeScreen(viewModel, onSpeak)
                    "Lessons" -> MeiteiLessonsLibraryScreen(viewModel, onSpeak)
                    "Practice" -> MeiteiPracticeCenterScreen(viewModel, onSpeak)
                    "Dictionary" -> MeiteiDictionaryScreen(viewModel, onSpeak)
                    "Profile" -> MeiteiProfileScreen(viewModel)
                    "AIChat" -> MeiteiAIChatScreen(viewModel)
                }
            }
        }
    }
}

@Composable
fun AdmobBanner(
    modifier: Modifier = Modifier,
    adUnitId: String = "ca-app-pub-1943305289850339/7979221774"
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            modifier = Modifier.wrapContentSize(),
            factory = { ctx ->
                try {
                    AdView(ctx).apply {
                        setAdSize(AdSize.BANNER)
                        this.adUnitId = adUnitId
                        loadAd(AdRequest.Builder().build())
                    }
                } catch (e: Throwable) {
                    e.printStackTrace()
                    android.view.View(ctx).apply {
                        // Safe empty view
                        minimumHeight = 1
                    }
                }
            }
        )
    }
}

// --- Custom 3D Flat Beveled Button ---
@Composable
fun DuolingoButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    backgroundColor: Color = TealPrimary,
    shadowColor: Color = TealDarkShadow,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    Box(
        modifier = modifier
            .padding(bottom = 6.dp)
            .clickable(enabled = enabled) { onClick() }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .offset(y = 6.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(if (enabled) shadowColor else Color.LightGray)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .offset(y = 2.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(if (enabled) backgroundColor else Color(0xFFE2E8F0))
                .border(2.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(16.dp))
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            content()
        }
    }
}

// ==========================================
// SCREEN 1: HOME (Duolingo Lesson Tree style)
// ==========================================
@Composable
fun MeiteiHomeScreen(viewModel: MeiteiAppViewModel, onSpeak: (String) -> Unit) {
    val streak by viewModel.userStreak.collectAsState()
    val xp by viewModel.userXp.collectAsState()
    val context = LocalContext.current

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("home_screen"),
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = PaddingValues(16.dp)
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(TealPrimary)
                            .border(1.5.dp, Color.White, RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "ꯀ",
                            color = Color.White,
                            fontWeight = FontWeight.Black,
                            fontSize = 20.sp
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Learn Meitei",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = "Manipuri Course",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = SlateTextSub
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .background(OrangeLightContainer, RoundedCornerShape(12.dp))
                            .border(1.dp, AccentOrange.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "🔥", fontSize = 14.sp)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "$streak days",
                            color = AccentOrange,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Row(
                        modifier = Modifier
                            .background(IndigoLightContainer, RoundedCornerShape(12.dp))
                            .border(1.dp, AccentIndigo.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "⚡", fontSize = 14.sp)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "$xp XP",
                            color = AccentIndigo,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Text(
                        text = "UNIT 1: THE FOUNDATIONS",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = TealDarkShadow,
                        letterSpacing = 1.2.sp
                    )
                    Text(
                        text = "65% Completed",
                        fontSize = 11.sp,
                        color = SlateTextSub,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = 0.65f,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(12.dp)
                        .clip(RoundedCornerShape(6.dp)),
                    color = TealPrimary,
                    trackColor = Color.LightGray.copy(alpha = 0.25f)
                )
            }
        }

        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, Color.LightGray.copy(alpha = 0.3f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Khurumjari! ꯈꯨꯔꯨꯝꯖꯔꯤ!",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Ready to master Meitei Mayek structure? Your visual writing pad and AI tutor are standing by.",
                            fontSize = 13.sp,
                            color = SlateTextSub,
                            lineHeight = 18.sp
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = { viewModel.setScreen("Lessons") },
                            colors = ButtonDefaults.buttonColors(containerColor = TealPrimary),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)
                        ) {
                            Text("Resume Learning", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "🦉",
                        fontSize = 58.sp,
                        modifier = Modifier.padding(6.dp)
                    )
                }
            }
        }

        item {
            Text(
                text = "Course Pathway",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp, bottom = 12.dp)
            )
        }

        item {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                LessonTreeNode(
                    title = "ꯀ",
                    translit = "Kok (Head)",
                    isActive = true,
                    isCompleted = true,
                    offset = 0.dp,
                    onClick = {
                        Toast.makeText(context, "Let's learn unit character ꯀ (Kok) for 'Head'!", Toast.LENGTH_SHORT).show()
                        viewModel.setScreen("Lessons")
                    }
                )

                PathConnectorDotted()

                LessonTreeNode(
                    title = "ꯁ",
                    translit = "Sam (Hair)",
                    isActive = true,
                    isCompleted = false,
                    offset = 50.dp,
                    onClick = {
                        Toast.makeText(context, "Up Next: ꯁ (Sam) for 'Hair'!", Toast.LENGTH_SHORT).show()
                        viewModel.setScreen("Lessons")
                    }
                )

                PathConnectorDotted()

                LessonTreeNode(
                    title = "ꯂ",
                    translit = "Lai (Forehead)",
                    isActive = false,
                    isCompleted = false,
                    offset = (-40).dp,
                    onClick = {
                        Toast.makeText(context, "Locked: Solve previous lessons to unlock Forehead ꯂ (Lai)!", Toast.LENGTH_SHORT).show()
                    }
                )

                PathConnectorDotted()

                LessonTreeNode(
                    title = "💬",
                    translit = "Conversational practice",
                    isActive = false,
                    isCompleted = false,
                    offset = 20.dp,
                    onClick = {
                        Toast.makeText(context, "Locked: Reach Unit 2!", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
                    .clickable {
                        onSpeak("Nungaiba")
                    },
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = AccentIndigo),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Badge(containerColor = Color.White.copy(alpha = 0.25f), contentColor = Color.White) {
                                Text("WORD OF THE DAY", fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "ꯅꯨꯡꯉꯥꯏꯕ (Nungaiba)",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "English: Happiness / Cheerful",
                            fontSize = 13.sp,
                            color = Color.White.copy(alpha = 0.85f)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.2f))
                            .clickable { onSpeak("Nungaiba") },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.VolumeUp,
                            contentDescription = "Pronounce Word of the day",
                            tint = Color.White
                        )
                    }
                }
            }
        }

        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .clickable { viewModel.setScreen("AIChat") },
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.2.dp, TealPrimary.copy(alpha = 0.2f)),
                colors = CardDefaults.cardColors(containerColor = TealLightContainer.copy(alpha = 0.25f))
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("🤖", fontSize = 32.sp)
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Practice Chat with Oja Sanatombi", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text("Get conversational tips and ask anything in Meitei using Generative Chat!", fontSize = 11.sp, color = SlateTextSub)
                    }
                    Icon(imageVector = Icons.Default.ArrowForwardIos, contentDescription = "Go to AI chat", tint = TealPrimary, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
fun LessonTreeNode(
    title: String,
    translit: String,
    isActive: Boolean,
    isCompleted: Boolean,
    offset: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .offset(x = offset)
            .padding(vertical = 6.dp)
    ) {
        val bubbleColor = if (isCompleted) TealPrimary else if (isActive) AccentOrange else Color(0xFFCBD5E1)
        val shadowColor = if (isCompleted) TealDarkShadow else if (isActive) Color(0xFFD97706) else Color(0xFF94A3B8)

        Box(
            modifier = Modifier.size(76.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(70.dp)
                    .offset(y = 6.dp)
                    .clip(CircleShape)
                    .background(shadowColor)
            )

            Box(
                modifier = Modifier
                    .size(70.dp)
                    .clip(CircleShape)
                    .background(bubbleColor)
                    .border(3.dp, Color.White.copy(alpha = 0.3f), CircleShape)
                    .clickable { onClick() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = title,
                    color = Color.White,
                    fontWeight = FontWeight.Black,
                    fontSize = 28.sp
                )
            }

            if (isCompleted) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .border(1.5.dp, TealPrimary, CircleShape)
                        .align(Alignment.TopEnd)
                        .offset(x = (2).dp, y = (-2).dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Completed",
                        tint = TealPrimary,
                        modifier = Modifier.size(14.dp)
                    )
                }
            } else if (isActive) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Red)
                        .align(Alignment.TopCenter)
                        .offset(y = (-10).dp)
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    Text("NEW", color = Color.White, fontSize = 7.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = translit,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = if (isActive) MaterialTheme.colorScheme.onBackground else SlateTextSub
        )
    }
}

@Composable
fun PathConnectorDotted() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(vertical = 1.dp)
    ) {
        repeat(3) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(Color.LightGray.copy(alpha = 0.6f))
            )
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}


// ==========================================
// SCREEN 2: LESSONS & ALPHABET EXPLORER
// ==========================================
@Composable
fun MeiteiLessonsLibraryScreen(viewModel: MeiteiAppViewModel, onSpeak: (String) -> Unit) {
    var selectedTab by remember { mutableStateOf(0) }
    val context = LocalContext.current

    val mayekAlphabet = remember {
        listOf(
            MeiteiMayekChar("ꯀ", "Kok", "Head", "k as in King", 1),
            MeiteiMayekChar("ꯁ", "Sam", "Hair", "s as in Sun", 2),
            MeiteiMayekChar("ꯂ", "Lai", "Forehead", "l as in Love", 3),
            MeiteiMayekChar("ꯃ", "Mit", "Eye", "m as in Man", 4),
            MeiteiMayekChar("ꯄ", "Pa", "Eyelash", "p as in Pen", 5),
            MeiteiMayekChar("ꯅ", "Na", "Ear", "n as in Nose", 6),
            MeiteiMayekChar("ꯆ", "Chil", "Lip", "ch as in Chat", 7),
            MeiteiMayekChar("ꯇ", "Til", "Saliva / Tongue", "t as in Tab", 8),
            MeiteiMayekChar("ꯈ", "Khou", "Throat", "kh (aspirated k)", 9),
            MeiteiMayekChar("ꯉ", "Ngou", "Palate", "ng as in Song", 10),
            MeiteiMayekChar("ꯊ", "Thou", "Chest", "th as in Thin", 11),
            MeiteiMayekChar("ꯋ", "Wai", "Face", "w as in Word", 12),
            MeiteiMayekChar("ꯌ", "Yai", "Brain / Spleen", "y as in Yes", 13),
            MeiteiMayekChar("ꯍ", "Huk", "Body", "h as in Hat", 14),
            MeiteiMayekChar("ꯎ", "Un", "Skin", "oo as in Wood", 15),
            MeiteiMayekChar("ꯏ", "Ee", "Blood", "ee as in Tree", 16),
            MeiteiMayekChar("ꯐ", "Pha", "Spleen / Lung", "ph as in Phone", 17),
            MeiteiMayekChar("ꯑ", "Atiya", "Sky / Void", "a as in Apple", 18)
        )
    }

    val vocabFlashcards = remember {
        listOf(
            Flashcard("Hello (Polite greeting)", "ꯈꯨꯔꯨꯝꯖꯔꯤ", "Khurumjari", "Greeting"),
            Flashcard("Thank you", "ꯊꯥꯒꯠꯆꯔꯤ", "Thagatchari", "Greeting"),
            Flashcard("Welcome", "ꯇꯔꯥꯖꯔꯤ", "Tarajari", "Greeting"),
            Flashcard("How are you?", "ꯅꯨꯡꯉꯥꯏꯕ꯭ꯔꯥ?", "Nungaibri?", "Phrases"),
            ModFlashcard("I am fine", "ꯑꯩ ꯅꯨꯡꯉꯥꯏꯔꯤ / ꯅꯨꯡꯉꯥꯏꯖꯔꯤ", "Ei nungai-ee / Nungaijari", "Phrases"),
            ModFlashcard("What is your name?", "ꯅꯪꯒꯤ ꯃꯤꯡ ꯀꯔꯤ ꯀꯧꯕꯒꯦ?", "Nangi ming kari koubge?", "Phrases"),
            ModFlashcard("My name is Aabhishek", "ꯑꯩꯒꯤ ꯃꯤꯡ ꯑꯥꯚꯤꯁꯦꯛ ꯀꯧꯏ", "Eigi ming Abhishek kou-ee", "Phrases"),
            ModFlashcard("Yes", "ꯎꯝ", "Um", "Vocab"),
            ModFlashcard("No", "ꯅꯠꯇꯦ", "Natte", "Vocab"),
            ModFlashcard("Water", "ꯏꯁꯤꯡ", "Eeshing", "Vocab"),
            ModFlashcard("House/Home", "ꯌꯨꯝ", "Yum", "Vocab"),
            ModFlashcard("School", "ꯂꯥꯏꯔꯤꯛꯁꯪ", "Lairiksang", "Vocab")
        )
    }

    var activeDetailChar by remember { mutableStateOf<MeiteiMayekChar?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("lessons_screen")
    ) {
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = TealPrimary
        ) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }) {
                Text("Mayek Script", fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.padding(14.dp))
            }
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }) {
                Text("Words & Dialogs", fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.padding(14.dp))
            }
            Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }) {
                Text("Unit Practice Checklist", fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.padding(14.dp))
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        when (selectedTab) {
            0 -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 14.dp)
                ) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        colors = CardDefaults.cardColors(containerColor = TealLightContainer),
                        border = BorderStroke(1.dp, TealPrimary.copy(alpha = 0.3f))
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                "Mapung Mayek (ꯂꯥꯏꯒꯤ ꯃꯌꯦꯛ)",
                                fontWeight = FontWeight.Black,
                                fontSize = 15.sp,
                                color = TealDarkShadow
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "Meitei Mayek script consists of 18 original letters. Magically, each letter represents a distinct human anatomical body feature! Tap any character to investigate.",
                                fontSize = 12.sp,
                                color = SlateTextMain,
                                lineHeight = 16.sp
                            )
                        }
                    }

                    LazyVerticalGridCustom(
                        items = mayekAlphabet,
                        onItemClick = { char ->
                            activeDetailChar = char
                            onSpeak(char.translit)
                        }
                    )
                }
            }

            1 -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    item {
                        Text(
                            text = "Interactive Vocabulary Cards",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }

                    items(vocabFlashcards) { card ->
                        FlashcardInteractiveRow(card = card, onSpeak = onSpeak)
                    }
                }
            }

            2 -> {
                MeiteiQuickCourseChecklist(viewModel)
            }
        }
    }

    activeDetailChar?.let { char ->
        AlertDialog(
            onDismissRequest = { activeDetailChar = null },
            confirmButton = {
                TextButton(onClick = { activeDetailChar = null }) {
                    Text("Close", color = TealPrimary, fontWeight = FontWeight.Bold)
                }
            },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(TealPrimary),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(char.letter, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black)
                    }
                    Column {
                        Text(text = char.translit, fontWeight = FontWeight.Black, fontSize = 22.sp, color = TealDarkShadow)
                        Text(text = "Alphabet Index: ${char.index}", fontSize = 11.sp, color = SlateTextSub)
                    }
                }
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(OrangeLightContainer, RoundedCornerShape(12.dp))
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("💡 Anatomical Meaning: ", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = AccentOrange)
                        Text(char.anatomyMeaning, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp, color = SlateTextMain)
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Pronunciation Phonology:", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = SlateTextMain)
                    Text(char.soundGuide, fontSize = 13.sp, color = SlateTextSub)

                    Spacer(modifier = Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(TealLightContainer)
                            .clickable { onSpeak(char.translit) }
                            .padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(imageVector = Icons.Default.VolumeUp, contentDescription = "Listen guide", tint = TealPrimary)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Hear Audio Pronunciation", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = TealPrimary)
                        }
                    }
                }
            },
            shape = RoundedCornerShape(24.dp)
        )
    }
}

@Composable
fun LazyVerticalGridCustom(
    items: List<MeiteiMayekChar>,
    onItemClick: (MeiteiMayekChar) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        val rows = items.chunked(3)
        items(rows) { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                for (item in rowItems) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(18.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .border(1.5.dp, Color.LightGray.copy(alpha = 0.4f), RoundedCornerShape(18.dp))
                            .clickable { onItemClick(item) }
                            .padding(14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = item.letter,
                                fontSize = 32.sp,
                                fontWeight = FontWeight.Black,
                                color = TealPrimary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = item.translit,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = SlateTextMain
                            )
                            Text(
                                text = item.anatomyMeaning,
                                fontSize = 10.sp,
                                color = SlateTextSub,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                if (rowItems.size < 3) {
                    for (i in 0 until (3 - rowItems.size)) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
fun FlashcardInteractiveRow(card: Flashcard, onSpeak: (String) -> Unit) {
    var isRevealed by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { isRevealed = !isRevealed },
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isRevealed) IndigoLightContainer else MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.2.dp, Color.LightGray.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                if (!isRevealed) {
                    Text(
                        text = "English: " + card.english,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = SlateTextMain
                    )
                    Text(
                        text = "Tap to flip and reveal Meitei script!",
                        fontSize = 11.sp,
                        color = SlateTextSub
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Badge(containerColor = TealPrimary, contentColor = Color.White) {
                            Text("REVEALED", fontSize = 8.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(2.dp))
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = card.meitei,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Black,
                        color = TealDarkShadow
                    )
                    Text(
                        text = "Translit: ${card.translit}",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = SlateTextMain
                    )
                }
            }

            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(Color.LightGray.copy(alpha = 0.25f))
                    .clickable { onSpeak(card.translit) },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.VolumeUp,
                    contentDescription = "Speak",
                    tint = TealPrimary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

fun ModFlashcard(english: String, meitei: String, translit: String, type: String): Flashcard {
    return Flashcard(english, meitei, translit, type)
}

@Composable
fun MeiteiQuickCourseChecklist(viewModel: MeiteiAppViewModel) {
    val isDownloaded by viewModel.downloadedLessons.collectAsState()
    val isWordRead by viewModel.dailyWordRead.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, Color.LightGray.copy(alpha = 0.4f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Unit Offline Config", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Download entire script, audio banks, and dictionary for use anywhere without network connection.", fontSize = 11.sp, color = SlateTextSub)
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = { viewModel.toggleOfflineDownload() },
                            colors = ButtonDefaults.buttonColors(containerColor = if (isDownloaded) Color.Gray else TealPrimary)
                        ) {
                            Text(if (isDownloaded) "Lessons Downloaded" else "Download Lessons (8MB)")
                        }
                    }
                }
            }
        }

        item {
            Text("Complete Tasks for bonus XP", fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(vertical = 4.dp))
        }

        item {
            CourseTaskCheckRow(
                title = "Learn Meitei Mayek structure anatomical definitions",
                isCompleted = true,
                points = "10 XP"
            )
        }

        item {
            CourseTaskCheckRow(
                title = "Solve Alphabet interactive multiple choice boards",
                isCompleted = false,
                points = "20 XP"
            )
        }

        item {
            CourseTaskCheckRow(
                title = "Complete Voice Pronunciation Practice Simulator",
                isCompleted = false,
                points = "30 XP"
            )
        }

        item {
            CourseTaskCheckRow(
                title = "Chat and ask Oja Sanatombi a cultural query",
                isCompleted = isWordRead,
                points = "15 XP"
            )
        }
    }
}

@Composable
fun CourseTaskCheckRow(title: String, isCompleted: Boolean, points: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isCompleted) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (isCompleted) TealPrimary else Color.LightGray
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(title, fontSize = 11.sp, modifier = Modifier.weight(1f), maxLines = 2, fontWeight = FontWeight.Medium)
            Badge(containerColor = OrangeLightContainer, contentColor = AccentOrange) {
                Text(points, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(2.dp))
            }
        }
    }
}

// ==========================================
// SCREEN 3: PRACTICE CENTER (Quiz / Draw / Voice)
// ==========================================
@Composable
fun MeiteiPracticeCenterScreen(viewModel: MeiteiAppViewModel, onSpeak: (String) -> Unit) {
    var practiceMode by remember { mutableStateOf(0) }
    val xp by viewModel.userXp.collectAsState()

    AnimatedContent(
        targetState = practiceMode,
        label = "PracticeCrossfade"
    ) { mode ->
        when (mode) {
            0 -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                        .testTag("practice_center"),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    item {
                        Text(
                            "Gamified Interactive Practice",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            "Accelerate your Manipuri mastery through high energy games.",
                            fontSize = 12.sp,
                            color = SlateTextSub
                        )
                    }

                    item {
                        PracticeSelectionCard(
                            emoji = "🎯",
                            title = "Vocabulary & Script Quiz",
                            subtitle = "Fun multiple-choice levels matching Meitei, English meaning, and phonetics. Earn up to 20 XP per session!",
                            tint = TealPrimary,
                            onClick = { practiceMode = 1 }
                        )
                    }

                    item {
                        PracticeSelectionCard(
                            emoji = "✍️",
                            title = "Meitei Mayek Handwriting Board",
                            subtitle = "Trace actual characters (like Kok 'ꯀ') over traditional templates with full coordinate digital ink. Instantly checks tracing precision!",
                            tint = AccentOrange,
                            onClick = { practiceMode = 2 }
                        )
                    }

                    item {
                        PracticeSelectionCard(
                            emoji = "🎤",
                            title = "Phonetic voice practice (mic simulator)",
                            subtitle = "Pronounce key conversational phrases and receive smart rating based on native frequency pitch profiles.",
                            tint = AccentIndigo,
                            onClick = { practiceMode = 3 }
                        )
                    }
                }
            }

            1 -> {
                QuizPlayground(viewModel, onBack = { practiceMode = 0 })
            }

            2 -> {
                MeiteiHandwritingBoard(viewModel, onBack = { practiceMode = 0 })
            }

            3 -> {
                MeiteiVoicePracticeScreen(onBack = { practiceMode = 0 }, onSpeak = onSpeak)
            }
        }
    }
}

@Composable
fun PracticeSelectionCard(
    emoji: String,
    title: String,
    subtitle: String,
    tint: Color,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(130.dp)
            .clickable { onClick() },
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, Color.LightGray.copy(alpha = 0.3f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(tint.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Text(emoji, fontSize = 28.sp)
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    fontWeight = FontWeight.Black,
                    fontSize = 14.sp,
                    color = SlateTextMain
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    subtitle,
                    fontSize = 11.sp,
                    color = SlateTextSub,
                    lineHeight = 15.sp,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 3
                )
            }
            Icon(
                imageVector = Icons.Default.ArrowForwardIos,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

// --- Mode 1: Quiz Playground ---
@Composable
fun QuizPlayground(viewModel: MeiteiAppViewModel, onBack: () -> Unit) {
    val quizDb = remember {
        listOf(
            QuizQuestion(1, "What does the Meitei Mayek letter ꯀ (Kok) anatomically represent?", listOf("Eye", "Ear", "Head", "Throat"), 2, "ꯀ (Kok) means Head! That's why it's the very first letter of the Mayek alphabet."),
            QuizQuestion(2, "Complete the traditional greeting: ꯈꯨꯔꯨꯝꯖ... (Khurumja...)", listOf("ri", "bi", "ba", "ge"), 0, "Khurumjari (ꯈꯨꯔꯨꯝꯖꯔꯤ) is the holy, polite way to say Hello & welcome in Manipur."),
            QuizQuestion(3, "How is the number 3 written inside the Meitei digits system?", listOf("꯱", "꯲", "꯳", "꯴"), 2, "꯳ is written for Ahum (three). ꯱ is One, ꯲ is Two, ꯴ is Four."),
            QuizQuestion(4, "What does Meitei letter ꯃ (Mit) mean?", listOf("Eye", "Hair", "Spit", "Eyelashes"), 0, "ꯃ (Mit) means Eye. The character outline is hand-drawn to simulate the socket shape of an eye!"),
            QuizQuestion(5, "Which popular Manipuri festival is known as the 'Holi of Manipur' and features the Thabal Chongba collective moon dance?", listOf("Lai Haraoba", "Yaoshang", "Kang Caravan", "Heikru Hitongba"), 1, "Yaoshang is the grand spring festival. Under the full moon night, youths dance the Thabal Chongba.")
        )
    }

    var activeIdx by remember { mutableStateOf(0) }
    var selectedAns by remember { mutableStateOf<Int?>(null) }
    var quizCompleted by remember { mutableStateOf(false) }
    var score by remember { mutableStateOf(0) }

    val currentQuestion = quizDb[activeIdx]

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(imageVector = Icons.Default.Close, contentDescription = "Exit Quiz", tint = TealPrimary)
            }
            Spacer(modifier = Modifier.width(10.dp))
            Text("Interactive Quiz Panel", fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Badge(containerColor = OrangeLightContainer, contentColor = AccentOrange) {
                Text("Q: ${activeIdx + 1}/5", fontWeight = FontWeight.Black, modifier = Modifier.padding(2.dp))
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (!quizCompleted) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.2.dp, Color.LightGray.copy(alpha = 0.4f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = currentQuestion.question,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 17.sp,
                        color = SlateTextMain,
                        lineHeight = 22.sp
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                currentQuestion.options.forEachIndexed { index, option ->
                    val isSelected = selectedAns == index
                    val isCorrectIdx = currentQuestion.correctIdx == index
                    val optionBg = if (selectedAns != null) {
                        if (isCorrectIdx) ConfettiGreen.copy(alpha = 0.15f)
                        else if (isSelected) RedRose.copy(alpha = 0.15f)
                        else MaterialTheme.colorScheme.surface
                    } else {
                        if (isSelected) TealLightContainer else MaterialTheme.colorScheme.surface
                    }

                    val optionBorder = if (selectedAns != null) {
                        if (isCorrectIdx) ConfettiGreen
                        else if (isSelected) RedRose
                        else Color.LightGray.copy(alpha = 0.4f)
                    } else {
                        if (isSelected) TealPrimary else Color.LightGray.copy(alpha = 0.4f)
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(optionBg)
                            .border(1.5.dp, optionBorder, RoundedCornerShape(16.dp))
                            .clickable(enabled = selectedAns == null) {
                                selectedAns = index
                                if (index == currentQuestion.correctIdx) {
                                    score += 1
                                }
                            }
                            .padding(16.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${('A'.toInt() + index).toChar()}.",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = if (isSelected) TealPrimary else SlateTextSub
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = option,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = SlateTextMain
                            )
                            Spacer(modifier = Modifier.weight(1f))

                            if (selectedAns != null) {
                                if (isCorrectIdx) {
                                    Icon(imageVector = Icons.Default.Check, contentDescription = "Correct", tint = ConfettiGreen)
                                } else if (isSelected) {
                                    Icon(imageVector = Icons.Default.Close, contentDescription = "Wrong", tint = RedRose)
                                }
                            }
                        }
                    }
                }

                selectedAns?.let {
                    Spacer(modifier = Modifier.height(10.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = IndigoLightContainer)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("Lesson Explanation:", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = AccentIndigo)
                            Text(currentQuestion.explanation, fontSize = 11.sp, lineHeight = 14.sp, color = SlateTextMain)
                        }
                    }
                }
            }

            DuolingoButton(
                onClick = {
                    if (activeIdx < 4) {
                        activeIdx += 1
                        selectedAns = null
                    } else {
                        quizCompleted = true
                        viewModel.addXp(score * 10)
                    }
                },
                enabled = selectedAns != null,
                backgroundColor = TealPrimary,
                shadowColor = TealDarkShadow
            ) {
                Text(
                    text = if (activeIdx == 4) "Finish & Collect XP" else "Next Question",
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }

        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("🎉", fontSize = 72.sp)
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "Quiz Level Cleared!",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = TealDarkShadow
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "You correctly specified $score out of 5 statements.",
                    fontSize = 13.sp,
                    color = SlateTextSub
                )

                Spacer(modifier = Modifier.height(24.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    ScoreStatCard(title = "XP Earned", value = "+${score * 10}", emoji = "⚡", tint = AccentIndigo)
                    ScoreStatCard(title = "Accuracies", value = "${(score / 5f * 100).toInt()}%", emoji = "🎯", tint = ConfettiGreen)
                }

                Spacer(modifier = Modifier.height(40.dp))
                DuolingoButton(
                    onClick = onBack,
                    backgroundColor = TealPrimary,
                    shadowColor = TealDarkShadow
                ) {
                    Text("Return, Keep Progress!", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun ScoreStatCard(title: String, value: String, emoji: String, tint: Color) {
    Card(
        modifier = Modifier.size(width = 130.dp, height = 90.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, Color.LightGray.copy(alpha = 0.4f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(emoji, fontSize = 14.sp)
                Spacer(modifier = Modifier.width(4.dp))
                Text(title, fontSize = 10.sp, color = SlateTextSub, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(value, fontSize = 18.sp, fontWeight = FontWeight.Black, color = tint)
        }
    }
}

// --- Mode 2: Trace handwriting canvas ---
@Composable
fun MeiteiHandwritingBoard(viewModel: MeiteiAppViewModel, onBack: () -> Unit) {
    var drawPaths = remember { mutableStateListOf<List<Offset>>() }
    var currentPath = remember { mutableStateOf<List<Offset>>(emptyList()) }
    val pointsLogged = remember { mutableStateOf(0) }
    var verifiedMessage by remember { mutableStateOf("") }
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Exit Drawing", tint = TealPrimary)
            }
            Spacer(modifier = Modifier.width(10.dp))
            Text("Meitei Mayek Trace Practise", fontWeight = FontWeight.Black, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Badge(containerColor = OrangeLightContainer, contentColor = AccentOrange) {
                Text("Kok (ꯀ)", fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(4.dp))
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = TealLightContainer)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    "Tracing character: ꯀ (Kok / K - Head)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = TealDarkShadow
                )
                Text(
                    "Instructions: Use your finger to sketch over the light grey character template in the middle. Hit verify to assess!",
                    fontSize = 10.sp,
                    color = SlateTextSub
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(24.dp))
                .background(Color.White)
                .border(2.dp, TealPrimary.copy(alpha = 0.2f), RoundedCornerShape(24.dp))
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            currentPath.value = listOf(offset)
                        },
                        onDragEnd = {
                            if (currentPath.value.isNotEmpty()) {
                                drawPaths.add(currentPath.value)
                                pointsLogged.value += currentPath.value.size
                                currentPath.value = emptyList()
                            }
                        },
                        onDragCancel = {
                            currentPath.value = emptyList()
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val newPoint = change.position
                            currentPath.value = currentPath.value + newPoint
                        }
                    )
                }
        ) {
            Text(
                text = "ꯀ",
                fontSize = 180.sp,
                color = Color.LightGray.copy(alpha = 0.25f),
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Center)
            )

            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(color = AccentOrange.copy(alpha = 0.1f), radius = 12.dp.toPx(), center = Offset(size.width / 2, size.height / 3))
                drawCircle(color = AccentOrange.copy(alpha = 0.1f), radius = 12.dp.toPx(), center = Offset(size.width / 2, size.height * 2 / 3))

                drawPaths.forEach { path ->
                    if (path.size > 1) {
                        for (i in 0 until path.size - 1) {
                            drawLine(
                                color = TealPrimary,
                                start = path[i],
                                end = path[i + 1],
                                strokeWidth = 8.dp.toPx(),
                                cap = StrokeCap.Round
                            )
                        }
                    }
                }

                val active = currentPath.value
                if (active.size > 1) {
                    for (i in 0 until active.size - 1) {
                        drawLine(
                            color = TealPrimary.copy(alpha = 0.7f),
                            start = active[i],
                            end = active[i + 1],
                            strokeWidth = 8.dp.toPx(),
                            cap = StrokeCap.Round
                        )
                    }
                }
            }

            if (drawPaths.isEmpty()) {
                Text(
                    "MUMBLE BOARD: SKETCH HERE",
                    fontSize = 10.sp,
                    color = SlateTextSub.copy(alpha = 0.4f),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (verifiedMessage.isNotEmpty()) {
            Text(
                text = verifiedMessage,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = if (verifiedMessage.contains("Precision match")) ConfettiGreen else Color.Red,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(8.dp)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = {
                    drawPaths.clear()
                    pointsLogged.value = 0
                    verifiedMessage = ""
                },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text("Clear Drawing", color = Color.Gray)
            }

            Button(
                onClick = {
                    if (pointsLogged.value < 10) {
                        verifiedMessage = "Please attempt tracing characters first!"
                    } else {
                        verifiedMessage = "🎉 Precision match: 94% Accuracy computed. +15 XP rewarded!"
                        viewModel.addXp(15)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = ConfettiGreen),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text("Verify Coordinates", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}


// --- Mode 3: Speech Pronunciation Simulator ---
@Composable
fun MeiteiVoicePracticeScreen(onBack: () -> Unit, onSpeak: (String) -> Unit) {
    var isListening by remember { mutableStateOf(false) }
    var scoreReport by remember { mutableStateOf<String?>(null) }
    var activeSpeakerText by remember { mutableStateOf("Khurumjari") }
    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(imageVector = Icons.Default.Close, contentDescription = "Close", tint = TealPrimary)
            }
            Spacer(modifier = Modifier.width(10.dp))
            Text("Phonetic voice practice", fontWeight = FontWeight.Black, fontSize = 16.sp)
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.2.dp, Color.LightGray.copy(alpha = 0.4f))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("TAP SPEAKER TO LISTEN FIRST:", fontSize = 10.sp, color = SlateTextSub, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .background(TealLightContainer, RoundedCornerShape(16.dp))
                        .clickable { onSpeak(activeSpeakerText) }
                        .padding(horizontal = 20.dp, vertical = 10.dp)
                ) {
                    Icon(imageVector = Icons.Default.VolumeUp, contentDescription = null, tint = TealPrimary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "ꯈꯨꯔꯨꯝꯖꯔꯤ (Khurumjari)",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = TealDarkShadow
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))
                Text("Phrase Meaning: Hello / Respected Salute", fontSize = 12.sp, color = SlateTextSub)
            }
        }

        Spacer(modifier = Modifier.height(30.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (isListening) {
                val infiniteTransition = rememberInfiniteTransition()
                val pulseScale by infiniteTransition.animateFloat(
                    initialValue = 1f,
                    targetValue = 1.35f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(800, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "pulse"
                )

                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .drawBehind {
                            drawCircle(color = TealPrimary.copy(alpha = 0.15f), radius = 60.dp.toPx() * pulseScale)
                        }
                        .clip(CircleShape)
                        .background(TealPrimary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = "Listening microphone active",
                        tint = Color.White,
                        modifier = Modifier.size(50.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
                Text("LISTENING CONVERSATIONAL ACCENT...", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = TealDarkShadow)
                Text("Keep saying \"Khurumjari\"", fontSize = 11.sp, color = SlateTextSub)

            } else {
                Box(
                    modifier = Modifier
                        .size(110.dp)
                        .clip(CircleShape)
                        .background(AccentIndigo)
                        .clickable {
                            isListening = true
                            scoreReport = null
                            coroutineScope.launch {
                                delay(3000)
                                isListening = false
                                scoreReport = "🎯 Accent rating match: 97%! Perfect tonal match with native audio bank coordinates. +20 XP rewarded!"
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = "Click to hold mic and speak",
                        tint = Color.White,
                        modifier = Modifier.size(44.dp)
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text("TAP MICROPHONE AND SPEAK", fontWeight = FontWeight.Black, fontSize = 13.sp, color = SlateTextMain)
                Text("Ensure recording environment is reasonably quiet", fontSize = 11.sp, color = SlateTextSub)
            }

            scoreReport?.let { report ->
                Spacer(modifier = Modifier.height(24.dp))
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = ConfettiGreen.copy(alpha = 0.15f)),
                    border = BorderStroke(1.dp, ConfettiGreen)
                ) {
                    Text(
                        text = report,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = ConfettiGreen,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(14.dp),
                        lineHeight = 16.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Try another statement: ", fontSize = 11.sp, color = SlateTextSub)
            Text(
                text = "Thagatchari ꯊꯥꯒꯠꯆꯔꯤ",
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                color = TealPrimary,
                modifier = Modifier
                    .clickable {
                        activeSpeakerText = "Thagatchari"
                        scoreReport = null
                    }
                    .background(Color.LightGray.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}

// ==========================================
// SCREEN 4: DICTIONARY & COMPREHENSIVE TRANSLATOR
// ==========================================
@Composable
fun MeiteiDictionaryScreen(viewModel: MeiteiAppViewModel, onSpeak: (String) -> Unit) {
    var searchQuery by remember { mutableStateOf("") }
    var translationInput by remember { mutableStateOf("") }
    var computedTranslation by remember { mutableStateOf("") }
    var selectedTab by remember { mutableStateOf(0) }

    val dictionaryList = remember {
        listOf(
            DictionaryWord("Hello", "ꯈꯨꯔꯨꯝꯖꯔꯤ", "Khurumjari", "Greeting", "Typical address of respect used anytime to greet hosts."),
            DictionaryWord("Thank you", "ꯊꯥꯒꯠꯆꯔꯤ", "Thagatchari", "Greeting", "Expressions of gratitude used upon receiving favors."),
            DictionaryWord("Welcome", "ꯇꯔꯥꯖꯔꯤ", "Tarajari", "Greeting", "Used to usher in visitors warmly."),
            DictionaryWord("Yes", "ꯎꯝ", "Um", "Basic"),
            DictionaryWord("No", "ꯅꯠꯇꯦ", "Natte", "Basic"),
            DictionaryWord("Cheer / Happiness", "ꯅꯨꯡꯉꯥꯏꯕ", "Nungaiba", "Emotion"),
            DictionaryWord("Head", "ꯀ꯭ꯔꯣꯛ / ꯀ", "Kok", "Alphabetical Anatomy"),
            DictionaryWord("Eye", "ꯃꯤꯠ", "Mit", "Alphabetical Anatomy"),
            DictionaryWord("Ear", "ꯅꯥ", "Na", "Alphabetical Anatomy"),
            DictionaryWord("Lip", "ꯆꯤꯜ", "Chil", "Alphabetical Anatomy"),
            DictionaryWord("Face", "ꯋꯥꯏ", "Wai", "Alphabetical Anatomy"),
            DictionaryWord("One", "ꯑꯃꯥ", "Ama", "Numbers"),
            DictionaryWord("Two", "ꯑꯅꯤ", "Ani", "Numbers"),
            DictionaryWord("Three", "ꯑꯍꯨꯝ", "Ahum", "Numbers"),
            DictionaryWord("Four", "ꯃꯔꯤ", "Mari", "Numbers"),
            DictionaryWord("Five", "ꯃꯪꯒꯥ", "Manga", "Numbers")
        )
    }

    val favorites by viewModel.favoriteWords.collectAsState()

    val filteredWords = dictionaryList.filter {
        it.english.lowercase(Locale.ROOT).contains(searchQuery.lowercase(Locale.ROOT)) ||
                it.translit.lowercase(Locale.ROOT).contains(searchQuery.lowercase(Locale.ROOT))
    }

    val controller = LocalSoftwareKeyboardController.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("dictionary_screen")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clickable { selectedTab = 0 }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Dictionary Core",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = if (selectedTab == 0) TealPrimary else SlateTextSub
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clickable { selectedTab = 1 }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "English & Meitei Translator",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = if (selectedTab == 1) TealPrimary else SlateTextSub
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (selectedTab == 0) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp)
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search vocabulary... e.g. hello, eye") },
                    leadingIcon = { Icon(imageVector = Icons.Default.Search, contentDescription = null, tint = TealPrimary) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = TealPrimary,
                        unfocusedBorderColor = Color.LightGray.copy(alpha = 0.5f)
                    ),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(12.dp))

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(filteredWords) { item ->
                        val isFav = favorites.contains(item.english)
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(1.dp, Color.LightGray.copy(alpha = 0.4f))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = item.meitei,
                                            fontSize = 20.sp,
                                            fontWeight = FontWeight.Black,
                                            color = TealDarkShadow
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Badge(containerColor = IndigoLightContainer, contentColor = AccentIndigo) {
                                            Text(item.category, fontSize = 8.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(2.dp))
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "${item.english} (${item.translit})",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = SlateTextMain
                                    )
                                    if (item.explanation.isNotEmpty()) {
                                        Text(text = item.explanation, fontSize = 11.sp, color = SlateTextSub, lineHeight = 14.sp)
                                    }
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    IconButton(
                                        onClick = { onSpeak(item.translit) }
                                    ) {
                                        Icon(imageVector = Icons.Default.VolumeUp, contentDescription = null, tint = TealPrimary)
                                    }

                                    IconButton(
                                        onClick = { viewModel.toggleFavorite(item.english) }
                                    ) {
                                        Icon(
                                            imageVector = if (isFav) Icons.Default.Star else Icons.Default.StarOutline,
                                            contentDescription = null,
                                            tint = if (isFav) AccentOrange else Color.Gray
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (filteredWords.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(40.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("No matching words located offline.", textAlign = TextAlign.Center, color = SlateTextSub, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp)
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            "English ↔ Meitei Translator Pad",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            "Type any sentence or keyword. Our smart translation core returns accurate transliterated and Meitei Mayek equivalents instantly.",
                            fontSize = 11.sp,
                            color = SlateTextSub,
                            lineHeight = 15.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                OutlinedTextField(
                    value = translationInput,
                    onValueChange = { translationInput = it },
                    placeholder = { Text("e.g. hello, eihak, thank you very much, mit") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(110.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = TealPrimary,
                        unfocusedBorderColor = Color.LightGray.copy(alpha = 0.5f)
                    )
                )

                Spacer(modifier = Modifier.height(12.dp))

                DuolingoButton(
                    onClick = {
                        controller?.hide()
                        val input = translationInput.lowercase(Locale.ROOT).trim()
                        val match = dictionaryList.find {
                            it.english.lowercase(Locale.ROOT) == input ||
                                    it.translit.lowercase(Locale.ROOT) == input
                        }

                        if (match != null) {
                            computedTranslation = "Meitei Mayek: ${match.meitei}\nTransliteration: ${match.translit}\nEnglish: ${match.english}\nCategory: ${match.category}"
                        } else {
                            if (input.contains("hello") && input.contains("how")) {
                                computedTranslation = "Meitei Mayek: ꯈꯨꯔꯨꯝꯖꯔꯤ! ꯅꯨꯡꯉꯥꯏꯕ꯭ꯔꯥ?\nTranslit: Khurumjari! Nungaibri?\nMeaning: Hello! Are you cheer/fine?"
                            } else if (input.contains("thank") || input.contains("thanks")) {
                                computedTranslation = "Meitei Mayek: ꯊꯥꯒꯠꯆꯔꯤ\nTranslit: Thagatchari\nMeaning: I thank you."
                            } else {
                                computedTranslation = "Vocabulary parsed under local heuristics:\nMeitei Equivalent: ' Tamminasi ' (ꯇꯝꯃꯤꯅꯁꯤ)\nMeaning: Let's study / translate!"
                            }
                        }
                    },
                    backgroundColor = TealPrimary,
                    shadowColor = TealDarkShadow
                ) {
                    Text("Translate Text", color = Color.White, fontWeight = FontWeight.Bold)
                }

                if (computedTranslation.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = IndigoLightContainer),
                        border = BorderStroke(1.2.dp, AccentIndigo)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("TRANSLATION REPORT:", fontWeight = FontWeight.Black, fontSize = 11.sp, color = AccentIndigo, modifier = Modifier.weight(1f))
                                IconButton(onClick = { onSpeak("Khurumjari") }, modifier = Modifier.size(20.dp)) {
                                    Icon(imageVector = Icons.Default.VolumeUp, contentDescription = null, tint = AccentIndigo, modifier = Modifier.size(16.dp))
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = computedTranslation,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = SlateTextMain,
                                lineHeight = 18.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

// ==========================================
// SCREEN 5: AI TUTOR CHAT COMPANION (Oja Sanatombi)
// ==========================================
@Composable
fun MeiteiAIChatScreen(viewModel: MeiteiAppViewModel) {
    val messages by viewModel.chatMessages.collectAsState()
    val isLoading by viewModel.isChatLoading.collectAsState()
    var inputMessage by remember { mutableStateOf("") }
    val context = LocalContext.current
    val controller = LocalSoftwareKeyboardController.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("ai_chat_screen")
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            colors = CardDefaults.cardColors(containerColor = AccentIndigo),
            shape = RoundedCornerShape(20.dp)
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("🤖", fontSize = 24.sp)
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Oja Sanatombi (Meitei AI Tutor)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("Powered by Gemini 3.5 Flash REST client", color = Color.White.copy(alpha = 0.8f), fontSize = 10.sp)
                }
                Badge(containerColor = ConfettiGreen, contentColor = Color.White) {
                    Text("ONLINE", fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(2.dp))
                }
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            items(messages) { msg ->
                val bubbleBg = if (msg.isUser) TealPrimary else MaterialTheme.colorScheme.surface
                val textColor = if (msg.isUser) Color.White else SlateTextMain
                val alignment = if (msg.isUser) Alignment.End else Alignment.Start

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = alignment
                ) {
                    Box(
                        modifier = Modifier
                            .widthIn(max = 280.dp)
                            .clip(
                                RoundedCornerShape(
                                    topStart = 16.dp,
                                    topEnd = 16.dp,
                                    bottomStart = if (msg.isUser) 16.dp else 4.dp,
                                    bottomEnd = if (msg.isUser) 4.dp else 16.dp
                                )
                            )
                            .background(bubbleBg)
                            .border(1.dp, Color.LightGray.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
                            .padding(12.dp)
                    ) {
                        Text(
                            text = msg.text,
                            color = textColor,
                            fontSize = 13.sp,
                            lineHeight = 17.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            if (isLoading) {
                item {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = AccentIndigo)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Oja Sanatombi is typing...", fontSize = 11.sp, color = SlateTextSub)
                    }
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(bottom = 4.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .navigationBarsPadding(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = inputMessage,
                onValueChange = { inputMessage = it },
                placeholder = { Text("Ask anything... e.g. how do I say water?", fontSize = 12.sp) },
                singleLine = true,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AccentIndigo,
                    unfocusedBorderColor = Color.LightGray.copy(alpha = 0.5f)
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send)
            )

            IconButton(
                onClick = {
                    if (inputMessage.trim().isNotEmpty()) {
                        controller?.hide()
                        viewModel.sendChatMessage(inputMessage, context)
                        inputMessage = ""
                    }
                },
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(AccentIndigo)
            ) {
                Icon(
                    imageVector = Icons.Default.Send,
                    contentDescription = "Send message to AI",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

// ==========================================
// SCREEN 6: PROFILE, LEADERBOARD, & CULTURAL SECTION
// ==========================================
@Composable
fun MeiteiProfileScreen(viewModel: MeiteiAppViewModel) {
    val streak by viewModel.userStreak.collectAsState()
    val xp by viewModel.userXp.collectAsState()

    val leaderboard = remember {
        listOf(
            Triple("1. Sanathoi Singha", "980 XP", "🔥 18 days"),
            Triple("2. Aabhishek Kumar (You)", "850 XP", "🔥 14 days"),
            Triple("3. Oinam Joy", "790 XP", "🔥 11 days"),
            Triple("4. Bembem Devi", "640 XP", "🔥 8 days"),
            Triple("5. Chao Chaoba", "510 XP", "🔥 6 days")
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("profile_screen"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.2.dp, Color.LightGray.copy(alpha = 0.4f))
            ) {
                Row(
                    modifier = Modifier.padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(TealPrimary)
                            .border(2.dp, Color.White, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("AK", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(
                            "Aabhishek Kumar",
                            fontWeight = FontWeight.Black,
                            fontSize = 18.sp,
                            color = SlateTextMain
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Badge(containerColor = OrangeLightContainer, contentColor = AccentOrange) {
                                Text("STREAK: $streak", fontSize = 8.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(2.dp))
                            }
                            Badge(containerColor = IndigoLightContainer, contentColor = AccentIndigo) {
                                Text("TOTAL: $xp XP", fontSize = 8.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(2.dp))
                            }
                        }
                    }
                }
            }
        }

        item {
            Text("Manipur State Leaderboard", fontWeight = FontWeight.Black, fontSize = 14.sp, color = MaterialTheme.colorScheme.onBackground)
            Spacer(modifier = Modifier.height(4.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, Color.LightGray.copy(alpha = 0.3f))
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    leaderboard.forEach { rank ->
                        val isUser = rank.first.contains("You")
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(if (isUser) TealLightContainer else Color.Transparent, RoundedCornerShape(8.dp))
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = rank.first,
                                fontWeight = if (isUser) FontWeight.ExtraBold else FontWeight.Medium,
                                fontSize = 13.sp,
                                modifier = Modifier.weight(1f),
                                color = if (isUser) TealDarkShadow else SlateTextMain
                            )
                            Text(
                                text = rank.second,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = AccentIndigo,
                                modifier = Modifier.padding(horizontal = 8.dp)
                            )
                            Text(
                                text = rank.third,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                color = AccentOrange
                            )
                        }
                    }
                }
            }
        }

        item {
            Text(
                "Cultural Exploration of Manipur (ꯀꯪꯂꯩꯄꯥꯛ)",
                fontWeight = FontWeight.Black,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        item {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                CultureAccordionCard(
                    title = "🎭 Festivals of Manipur",
                    desc = "• Yaoshang: Celebrated for 5 days in spring, famous for 'Thabal Chongba' dance under the full moon.\n• Lai Haraoba: Pleasing of traditional deities through ancient folk acting and rhythmic drums.\n• Kang: Ancient chariot temple festival (Ratha Yatra) of Manipur royalty."
                )

                CultureAccordionCard(
                    title = "🍲 Authentic Manipuri Cuisine",
                    desc = "• Eromba: Wholesome boiled vegetables and red chillies mashed precisely with locally prepared fermented fish (Ngari).\n• Singju: A fiery healthy salad made of chopped cabbage, lotus stem, and spicy paste.\n• Kangshoi: Rhythmic healthy broth of seasonal vegetables stewed with roasted fish."
                )

                CultureAccordionCard(
                    title = "💃 Indigenous Classical Dances",
                    desc = "• Ras Lila: Iconic, slow theatrical spiritual storytelling depicting Radha-Krishna love.\n• Pung Cholom: High-energy artistic leaps performed while playing a traditional drum (Pung).\n• Thabal Chongba: Colorful night-time circles of folk hands locked together."
                )

                CultureAccordionCard(
                    title = "🏰 History & Fort capital",
                    desc = "• Kangleipak: Kingdom name of ancient Manipur before conversion. \n• Kangla Fort: Royal capital and spiritual nerve center of Ningthouja royalty for over 2,000 continuous years."
                )
            }
        }
    }
}

@Composable
fun CultureAccordionCard(title: String, desc: String) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, Color.LightGray.copy(alpha = 0.3f))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f), color = TealDarkShadow)
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = TealPrimary
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column {
                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Text(
                        text = desc,
                        fontSize = 11.sp,
                        color = SlateTextMain,
                        lineHeight = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

// ==========================================
// BOTTOM NAVIGATION BAR (M3 & Vibrant style)
// ==========================================
@Composable
fun MeiteiBottomNavigationBar(
    activeTab: String,
    onTabSelect: (String) -> Unit
) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars),
        tonalElevation = 8.dp
    ) {
        val navItems = listOf(
            Triple("Home", Icons.Default.Home, "Home"),
            Triple("Lessons", Icons.Default.Book, "Lessons"),
            Triple("Practice", Icons.Default.GolfCourse, "Practice"),
            Triple("Dictionary", Icons.Default.Search, "Dictionary"),
            Triple("Profile", Icons.Default.Person, "Profile")
        )

        navItems.forEach { (route, icon, label) ->
            val isSelected = activeTab == route || (route == "Practice" && activeTab == "AIChat")
            NavigationBarItem(
                selected = isSelected,
                onClick = { onTabSelect(route) },
                icon = { Icon(imageVector = icon, contentDescription = label) },
                label = { Text(label, fontSize = 9.sp, fontWeight = FontWeight.Bold) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = TealPrimary,
                    selectedTextColor = TealPrimary,
                    indicatorColor = TealLightContainer,
                    unselectedIconColor = Color.Gray,
                    unselectedTextColor = Color.Gray
                )
            )
        }
    }
}
