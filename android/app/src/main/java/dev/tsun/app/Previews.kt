package dev.tsun.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

internal suspend fun decodeBitmap(bytes: ByteArray): Bitmap = withContext(Dispatchers.Default) {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("This image couldn't be decoded.")
    if (bounds.outWidth.toLong() * bounds.outHeight > 100_000_000L) {
        throw IOException("This image is too large to preview. You can still export it.")
    }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: throw IOException("This image couldn't be decoded.")
}

@Composable
internal fun RasterPreview(bitmap: Bitmap, label: String, modifier: Modifier = Modifier) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    var zoom by remember(bitmap) { mutableFloatStateOf(1f) }
    var offset by remember(bitmap) { mutableStateOf(Offset.Zero) }
    BoxWithConstraints(modifier.clip(Corner).background(inks.wash)) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val fit = minOf(width / bitmap.width, height / bitmap.height)
        val gestures = rememberTransformableState { factor, pan, _ ->
            zoom = (zoom * factor).coerceIn(1f, 5f)
            val x = ((fit * bitmap.width * zoom - width) / 2).coerceAtLeast(0f)
            val y = ((fit * bitmap.height * zoom - height) / 2).coerceAtLeast(0f)
            offset = Offset((offset.x + pan.x).coerceIn(-x, x), (offset.y + pan.y).coerceIn(-y, y))
        }
        Image(image, label, modifier = Modifier.fillMaxSize().transformable(gestures).graphicsLayer {
            scaleX = zoom; scaleY = zoom; translationX = offset.x; translationY = offset.y
        }, contentScale = ContentScale.Fit)
    }
}

private const val previewOrigin = "https://preview.tsun.invalid/"
private const val previewHead = """<!doctype html>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <meta http-equiv="Content-Security-Policy"
          content="default-src 'none'; style-src 'unsafe-inline'; img-src data:; font-src data:;
                   form-action 'none'; base-uri 'none'; frame-src 'none'">
"""

@Suppress("DEPRECATION") // Explicitly disable these settings on the oldest supported WebView, too.
@Composable
internal fun HtmlPreview(html: String, modifier: Modifier = Modifier) {
    key(html) {
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    settings.apply {
                        javaScriptEnabled = false
                        domStorageEnabled = false
                        allowFileAccess = false
                        allowContentAccess = false
                        allowFileAccessFromFileURLs = false
                        allowUniversalAccessFromFileURLs = false
                        blockNetworkLoads = true
                        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        cacheMode = WebSettings.LOAD_NO_CACHE
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
                    }
                    loadDataWithBaseURL(previewOrigin, previewHead + html, "text/html", null, previewOrigin)
                }
            },
            modifier = modifier.clip(Corner),
            onReset = null,
            onRelease = { view -> view.stopLoading(); view.destroy() },
        )
    }
}
