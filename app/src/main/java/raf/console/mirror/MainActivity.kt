package raf.console.mirror

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.util.Size
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (intent?.action == "raf.console.mirror.OPEN_MAIN") {
            // запуск из плитки
        }

        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize(), color = Color.Black) {
                    MirrorScreen()
                }
            }
        }
    }
}

@Composable
private fun MirrorScreen() {
    val context = LocalContext.current
    val activity = context as Activity
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    // ---- разрешения ----
    var hasCamera by remember { mutableStateOf(isGranted(activity, Manifest.permission.CAMERA)) }
    var hasAudio by remember { mutableStateOf(isGranted(activity, Manifest.permission.RECORD_AUDIO)) }

    val missing = remember(hasCamera, hasAudio) {
        buildList {
            if (!hasCamera) add(Manifest.permission.CAMERA)
            if (!hasAudio) add(Manifest.permission.RECORD_AUDIO)
        }
    }

    val permissionsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { res ->
        hasCamera = res[Manifest.permission.CAMERA] == true || isGranted(activity, Manifest.permission.CAMERA)
        hasAudio = res[Manifest.permission.RECORD_AUDIO] == true || isGranted(activity, Manifest.permission.RECORD_AUDIO)
    }

    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        hasCamera = isGranted(activity, Manifest.permission.CAMERA)
        hasAudio = isGranted(activity, Manifest.permission.RECORD_AUDIO)
    }

    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                hasCamera = isGranted(activity, Manifest.permission.CAMERA)
                hasAudio = isGranted(activity, Manifest.permission.RECORD_AUDIO)
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    if (!hasCamera) {
        Box(Modifier.fillMaxSize())
        PermissionDialog(
            needCamera = !hasCamera,
            needAudio = !hasAudio,
            permanentlyDeniedCamera = permanentlyDenied(activity, Manifest.permission.CAMERA),
            permanentlyDeniedAudio = permanentlyDenied(activity, Manifest.permission.RECORD_AUDIO),
            onGrant = { permissionsLauncher.launch(missing.toTypedArray()) },
            onOpenSettings = {
                val uri = Uri.fromParts("package", activity.packageName, null)
                settingsLauncher.launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = uri })
            }
        )
        return
    }

    // ---- камера / состояния ----
    var useFront by remember { mutableStateOf(true) }   // фронт/тыл
    var doMirror by remember { mutableStateOf(true) }   // зеркалить превью (только фронталка)
    var torchOn by remember { mutableStateOf(false) }   // фонарик (тыл)

    // --- QR-сканер (непрерывный) ---
    var qrMode by remember { mutableStateOf(false) }          // режим сканера
    var showQrDialog by remember { mutableStateOf(false) }    // показать диалог результата
    var lastQr by remember { mutableStateOf<String?>(null) }  // последний показанный текст

    // анти-спам: не показывать одинаковое значение чаще, чем раз в N мс
    val COOLDOWN_MS = 1200L
    var lastShownValue by remember { mutableStateOf<String?>(null) }
    var lastShownAt by remember { mutableStateOf(0L) }

    val scannerOptions = remember {
        // только QR — быстрее и точнее
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
    }
    val barcodeScanner = remember { BarcodeScanning.getClient(scannerOptions) }
    var analysis: ImageAnalysis? by remember { mutableStateOf(null) }

    fun resetQrState() {
        // не трогаем lastShownValue/At — они как фильтр от спама
        lastQr = null
        showQrDialog = false
    }

    // фоновый executor для анализа
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { cameraExecutor.shutdown() } }

    val previewView = remember {
        PreviewView(activity).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    var camera: Camera? by remember { mutableStateOf(null) }
    var imageCapture: ImageCapture? by remember { mutableStateOf(null) }
    var videoCapture: VideoCapture<Recorder>? by remember { mutableStateOf(null) }
    var activeRecording: Recording? by remember { mutableStateOf(null) }
    var zoomRatio by remember { mutableStateOf(1f) }

    // --- анимации фото ---
    val flashAlpha = remember { Animatable(0f) }
    val frameAlpha = remember { Animatable(0f) }
    val frameScale = remember { Animatable(1f) }

    // --- HUD видео ---
    var recordingStart by remember { mutableStateOf<Long?>(null) }
    var elapsedText by remember { mutableStateOf("00:00:00") }
    var timerJob by remember { mutableStateOf<Job?>(null) }

    fun startHudTimer() {
        timerJob?.cancel()
        timerJob = scope.launch {
            while (true) {
                val start = recordingStart ?: break
                val sec = max(0L, (System.currentTimeMillis() - start) / 1000)
                val h = sec / 3600
                val m = (sec % 3600) / 60
                val s = sec % 60
                elapsedText = "%02d:%02d:%02d".format(h, m, s)
                delay(1000)
            }
        }
    }

    val mainExecutor = remember { ContextCompat.getMainExecutor(activity) }
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(activity) }

    @OptIn(ExperimentalGetImage::class)
    fun bindCamera() {
        cameraProviderFuture.addListener({
            val cameraProvider = runCatching { cameraProviderFuture.get() }.getOrNull() ?: return@addListener
            val selector = if (useFront) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA

            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }

            val imgCap = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            val recorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.fromOrderedList(
                        listOf(Quality.FHD, Quality.HD, Quality.SD),
                        FallbackStrategy.higherQualityOrLowerThan(Quality.HD)
                    )
                )
                .build()
            val vidCap = VideoCapture.withOutput(recorder)

            // Анализатор кадров для QR (работает пока qrMode = true)
            val analysisUseCase = if (qrMode) {
                ImageAnalysis.Builder()
                    .setTargetResolution(Size(1280, 720))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build().also { analysisUC ->
                        analysisUC.setAnalyzer(cameraExecutor) { imageProxy: ImageProxy ->
                            val mediaImage = imageProxy.image ?: run {
                                imageProxy.close(); return@setAnalyzer
                            }
                            val rotation = imageProxy.imageInfo.rotationDegrees
                            val image = InputImage.fromMediaImage(mediaImage, rotation)

                            barcodeScanner.process(image)
                                .addOnSuccessListener { barcodes ->
                                    // непрерывный режим: не показываем новый диалог, если старый открыт
                                    if (!showQrDialog) {
                                        val now = System.currentTimeMillis()
                                        val hit = barcodes.firstOrNull { !it.rawValue.isNullOrBlank() }?.rawValue
                                        if (!hit.isNullOrBlank()) {
                                            // фильтр: одинаковые значения — не чаще, чем раз в COOLDOWN_MS
                                            val allowByValue = hit != lastShownValue || (now - lastShownAt) >= COOLDOWN_MS
                                            if (allowByValue) {
                                                lastQr = hit
                                                lastShownValue = hit
                                                lastShownAt = now
                                                showQrDialog = true
                                            }
                                        }
                                    }
                                }
                                .addOnFailureListener { /* ignore */ }
                                .addOnCompleteListener { imageProxy.close() }
                        }
                    }
            } else null

            runCatching {
                cameraProvider.unbindAll()

                camera = if (analysisUseCase != null) {
                    cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, imgCap, vidCap, analysisUseCase)
                } else {
                    cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, imgCap, vidCap)
                }
                imageCapture = imgCap
                videoCapture = vidCap
                analysis = analysisUseCase

                if (useFront) {
                    torchOn = false
                } else {
                    camera?.let { cam ->
                        val hasFlash = cam.cameraInfo.hasFlashUnit()
                        if (hasFlash) cam.cameraControl.enableTorch(torchOn) else torchOn = false
                    }
                }
                zoomRatio = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f

                // автофокус по центру — повышает шанс считывания
                previewView.post {
                    val factory = previewView.meteringPointFactory
                    val point = factory.createPoint(
                        previewView.width / 2f,
                        previewView.height / 2f
                    )
                    val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
                        .setAutoCancelDuration(2, TimeUnit.SECONDS)
                        .build()
                    camera?.cameraControl?.startFocusAndMetering(action)
                }
            }
        }, mainExecutor)
    }

    LaunchedEffect(useFront) { bindCamera() }
    LaunchedEffect(qrMode) {
        resetQrState()
        bindCamera()
    }

    // --- эффекты при фото ---
    fun playPhotoFx() = scope.launch {
        flashAlpha.snapTo(0f)
        frameAlpha.snapTo(0f)
        frameScale.snapTo(1f)

        flashAlpha.animateTo(0.9f, tween(durationMillis = 80, easing = LinearEasing))
        flashAlpha.animateTo(0f, tween(durationMillis = 160, easing = LinearEasing))

        frameAlpha.animateTo(1f, tween(80))
        frameScale.animateTo(0.85f, tween(180))
        frameAlpha.animateTo(0f, tween(140))
        frameScale.snapTo(1f)
    }

    fun takePhoto() {
        val imgCap = imageCapture ?: return
        playPhotoFx()

        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "Mirror_$name")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Mirror")
            }
        }
        val opts = ImageCapture.OutputFileOptions.Builder(
            activity.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values
        ).build()

        imgCap.takePicture(opts, mainExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onError(exception: ImageCaptureException) {}
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {}
        })
    }

    fun startRecording(): Recording? {
        val vidCap = videoCapture ?: return null
        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
        val output = MediaStoreOutputOptions.Builder(
            activity.contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "Mirror_$name")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Mirror")
            }
        }).build()

        var pending = vidCap.output.prepareRecording(activity, output)
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            // без звука
        }
        if (hasAudio) pending = pending.withAudioEnabled()

        recordingStart = System.currentTimeMillis()
        startHudTimer()

        return pending.start(mainExecutor) { /* events */ }
    }

    fun stopRecording() {
        activeRecording?.stop()
        activeRecording = null
        recordingStart = null
        timerJob?.cancel()
        elapsedText = "00:00:00"
    }

    // Жесты: зум (щипок) + фото/видео (тап/лонг-тап)
    val gestureLayer = Modifier
        .fillMaxSize()
        .then(
            if (qrMode) Modifier   // в режиме QR не мешаем скану
            else Modifier
                .pointerInput(useFront, doMirror) {
                    detectTransformGestures { _: Offset, _: Offset, zoomChange: Float, _: Float ->
                        val cam = camera ?: return@detectTransformGestures
                        val info = cam.cameraInfo
                        val minZ = info.zoomState.value?.minZoomRatio ?: 1f
                        val maxZ = info.zoomState.value?.maxZoomRatio ?: 10f
                        val newZ = (zoomRatio * zoomChange).coerceIn(minZ, maxZ)
                        zoomRatio = newZ
                        cam.cameraControl.setZoomRatio(newZ)
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            if (activeRecording != null) {
                                stopRecording()
                            } else {
                                takePhoto()
                            }
                        },
                        onLongPress = {
                            if (activeRecording == null) {
                                activeRecording = startRecording()
                            } else {
                                stopRecording()
                            }
                        }
                    )
                }
        )

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { scaleX = if (doMirror && useFront) -1f else 1f }
        )

        Box(gestureLayer)

        PhotoFxOverlay(
            flashAlpha = flashAlpha.value,
            frameAlpha = frameAlpha.value,
            frameScale = frameScale.value
        )

        if (qrMode) {
            QrOverlay()
        }

        if (activeRecording != null) {
            RecordingHud(elapsed = elapsedText)
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(16.dp)
        ) {
            ControlBar(
                useFront = useFront,
                doMirror = doMirror,
                torchOn = torchOn,
                qrMode = qrMode,
                onToggleCamera = {
                    useFront = !useFront
                    if (useFront) {
                        torchOn = false
                    } else {
                        camera?.let { cam ->
                            val hasFlash = cam.cameraInfo.hasFlashUnit()
                            if (hasFlash) cam.cameraControl.enableTorch(torchOn) else torchOn = false
                        }
                    }
                },
                onToggleMirror = { doMirror = !doMirror },
                onToggleTorch = {
                    if (!useFront) {
                        torchOn = !torchOn
                        camera?.let { cam ->
                            val hasFlash = cam.cameraInfo.hasFlashUnit()
                            if (hasFlash) cam.cameraControl.enableTorch(torchOn) else torchOn = false
                        }
                    }
                },
                onToggleQr = { qrMode = !qrMode }
            )
        }

        if (missing.isNotEmpty()) {
            PermissionDialog(
                needCamera = !hasCamera,
                needAudio = !hasAudio,
                permanentlyDeniedCamera = permanentlyDenied(activity, Manifest.permission.CAMERA),
                permanentlyDeniedAudio = permanentlyDenied(activity, Manifest.permission.RECORD_AUDIO),
                onGrant = { permissionsLauncher.launch(missing.toTypedArray()) },
                onOpenSettings = {
                    val uri = Uri.fromParts("package", activity.packageName, null)
                    settingsLauncher.launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = uri })
                }
            )
        }
    }

    // Диалог результата QR — закрытие сразу возвращает к скану
    if (showQrDialog && lastQr != null) {
        AlertDialog(
            onDismissRequest = { showQrDialog = false },
            title = { Text("QR распознан") },
            text = { Text(lastQr ?: "") },
            confirmButton = {
                TextButton(onClick = {
                    val v = lastQr.orEmpty()
                    val uri = runCatching { Uri.parse(v) }.getOrNull()
                    if (uri != null && (v.startsWith("http://") || v.startsWith("https://"))) {
                        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                    } else {
                        Toast.makeText(context, "Не похоже на URL", Toast.LENGTH_SHORT).show()
                    }
                    showQrDialog = false  // вернуться к скану
                }) { Text("Открыть") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        val cm = context.getSystemService(ClipboardManager::class.java)
                        cm.setPrimaryClip(ClipData.newPlainText("QR", lastQr))
                        Toast.makeText(context, "Скопировано", Toast.LENGTH_SHORT).show()
                        showQrDialog = false  // вернуться к скану
                    }) { Text("Копировать") }
                    TextButton(onClick = { showQrDialog = false }) { Text("Закрыть") }
                }
            }
        )
    }
}

