package com.webdav.album

import android.app.KeyguardManager
import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import dev.chengeeker.lucent.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LockSession : ViewModel() { var initialized = false; var locked by mutableStateOf(false); var cacheChecked by mutableStateOf(false) }

class MainActivity : FragmentActivity() {
    lateinit var lock: AppLock
        private set
    private lateinit var session: LockSession
    private var credentialCallback: ((Boolean) -> Unit)? = null
    private val credential = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val success = result.resultCode == Activity.RESULT_OK
        if (success) session.locked = false
        credentialCallback?.invoke(success)
        credentialCallback = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lock = AppLock(this)
        session = ViewModelProvider(this)[LockSession::class.java]
        if (!session.initialized) { session.locked = lock.enabled; session.initialized = true }
        val prefs = AlbumPreferences(this)
        enableEdgeToEdge()
        setContent {
            val revision = lock.revision
            val configured = remember(revision) { lock.enabled }
            val systemMotion = android.provider.Settings.Global.getFloat(contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
            LucentTheme(prefs.style.copy(motion = prefs.style.motion && systemMotion)) {
                val dark = MaterialTheme.colorScheme.background.luminance() < .5f
                SideEffect {
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        if (isAppearanceLightStatusBars == dark) isAppearanceLightStatusBars = !dark
                        if (isAppearanceLightNavigationBars == dark) isAppearanceLightNavigationBars = !dark
                    }
                    val protect = configured || prefs.privacy
                    val protected = window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
                    if (protect != protected) {
                        if (protect) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    }
                    val attributes = window.attributes
                    if (attributes.preferredRefreshRate != prefs.refresh.toFloat()) {
                        attributes.preferredRefreshRate = prefs.refresh.toFloat(); window.attributes = attributes
                    }
                }
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().then(if (session.locked) Modifier.alpha(0f).clearAndSetSemantics {} else Modifier)) {
                        AlbumScreen(application as AlbumApplication, prefs, lock, this@MainActivity, session)
                    }
                    if (session.locked) {
                        BackHandler { moveTaskToBack(true) }
                        LockScreen(lock, this@MainActivity) { session.locked = false }
                    }
                }
            }
        }
    }

    override fun onStop() {
        if (::lock.isInitialized && lock.enabled && !isChangingConfigurations) session.locked = true
        super.onStop()
    }

    fun biometricAvailable(): Boolean = BiometricManager.from(this).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS
    fun deviceCredentialAvailable(): Boolean = (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceSecure

    fun authenticateBiometric(onResult: (Boolean, String?) -> Unit) {
        if (!biometricAvailable()) { onResult(false, "当前设备没有可用的生物识别，请检查系统设置。" ); return }
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { session.locked = false; onResult(true, null) }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { onResult(false, errString.toString()) }
        })
        runCatching {
            prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("验证身份")
                .setSubtitle("解锁 WebAlbum").setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
                .setNegativeButtonText("取消").build())
        }.onFailure { onResult(false, "无法启动系统生物识别，请重试。") }
    }

    fun authenticateDevice(onResult: (Boolean) -> Unit) {
        val manager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val intent = manager.createConfirmDeviceCredentialIntent("验证设备锁屏", "用于解锁 WebAlbum")
        if (intent == null) { onResult(false); return }
        credentialCallback = onResult
        credential.launch(intent)
    }
}

@Composable
private fun AuthenticationForm(lock: AppLock, activity: MainActivity, onVerified: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var remaining by remember { mutableLongStateOf(lock.remainingSeconds()) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(remaining) {
        if (remaining > 0) { delay(1000); remaining = lock.remainingSeconds() }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (lock.pinEnabled) {
            OutlinedTextField(pin, { pin = it.filter { digit -> digit in '0'..'9' }.take(12); error = null }, Modifier.fillMaxWidth(),
                label = { Text("应用 PIN 码") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), isError = error != null)
            if (remaining > 0) Text("尝试次数过多，请在 ${remaining} 秒后重试", color = MaterialTheme.colorScheme.error)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            LucentAction("解锁", enabled = !busy && remaining == 0L && pin.isNotEmpty(), modifier = Modifier.fillMaxWidth(), onClick = {
                busy = true
                scope.launch {
                    val result = runCatching { withContext(Dispatchers.Default) { lock.verifyPin(pin) } }
                    busy = false; pin = ""; remaining = lock.remainingSeconds()
                    result.onSuccess { if (it) onVerified() else error = "PIN 码不正确" }
                        .onFailure { error = "无法验证 PIN 码，请重试" }
                }
            })
        }
        if (lock.biometricEnabled) {
            LucentAction("使用生物识别", primary = !lock.pinEnabled, modifier = Modifier.fillMaxWidth(), onClick = {
                activity.authenticateBiometric { success, reason -> if (success) onVerified() else error = reason }
            })
            if (activity.deviceCredentialAvailable()) LucentAction("使用设备锁屏验证", primary = false, modifier = Modifier.fillMaxWidth(), onClick = {
                activity.authenticateDevice { if (it) onVerified() else error = "未完成设备锁屏验证" }
            })
            if (!lock.pinEnabled) error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
fun LockScreen(lock: AppLock, activity: MainActivity, onVerified: () -> Unit) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
        .clickable(interactionSource = null, indication = null) { }.statusBarsPadding().navigationBarsPadding().imePadding(), contentAlignment = Alignment.Center) {
        Column(Modifier.fillMaxWidth().widthIn(max = 480.dp).verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Icon(Icons.Outlined.Lock, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
            Text("解锁 WebAlbum", style = MaterialTheme.typography.headlineSmall)
            LucentCard { Column(Modifier.padding(20.dp)) { AuthenticationForm(lock, activity, onVerified) } }
        }
    }
}

@Composable
fun AuthenticationDialog(lock: AppLock, activity: MainActivity, onDismiss: () -> Unit, onVerified: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("验证身份") },
        text = { AuthenticationForm(lock, activity, onVerified) },
        confirmButton = {}, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
fun PinSetupDialog(lock: AppLock, onDismiss: () -> Unit, onSaved: () -> Unit) {
    var pin by remember { mutableStateOf("") }; var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }; var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("设置应用 PIN 码") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("设置 4–12 位数字。请妥善记住 PIN 码；忘记后可能需要清除应用数据。")
            OutlinedTextField(pin, { pin = it.filter { digit -> digit in '0'..'9' }.take(12) }, label = { Text("新 PIN 码") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), visualTransformation = PasswordVisualTransformation())
            OutlinedTextField(confirm, { confirm = it.filter { digit -> digit in '0'..'9' }.take(12) }, label = { Text("再次输入") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), visualTransformation = PasswordVisualTransformation())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(enabled = !busy, onClick = {
        when { !validPin(pin) -> error = "请输入 4–12 位数字"; pin != confirm -> error = "两次输入的 PIN 码不一致"; else -> {
            busy = true; scope.launch {
                val result = runCatching { withContext(Dispatchers.Default) { lock.setPin(pin) } }
                busy = false
                result.onSuccess { pin = ""; confirm = ""; onSaved() }.onFailure { error = "保存失败，请重试" }
            }
        } }
    }) { Text("保存") } }, dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } })
}
