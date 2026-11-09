package com.facescore.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val Bg = Color(0xFF14121A)
private val CardC = Color(0xFF1E1B27)
private val LineC = Color(0xFF2E2A3B)
private val TextC = Color(0xFFF2EFF8)
private val Muted = Color(0xFFA29DB3)
private val Accent = Color(0xFFFF7A59)
private val Accent2 = Color(0xFF7CD6C4)
private val Bad = Color(0xFFFF5D73)
private val OnAccent = Color(0xFF1A1210)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Accent, onPrimary = OnAccent,
                    background = Bg, onBackground = TextC,
                    surface = CardC, onSurface = TextC
                )
            ) { App() }
        }
    }
}

/** Системная камера, по возможности сразу фронтальная. */
class FrontCameraTakePicture : ActivityResultContracts.TakePicture() {
    override fun createIntent(context: Context, input: Uri): Intent =
        super.createIntent(context, input).apply {
            putExtra("android.intent.extras.CAMERA_FACING", 1)
            putExtra("android.intent.extras.LENS_FACING_FRONT", 1)
            putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
        }
}

private sealed interface UiState {
    data object Home : UiState
    data object Loading : UiState
    data class Done(val bitmap: Bitmap, val result: ScoreResult) : UiState
    data class Error(val message: String) : UiState
}

private fun loadBitmap(context: Context, uri: Uri): Bitmap {
    val source = ImageDecoder.createSource(context.contentResolver, uri)
    return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val w = info.size.width
        val h = info.size.height
        val scale = 1600f / maxOf(w, h)
        if (scale < 1f) decoder.setTargetSize((w * scale).toInt(), (h * scale).toInt())
    }
}