/* -------------------- Оверлеи -------------------- */

@Composable
private fun PhotoFxOverlay(
    flashAlpha: Float,
    frameAlpha: Float,
    frameScale: Float
) {
    if (flashAlpha > 0f) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.White.copy(alpha = flashAlpha))
        )
    }
    if (frameAlpha > 0f) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(18.dp)
                .graphicsLayer {
                    scaleX = frameScale
                    scaleY = frameScale
                    alpha = frameAlpha
                }
                .border(width = 3.dp, color = Color.White, shape = RoundedCornerShape(16.dp))
        )
    }
}

@Composable
private fun RecordingHud(elapsed: String) {
    val inf = rememberInfiniteTransition(label = "rec")
    val pulse = inf.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )
    val alpha = inf.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, end = 16.dp)
            .wrapContentSize(Alignment.TopEnd),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(14.dp)
                .graphicsLayer {
                    scaleX = pulse.value
                    scaleY = pulse.value
                    this.alpha = alpha.value
                }
                .background(Color.Red, CircleShape)
        )
        Spacer(Modifier.width(8.dp))
        Text(text = elapsed, color = Color.White, fontSize = 14.sp)
    }
}

@Composable
private fun QrOverlay() {
    // Простой рамочный видоискатель и бегущая линия
    val inf = rememberInfiniteTransition(label = "qr_line")
    val yShift = inf.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "qr_line_anim"
    )

    Box(
        Modifier
            .fillMaxSize()
            .padding(48.dp),
        contentAlignment = Alignment.Center
    ) {
        val frameShape = RoundedCornerShape(18.dp)
        Box(
            Modifier
                .fillMaxWidth(0.75f)
                .aspectRatio(1f)
                .border(3.dp, Color(0xAAFFFFFF), frameShape)
        )

        // бегущая горизонтальная линия
        Box(
            Modifier
                .fillMaxWidth(0.72f)
                .aspectRatio(1f)
                .graphicsLayer {
                    translationY = yShift.value * size.height
                }
                .height(2.dp)
                .background(Color(0xAA00FF8C))
        )
    }
}

