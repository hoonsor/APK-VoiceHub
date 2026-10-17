package tw.hoonsor.voicehub.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tw.hoonsor.voicehub.ui.theme.C

@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(C.surface)
            .border(1.dp, C.border, RoundedCornerShape(16.dp))
            .padding(16.dp),
        content = content,
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = C.muted, modifier = modifier.padding(start = 4.dp, top = 8.dp, bottom = 8.dp))
}

@Composable
fun OutlineButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val bg = when {
        !enabled -> C.surface
        primary -> C.accent
        else -> C.surfaceHi
    }
    val fg = when {
        !enabled -> C.dim
        primary -> C.onAccent
        else -> C.text
    }
    Row(
        modifier
            .height(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .border(1.dp, if (primary) C.accent else C.borderHi, RoundedCornerShape(20.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(6.dp))
        }
        Text(text, color = fg, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    secret: Boolean = false,
    mono: Boolean = false,
    minLines: Int = 1,
    placeholder: String = "",
) {
    Column(modifier.fillMaxWidth()) {
        Text(label, color = C.muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(bottom = 6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(C.field)
                .border(1.dp, C.borderHi, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 11.dp),
        ) {
            if (value.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, color = C.dim, fontSize = 15.sp)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                textStyle = TextStyle(color = C.text, fontSize = if (mono) 13.sp else 15.sp, fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default),
                cursorBrush = SolidColor(C.accent),
                singleLine = minLines == 1,
                minLines = minLines,
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
fun ToggleRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = C.text, style = MaterialTheme.typography.bodyLarge)
            if (desc.isNotBlank()) Text(desc, color = C.muted, style = MaterialTheme.typography.bodyMedium)
        }
        Switch(
            checked, onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = C.onAccent, checkedTrackColor = C.accent,
                uncheckedThumbColor = C.muted, uncheckedTrackColor = C.field, uncheckedBorderColor = C.borderHi,
            ),
        )
    }
}

/** 健康檢查的狀態點 */
@Composable
fun StatusDot(ok: Boolean?, modifier: Modifier = Modifier) {
    val color = when (ok) {
        true -> C.ok
        false -> C.danger
        null -> C.warn
    }
    Box(modifier.size(10.dp).clip(CircleShape).background(color))
}

@Composable
fun ResultText(text: String, ok: Boolean?) {
    if (text.isBlank()) return
    Spacer(Modifier.height(8.dp))
    Text(
        text,
        color = when (ok) {
            true -> C.ok
            false -> C.danger
            null -> C.muted
        },
        style = MaterialTheme.typography.bodyMedium,
    )
}

val Mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = C.textSoft, lineHeight = 17.sp)
