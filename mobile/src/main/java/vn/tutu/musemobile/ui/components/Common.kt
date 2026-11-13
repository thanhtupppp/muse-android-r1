package vn.tutu.musemobile.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vn.tutu.musemobile.ui.theme.StatusColors

/** Thẻ nội dung chuẩn của app: ElevatedCard bo 16dp, padding 16dp. */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    containerColor: Color? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
        colors = if (containerColor != null) {
            CardDefaults.elevatedCardColors(containerColor = containerColor)
        } else {
            CardDefaults.elevatedCardColors()
        }
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            content = content
        )
    }
}

/** Tiêu đề thẻ: icon trong ô bo tròn + tiêu đề + mô tả phụ. */
@Composable
fun CardHeader(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = MaterialTheme.shapes.medium
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

enum class ChipKind { SUCCESS, WARNING, ERROR, NEUTRAL, INFO }

/** Nhãn trạng thái dạng viên thuốc, có chấm màu đi kèm. */
@Composable
fun StatusChip(text: String, kind: ChipKind, modifier: Modifier = Modifier) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val (bg, fg) = when (kind) {
        ChipKind.SUCCESS -> MaterialTheme.colorScheme.secondaryContainer to
            if (dark) StatusColors.SuccessDark else StatusColors.Success
        ChipKind.WARNING -> MaterialTheme.colorScheme.tertiaryContainer to
            MaterialTheme.colorScheme.onTertiaryContainer
        ChipKind.ERROR -> MaterialTheme.colorScheme.errorContainer to
            MaterialTheme.colorScheme.onErrorContainer
        ChipKind.INFO -> MaterialTheme.colorScheme.primaryContainer to
            MaterialTheme.colorScheme.onPrimaryContainer
        ChipKind.NEUTRAL -> MaterialTheme.colorScheme.surfaceVariant to
            MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        modifier = modifier,
        color = bg,
        shape = MaterialTheme.shapes.extraLarge
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(fg, CircleShape)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = text,
                color = fg,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

private fun Color.luminance(): Float =
    0.299f * red + 0.587f * green + 0.114f * blue

/** Nút chính có trạng thái đang xử lý (spinner thay nội dung). */
@Composable
fun LoadingButton(
    text: String,
    loadingText: String,
    loading: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null
) {
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(loadingText)
        } else {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(text)
        }
    }
}

/** Trường nhập có nút dán từ clipboard ở cuối. */
@Composable
fun PasteTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    enabled: Boolean = true
) {
    val clipboardManager = LocalClipboardManager.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = if (placeholder != null) {
            { Text(placeholder) }
        } else {
            null
        },
        singleLine = true,
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        trailingIcon = {
            IconButton(
                onClick = {
                    val clip = clipboardManager.getText()?.text?.trim().orEmpty()
                    if (clip.isNotEmpty()) onValueChange(clip)
                },
                enabled = enabled
            ) {
                Icon(
                    imageVector = Icons.Filled.ContentPaste,
                    contentDescription = "Dán từ clipboard"
                )
            }
        },
        modifier = modifier.fillMaxWidth()
    )
}

/** Thanh tiến trình mượt (animate theo giá trị), null = không xác định. */
@Composable
fun AnimatedProgress(progress: Float?, modifier: Modifier = Modifier) {
    if (progress == null) {
        LinearProgressIndicator(modifier = modifier.fillMaxWidth())
    } else {
        val animated by animateFloatAsState(
            targetValue = progress.coerceIn(0f, 1f),
            label = "progress"
        )
        LinearProgressIndicator(
            progress = { animated },
            modifier = modifier.fillMaxWidth()
        )
    }
}

/** Huy hiệu số thứ tự bước trong luồng cài đặt. */
@Composable
fun StepBadge(number: Int, completed: Boolean) {
    val bg = if (completed) {
        MaterialTheme.colorScheme.secondary
    } else {
        MaterialTheme.colorScheme.primary
    }
    val fg = if (completed) {
        MaterialTheme.colorScheme.onSecondary
    } else {
        MaterialTheme.colorScheme.onPrimary
    }
    Box(
        modifier = Modifier
            .size(28.dp)
            .background(bg, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (completed) {
            Text("✓", color = fg, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        } else {
            Text("$number", color = fg, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
    }
}

/** Banner thông tin/hướng dẫn nhẹ. */
@Composable
fun InfoBanner(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
        shape = MaterialTheme.shapes.medium
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(12.dp)
        )
    }
}

/**
 * Khung hiển thị nhật ký kiểu terminal (nền tối, chữ monospace, tự cuộn
 * xuống dòng mới nhất, tô màu theo nội dung dòng).
 */
@Composable
fun LogConsole(
    lines: List<String>,
    modifier: Modifier = Modifier,
    maxHeight: androidx.compose.ui.unit.Dp = 220.dp,
    lineColor: (String) -> Color = { line -> defaultConsoleLineColor(line) }
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp, max = maxHeight),
        color = StatusColors.ConsoleBackground,
        shape = MaterialTheme.shapes.medium
    ) {
        val listState = rememberLazyListState()
        LaunchedEffect(lines.size) {
            if (lines.isNotEmpty()) {
                listState.animateScrollToItem(lines.size - 1)
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            items(lines) { line ->
                Text(
                    text = line,
                    color = lineColor(line),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 15.sp
                )
            }
        }
    }
}

fun defaultConsoleLineColor(line: String): Color = when {
    line.contains("❌") || line.contains("Lỗi", ignoreCase = true) ||
        line.contains("thất bại", ignoreCase = true) ||
        line.contains("ERROR", ignoreCase = true) ||
        line.contains("REFUSED", ignoreCase = true) ||
        line.contains("NOT_LISTENING", ignoreCase = true) -> StatusColors.ConsoleError

    line.contains("✅") || line.contains("thành công", ignoreCase = true) ||
        line.contains("CONNECTED", ignoreCase = true) ||
        line.contains("ready", ignoreCase = true) -> StatusColors.ConsoleSuccess

    line.contains("Bắt đầu") || line.contains("started", ignoreCase = true) ->
        StatusColors.ConsoleAccent

    else -> StatusColors.ConsoleText
}