/* -------------------- Диалоги / утилиты -------------------- */

@Composable
private fun PermissionDialog(
    needCamera: Boolean,
    needAudio: Boolean,
    permanentlyDeniedCamera: Boolean,
    permanentlyDeniedAudio: Boolean,
    onGrant: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val lines = buildList {
        if (needCamera) add("• Камера — для превью, фото и видео")
        if (needAudio) add("• Микрофон — для записи звука в видео")
    }.joinToString("\n")

    val showSettings = (needCamera && permanentlyDeniedCamera) || (needAudio && permanentlyDeniedAudio)

    AlertDialog(
        onDismissRequest = { /* не закрываем */ },
        title = { Text("Нужны разрешения") },
        text = { Text(lines) },
        confirmButton = {
            if (showSettings) {
                TextButton(onClick = onOpenSettings) { Text("Открыть настройки") }
            } else {
                TextButton(onClick = onGrant) { Text("Разрешить") }
            }
        },
        dismissButton = {
            if (showSettings) {
                TextButton(onClick = onGrant) { Text("Предоставить разрешения") }
            }
        }
    )
}

private fun isGranted(activity: Activity, perm: String): Boolean =
    ContextCompat.checkSelfPermission(activity, perm) == PackageManager.PERMISSION_GRANTED

