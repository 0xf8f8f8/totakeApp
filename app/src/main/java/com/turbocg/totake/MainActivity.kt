package com.turbocg.totake

// import android.webkit.CookieManager
import android.Manifest
import android.R
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import androidx.core.graphics.toColorInt
import org.mozilla.geckoview.GeckoResult
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import android.graphics.Color
import androidx.annotation.RequiresPermission
import androidx.core.view.updatePadding
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.WebNotification
import org.mozilla.geckoview.WebNotificationDelegate
import java.io.File
import java.io.FileOutputStream



@Suppress("COMPOSE_APPLIER_CALL_MISMATCH")
class MainActivity : ComponentActivity() {
    private lateinit var runtime: GeckoRuntime
    private var canGoBack = false
    private lateinit var session: GeckoSession

    private var pendingFilePrompt: GeckoSession.PromptDelegate.FilePrompt? = null
    private var pendingFileResult: GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? = null
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val prompt = pendingFilePrompt
        val geckoResult = pendingFileResult
        if (result.resultCode == Activity.RESULT_OK && result.data?.data != null) {
            val contentUri = result.data!!.data!!
            val fileUri = copyContentUriToFile(contentUri)

            if (fileUri != null) {
                geckoResult?.complete(prompt?.confirm(this, fileUri))
            } else {
                geckoResult?.complete(prompt?.dismiss())
            }
        } else {
            geckoResult?.complete(prompt?.dismiss())
        }
        pendingFilePrompt = null
        pendingFileResult = null
    }
    private fun copyContentUriToFile(contentUri: Uri): Uri? {
        return try {
            val inputStream = contentResolver.openInputStream(contentUri) ?: return null
            val fileName = queryFileName(contentUri) ?: "temp_${System.currentTimeMillis()}"
            val tempFile = File(cacheDir, fileName)
            FileOutputStream(tempFile).use { output ->
                inputStream.copyTo(output)
            }
            inputStream.close()
            Uri.fromFile(tempFile)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    private fun queryFileName(uri: Uri): String? {
        var name: String? = null
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index != -1) {
                    name = cursor.getString(index)
                }
            }
        }
        return name
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)   // ✅ 必须先调 super

        // Android 13+ 动态申请通知权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(
                Color.TRANSPARENT, Color.TRANSPARENT
            ),
            navigationBarStyle = SystemBarStyle.light(
                Color.TRANSPARENT, Color.TRANSPARENT
            )
        )

        val container = FrameLayout(this)
        val navBarColorBg = View(this).apply {
            setBackgroundColor("#FFFFFF".toColorInt())
        }
        container.addView(navBarColorBg, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0
        ).apply {
            gravity = Gravity.BOTTOM
        })

        runtime = GeckoRuntime.create(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        // ✅ 先创建 session（赋值给类的 lateinit 字段，不要 val 重复声明）
        session = GeckoSession()

        // ✅ 这时 session 已存在，可以设置 permissionDelegate
        session.permissionDelegate = object : GeckoSession.PermissionDelegate {
            override fun onContentPermissionRequest(
                session: GeckoSession,
                perm: GeckoSession.PermissionDelegate.ContentPermission
            ): GeckoResult<Int>? {
                return if (perm.permission == GeckoSession.PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION) {
                    GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW)
                } else {
                    GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)
                }
            }
        }

        // 通知 delegate
        runtime.webNotificationDelegate = object : WebNotificationDelegate {
            @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
            override fun onShowNotification(notification: WebNotification) {
                val builder = NotificationCompat.Builder(this@MainActivity, "web_channel")
                    .setContentTitle(notification.title ?: "Notificación")
                    .setContentText(notification.text ?: "")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setAutoCancel(true)

                NotificationManagerCompat.from(this@MainActivity)
                    .notify(notification.tag.hashCode(), builder.build())
            }

            override fun onCloseNotification(notification: WebNotification) {
                runOnUiThread {
                    // vacío o dismiss() si existe
                }
            }
        }

        val geckoView = GeckoView(this)
        session.open(runtime)
        geckoView.setSession(session)
        container.addView(geckoView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        if (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true) {
            session.loadUri("https://totake-fe.onrender.com/mainpage")
        } else {
            session.loadUri("about:blank")
        }

        session.promptDelegate = object : GeckoSession.PromptDelegate {
            override fun onFilePrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.FilePrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = if (prompt.mimeTypes?.isNotEmpty() == true) {
                        prompt.mimeTypes!![0]
                    } else {
                        "*/*"
                    }
                }
                pendingFilePrompt = prompt
                pendingFileResult = result
                filePickerLauncher.launch(intent)
                return result
            }
        }

        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLoadRequest(
                session: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest
            ): GeckoResult<AllowOrDeny?>? {
                val uri = Uri.parse(request.uri)

                if (uri.scheme == "intent") {
                    try {
                        val intent = Intent.parseUri(request.uri, Intent.URI_INTENT_SCHEME)
                        startActivity(intent)
                    } catch (e: Exception) {
                        val fallback = try {
                            Intent.parseUri(request.uri, Intent.URI_INTENT_SCHEME)
                                .getStringExtra("browser_fallback_url")
                        } catch (_: Exception) { null }
                        if (fallback != null) session.loadUri(fallback)
                    }
                    return GeckoResult.fromValue(AllowOrDeny.DENY)
                }

                if (uri.scheme == "paypal" || uri.scheme == "mercadopago") {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, uri))
                    } catch (_: Exception) { }
                    return GeckoResult.fromValue(AllowOrDeny.DENY)
                }

                return GeckoResult.fromValue(AllowOrDeny.ALLOW)
            }

            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                this@MainActivity.canGoBack = canGoBack
            }
        }

        ViewCompat.setOnApplyWindowInsetsListener(container) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(
                top = bars.top,
                bottom = bars.bottom
            )
            navBarColorBg.layoutParams.height = bars.bottom
            navBarColorBg.requestLayout()
            insets
        }

        setContentView(container)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (canGoBack) {
                    session.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }
    override fun onDestroy() {
        super.onDestroy()
    }
}