@Composable
private fun App() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<UiState>(UiState.Home) }

    val photoUri = remember {
        val dir = File(context.cacheDir, "images").apply { mkdirs() }
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(dir, "photo.jpg"))
    }

    fun process(uri: Uri) {
        state = UiState.Loading
        scope.launch {
            state = try {
                val bmp = withContext(Dispatchers.IO) { loadBitmap(context, uri) }
                when (val a = FaceAnalyzer.analyze(bmp)) {
                    is Analysis.Ok -> UiState.Done(bmp, a.result)
                    is Analysis.Fail -> UiState.Error(a.message)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                UiState.Error("Не получилось обработать фото. Попробуй другое.")
            }
        }
    }

    val takePhoto = rememberLauncherForActivityResult(FrontCameraTakePicture()) { ok ->
        if (ok) process(photoUri)
    }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) process(uri)
    }

    BackHandler(enabled = state !is UiState.Home) { state = UiState.Home }

    Scaffold(containerColor = Bg) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Text("ФейсСкор", color = TextC, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))

            when (val s = state) {
                UiState.Home -> HomeScreen(
                    onCamera = {
                        try {
                            takePhoto.launch(photoUri)
                        } catch (e: ActivityNotFoundException) {
                            state = UiState.Error("На телефоне не найдено приложение камеры. Выбери фото из галереи.")
                        }
                    },
                    onGallery = {
                        pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                )
                UiState.Loading -> Box(
                    Modifier.fillMaxWidth().padding(vertical = 64.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Accent)
                        Spacer(Modifier.height(16.dp))
                        Text("Измеряю пропорции…", color = Muted)
                    }
                }
                is UiState.Done -> ResultScreen(s.bitmap, s.result) { state = UiState.Home }
                is UiState.Error -> CardBox {
                    Text(s.message, color = TextC, fontSize = 16.sp)
                    Spacer(Modifier.height(16.dp))
                    PrimaryButton("Попробовать ещё раз") { state = UiState.Home }
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(onCamera: () -> Unit, onGallery: () -> Unit) {
    Text(
        "Сфоткайся — и приложение измерит симметрию и пропорции лица по ориентирам из исследований о привлекательности. Фото обрабатывается прямо на телефоне и никуда не отправляется.",
        color = Muted, fontSize = 15.sp, lineHeight = 21.sp
    )
    Spacer(Modifier.height(20.dp))
    CardBox {
        PrimaryButton("📸  Сфоткаться", onCamera)
        Spacer(Modifier.height(10.dp))
        GhostButton("🖼  Выбрать из галереи", onGallery)
        Spacer(Modifier.height(16.dp))
        Text("Чтобы оценка была честной:", color = TextC, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        listOf(
            "смотри прямо в камеру, голова ровно",
            "камера на уровне глаз, не снизу",
            "волосы убери со лба, очки сними",
            "свет спереди, лицо целиком в кадре"
        ).forEach { Text("•  $it", color = Muted, fontSize = 14.sp, lineHeight = 22.sp) }
    }
}

@Composable
private fun ResultScreen(bitmap: Bitmap, result: ScoreResult, onAgain: () -> Unit) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    var showInfo by remember { mutableStateOf(false) }

    CardBox {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("%.1f".format(result.score), color = Accent, fontSize = 72.sp, fontWeight = FontWeight.Bold)
                Text(" /10", color = Muted, fontSize = 24.sp, modifier = Modifier.padding(bottom = 14.dp))
            }
            Text(result.verdict, color = Muted, fontSize = 15.sp)
        }
        result.warning?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = Bad, fontSize = 14.sp, lineHeight = 19.sp)
        }
        result.metrics.forEach { m ->
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(m.name, color = TextC, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text("${m.value} · ${"%.1f".format(m.score * 10)}", color = Muted, fontSize = 14.sp)
            }
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier.fillMaxWidth().height(8.dp)
                    .clip(RoundedCornerShape(99.dp)).background(LineC)
            ) {
                Box(
                    Modifier.fillMaxWidth(m.score.coerceIn(0.04f, 1f)).fillMaxHeight()
                        .clip(RoundedCornerShape(99.dp))
                        .background(Brush.horizontalGradient(listOf(Accent, Accent2)))
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(m.note, color = Muted, fontSize = 12.sp, lineHeight = 16.sp)
        }
    }

    Spacer(Modifier.height(14.dp))
    CardBox {
        Box(
            Modifier.fillMaxWidth()
                .aspectRatio(bitmap.width.toFloat() / bitmap.height)
                .clip(RoundedCornerShape(14.dp))
        ) {
            Image(
                bitmap = image, contentDescription = "Твоё фото с разметкой",
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds
            )
            Canvas(Modifier.fillMaxSize()) {
                val sx = size.width / bitmap.width
                val sy = size.height / bitmap.height
                fun o(p: P) = Offset(p.x * sx, p.y * sy)
                result.overlay.contour.forEach {
                    drawCircle(Color.White.copy(alpha = 0.45f), 1.8.dp.toPx(), o(it))
                }
                result.overlay.lines.forEach { (a, b) ->
                    drawLine(
                        Color.White.copy(alpha = 0.6f), o(a), o(b),
                        strokeWidth = 1.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 12f))
                    )
                }
                result.overlay.points.forEach {
                    drawCircle(Color.Black, 5.5.dp.toPx(), o(it))
                    drawCircle(Accent2, 4.dp.toPx(), o(it))
                }
            }
        }
    }

    Spacer(Modifier.height(14.dp))
    PrimaryButton("Ещё фото", onAgain)

    Spacer(Modifier.height(14.dp))
    CardBox {
        Text(
            if (showInfo) "Откуда берутся цифры  ▲" else "Откуда берутся цифры  ▼",
            color = TextC, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.fillMaxWidth().clickable { showInfo = !showInfo }
        )
        if (showInfo) {
            Spacer(Modifier.height(10.dp))
            Text(
                "Объективной «шкалы красоты» не существует — каждый видит её по-своему. Приложение измеряет признаки, которые в исследованиях чаще всего связывают с оценками привлекательности:\n\n" +
                    "• Симметрия — насколько совпадают левая и правая половины лица.\n" +
                    "• «Новые золотые пропорции» (Pallett, Link, Lee, 2010, Vision Research): расстояние между зрачками ≈ 46% ширины лица, от глаз до рта ≈ 36% высоты лица.\n" +
                    "• Соотношение высоты и ширины лица и нижняя треть — классические каноны пропорций.\n" +
                    "• Ширина рта относительно расстояния между зрачками.\n\n" +
                    "Результат сильно зависит от ракурса, света и камеры. Улыбка, причёска, стиль и харизма — то, что сильнее всего влияет на то, как тебя видят люди, — сюда не входят. Это игра, а не приговор.",
                color = Muted, fontSize = 13.sp, lineHeight = 19.sp
            )
        }
    }
}

@Composable
private fun CardBox(content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier.fillMaxWidth()
            .clip(shape)
            .background(CardC)
            .border(1.dp, LineC, shape)
            .padding(16.dp),
        content = content
    )
}

@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(54.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = OnAccent)
    ) { Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun GhostButton(text: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(54.dp),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, LineC),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextC)
    ) { Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
}