private fun permanentlyDenied(activity: Activity, perm: String): Boolean =
    !ActivityCompat.shouldShowRequestPermissionRationale(activity, perm) &&
            ContextCompat.checkSelfPermission(activity, perm) != PackageManager.PERMISSION_GRANTED

/* -------------------- Панель управления -------------------- */

private val BarMinHeight = 96.dp
private val BtnHeight = 40.dp

@Composable
private fun ControlBar(
    useFront: Boolean,
    doMirror: Boolean,
    torchOn: Boolean,
    qrMode: Boolean,
    onToggleCamera: () -> Unit,
    onToggleMirror: () -> Unit,
    onToggleTorch: () -> Unit,
    onToggleQr: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0x66000000), RoundedCornerShape(16.dp))
            .heightIn(min = BarMinHeight)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SegmentLabel("Камера", Modifier.weight(1f))
            SegmentLabel(if (useFront) "Зеркало" else "Фонарик", Modifier.weight(1f))
            SegmentLabel("QR-сканер", Modifier.weight(1f))
        }
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FancyButton(if (useFront) "фронт" else "тыл", onToggleCamera, Modifier.weight(1f))
            if (useFront) {
                FancyButton(if (doMirror) "ВКЛ" else "ВЫКЛ", onToggleMirror, Modifier.weight(1f))
            } else {
                FancyButton(if (torchOn) "ВКЛ" else "ВЫКЛ", onToggleTorch, Modifier.weight(1f))
            }
            FancyButton(if (qrMode) "ON" else "OFF", onToggleQr, Modifier.weight(1f))
        }
        Spacer(Modifier.height(6.dp))
        Text("Зеркало • Raf</>Console Studio", color = Color.White.copy(alpha = 0.85f), maxLines = 1)
    }
}

@Composable
private fun SegmentLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, color = Color.White.copy(alpha = 0.9f), fontSize = 12.sp, textAlign = TextAlign.Center, modifier = modifier)
}

@Composable
private fun FancyButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(Color.Black, RoundedCornerShape(10.dp))
            .height(BtnHeight)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = Color.White, fontSize = 13.sp)
    }
}
