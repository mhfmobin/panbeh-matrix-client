package ir.panbeh.app.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ir.panbeh.app.ui.theme.Panbeh

/** Bottom sheet with an optional title (common.tsx Sheet on phones). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Sheet(onDismiss: () -> Unit, title: String? = null, fullHeight: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = fullHeight), containerColor = Panbeh.colors.panel) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 8.dp)) {
            if (title != null) Text(title, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            content()
        }
    }
}

/** One row of a menu or settings list. */
@Composable
fun MenuItem(text: String, icon: ImageVector? = null, danger: Boolean = false, detail: String? = null, onClick: () -> Unit) {
    val color = if (danger) Panbeh.colors.danger else Panbeh.colors.text
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(22.dp), tint = if (danger) color else Panbeh.colors.muted)
        Column(Modifier.weight(1f)) {
            Text(text, color = color)
            if (detail != null) Text(detail, color = Panbeh.colors.muted, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
        }
    }
}

/** A context menu shown as a bottom sheet (the web app's ContextMenu on phones). */
class MenuEntry(val text: String, val icon: ImageVector? = null, val danger: Boolean = false, val onClick: () -> Unit)

@Composable
fun MenuSheet(entries: List<MenuEntry>, onDismiss: () -> Unit, header: (@Composable () -> Unit)? = null) {
    Sheet(onDismiss) {
        header?.invoke()
        entries.forEach { e -> MenuItem(e.text, e.icon, e.danger) { onDismiss(); e.onClick() } }
    }
}

val Transparent = Color.Transparent
