package com.ai.assistance.operit.ui.common.markdown

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.ai.assistance.operit.util.AppLogger
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.ai.assistance.operit.R
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "MarkdownImageRenderer"

/** 判断Markdown图片语法是否完整 检查是否有完整的 ![alt](url) 语法 */
internal fun isCompleteImageMarkdown(content: String): Boolean {
    // 最基本的检查：包含![和]和(和)
    return content.contains("![") &&
            content.contains("](") &&
            content.indexOf(')') > content.indexOf('(')
}

/** 从Markdown图片语法中提取Alt文本 */
internal fun extractMarkdownImageAlt(imageContent: String): String {
    val startMarker = imageContent.indexOf("![")
    if (startMarker == -1) return ""

    val startBracket = startMarker + 2 // 跳过"!["
    val endBracket = imageContent.indexOf(']', startBracket)

    if (endBracket == -1) return ""
    return imageContent.substring(startBracket, endBracket).trim()
}

/** 从Markdown图片语法中提取URL */
internal fun extractMarkdownImageUrl(imageContent: String): String {
    val startParenthesis = imageContent.indexOf('(')
    if (startParenthesis == -1) return ""

    val endParenthesis = imageContent.indexOf(')', startParenthesis)
    if (endParenthesis == -1) return ""

    return imageContent.substring(startParenthesis + 1, endParenthesis).trim()
}

internal data class MarkdownPreviewImage(val url: String, val alt: String)

internal fun parseMarkdownPreviewImage(markdown: String): MarkdownPreviewImage? {
    if (!isCompleteImageMarkdown(markdown)) return null
    val url = extractMarkdownImageUrl(markdown)
    if (url.isBlank() || isLikelyVideoUrl(url) || isLikelyAudioUrl(url)) return null
    return MarkdownPreviewImage(url, extractMarkdownImageAlt(markdown))
}

/** Markdown图片渲染器组件 支持流式渲染和图片预览功能 */
@Composable
fun MarkdownImageRenderer(
        imageMarkdown: String,
        modifier: Modifier = Modifier,
        maxImageHeight: Int = 160, // 更小的默认最大高度
        enableDialogs: Boolean = true,
        onImageClick: (() -> Unit)? = null
) {
    // 只有完整的Markdown图片语法才会被渲染
    if (!isCompleteImageMarkdown(imageMarkdown)) {
        return
    }

    val imageAlt = extractMarkdownImageAlt(imageMarkdown)
    val imageUrl = extractMarkdownImageUrl(imageMarkdown)

    if (imageUrl.isEmpty()) {
        return
    }

    if (isLikelyVideoUrl(imageUrl)) {
        MarkdownVideoRenderer(
            videoMarkdown = imageMarkdown,
            modifier = modifier,
            maxVideoHeight = 220
        )
        return
    }

    if (isLikelyAudioUrl(imageUrl)) {
        MarkdownAudioRenderer(
            audioMarkdown = imageMarkdown,
            modifier = modifier
        )
        return
    }

    // 全屏预览状态
    var showFullScreen by remember { mutableStateOf(false) }

    // 无障碍朗读描述：只朗读块类型
    val accessibilityDesc = if (imageAlt.isNotEmpty()) {
        "${stringResource(R.string.image_block)}: $imageAlt"
    } else {
        stringResource(R.string.image_block)
    }

    Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
                .semantics { contentDescription = accessibilityDesc }
    ) {
        // 图片容器 - 移除容器的圆角，只保留基本的布局
        Box(
                modifier =
                        Modifier.wrapContentHeight()
                            .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp)) // 增加圆角大小为12dp
                                .clickable(enabled = enableDialogs) {
                                    if (onImageClick != null) onImageClick() else showFullScreen = true
                                }
        ) {
            SubcomposeAsyncImage(
                    model =
                            ImageRequest.Builder(LocalContext.current)
                                    .data(imageUrl)
                                    .crossfade(true)
                                    .build(),
                    contentDescription = null,
                    modifier =
                            Modifier.clip(RoundedCornerShape(12.dp))
                                .align(Alignment.Center)
                                    .heightIn(max = maxImageHeight.dp),
                    contentScale = ContentScale.Fit,
                    onSuccess = { /* 移除复杂的宽高比计算 */},
                    loading = {
                        Box(
                                modifier =
                                        Modifier.fillMaxWidth()
                                                .height(50.dp) // 更小的加载区域
                                                .background(
                                                        Color.LightGray.copy(alpha = 0.1f)
                                                ), // 更淡的背景
                                contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp), // 更小的加载指示器
                                    strokeWidth = 2.dp, // 更细的加载指示器
                                    color = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    error = {
                        Text(
                                text = stringResource(R.string.image_load_failed),
                                color = Color.Red.copy(alpha = 0.7f), // 更淡的文字颜色
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                        )
                    }
            )
        }

        // 显示图片说明，仅在需要时显示
        if (imageAlt.isNotEmpty()) {
            Text(
                    text = imageAlt,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier =
                            Modifier.fillMaxWidth() // 确保文本占据整个宽度
                                    .padding(horizontal = 2.dp, vertical = 1.dp),
                    textAlign = TextAlign.Center, // 居中对齐
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
            )
        }
    }

    // 全屏预览对话框
    if (enableDialogs && showFullScreen) {
        FullScreenImageDialog(
                images = listOf(MarkdownPreviewImage(imageUrl, imageAlt)),
                initialPage = 0,
                onDismiss = { showFullScreen = false }
        )
    }
}

/** 全屏图片预览对话框 */
@Composable
internal fun FullScreenImageDialog(
        images: List<MarkdownPreviewImage>,
        initialPage: Int,
        onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isSaving by remember { mutableStateOf(false) }
    val pagerState = rememberPagerState(initialPage = initialPage) { images.size }
    val page = pagerState.currentPage
    val image = images[page]
    var saveResult by remember { mutableStateOf<Pair<Int, Boolean>?>(null) }

    Dialog(
            onDismissRequest = onDismiss,
            properties =
                    DialogProperties(
                            dismissOnBackPress = true,
                            dismissOnClickOutside = true,
                            usePlatformDefaultWidth = false
                    )
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color.Black.copy(alpha = 0.9f)) {
            Box(modifier = Modifier.fillMaxSize()) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    userScrollEnabled = images.size > 1
                ) { index ->
                    ZoomablePreviewImage(
                        image = images[index],
                        isSettledPage = pagerState.settledPage == index
                    )
                }

                // 顶部工具栏
                Box(
                        modifier =
                                Modifier.fillMaxWidth()
                                        .background(Color.Black.copy(alpha = 0.6f))
                                        .padding(8.dp)
                                        .align(Alignment.TopCenter)
                ) {
                    IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopStart)) {
                        Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.common_close),
                                tint = Color.White
                        )
                    }

                    Text(
                            text = if (images.size > 1) {
                                "${page + 1} / ${images.size}  ${image.alt}".trimEnd()
                            } else {
                                image.alt
                            },
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.align(Alignment.Center).padding(horizontal = 48.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                    )

                    IconButton(
                            onClick = {
                                if (!isSaving) {
                                    saveResult = null
                                    isSaving = true
                                    scope.launch {
                                        try {
                                            saveResult = page to saveImageFromUrl(context, image.url)
                                        } finally {
                                            isSaving = false
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.align(Alignment.TopEnd)
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    color = Color.White
                            )
                        } else {
                            Icon(
                                    imageVector = Icons.Default.Download,
                                    contentDescription = stringResource(R.string.save_image),
                                    tint = Color.White
                            )
                        }
                    }
                }

                // 显示保存结果
                saveResult?.takeIf { it.first == page }?.let { (_, success) ->
                    Box(
                            modifier =
                                    Modifier.align(Alignment.BottomCenter)
                                            .fillMaxWidth()
                                            .background(Color.Black.copy(alpha = 0.6f))
                                            .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                    ) {
                        Text(
                                text = if (success) stringResource(R.string.image_saved) else stringResource(R.string.save_failed),
                                color = Color.White,
                                style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

            }
        }
    }
}

@Composable
private fun ZoomablePreviewImage(image: MarkdownPreviewImage, isSettledPage: Boolean) {
    val context = LocalContext.current
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }

    fun resetZoom() {
        scale = 1f
        offset = Offset.Zero
    }

    LaunchedEffect(isSettledPage, viewport) {
        resetZoom()
    }

    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 3f)
        val maxX = viewport.width * (scale - 1f) / 2f
        val maxY = viewport.height * (scale - 1f) / 2f
        offset = Offset(
            (offset.x + panChange.x).coerceIn(-maxX, maxX),
            (offset.y + panChange.y).coerceIn(-maxY, maxY)
        )
    }

    Box(
        modifier = Modifier.fillMaxSize()
            .clipToBounds()
            .onSizeChanged { viewport = it }
            .transformable(transformState, canPan = { scale > 1f })
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    if (scale > 1f) resetZoom() else scale = 2f
                })
            }
    ) {
        SubcomposeAsyncImage(
            model = remember(context, image.url) {
                ImageRequest.Builder(context).data(image.url).crossfade(true).build()
            },
            contentDescription = image.alt,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
            contentScale = ContentScale.Fit,
            loading = {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(48.dp))
                }
            },
            error = {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.common_load_failed),
                        color = Color.Red,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        )

        if (scale > 1f) {
            IconButton(
                onClick = ::resetZoom,
                modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = stringResource(R.string.common_reset_zoom),
                    tint = Color.White
                )
            }
        }
    }
}

/** 从URL保存图片到设备相册 */
private suspend fun saveImageFromUrl(context: Context, imageUrl: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                // 创建图片文件名
                val fileName =
                        "markdown_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.jpg"

                // 根据Android版本选择不同的保存策略
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val contentValues =
                            ContentValues().apply {
                                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                                put(
                                        MediaStore.MediaColumns.RELATIVE_PATH,
                                        "${Environment.DIRECTORY_PICTURES}/Markdown"
                                )
                            }

                    val uri =
                            context.contentResolver.insert(
                                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                    contentValues
                            )
                    uri?.let { imageUri ->
                        context.contentResolver.openOutputStream(imageUri)?.use { outputStream ->
                            // 下载图片
                            val connection = java.net.URL(imageUrl).openConnection()
                            connection.connect()
                            connection.getInputStream().use { input -> input.copyTo(outputStream) }
                            return@withContext true
                        }
                    }
                } else {
                    // 较老版本的Android使用传统文件访问方式
                    val imagesDir =
                            Environment.getExternalStoragePublicDirectory(
                                    Environment.DIRECTORY_PICTURES
                            )
                    val markdownDir = File(imagesDir, "Markdown").apply { if (!exists()) mkdirs() }

                    val imageFile = File(markdownDir, fileName)
                    FileOutputStream(imageFile).use { outputStream ->
                        // 下载图片
                        val connection = java.net.URL(imageUrl).openConnection()
                        connection.connect()
                        connection.getInputStream().use { input -> input.copyTo(outputStream) }
                        return@withContext true
                    }
                }

                return@withContext false
            } catch (e: Exception) {
                AppLogger.e(TAG, "保存图片失败", e)
                return@withContext false
            }
        }
