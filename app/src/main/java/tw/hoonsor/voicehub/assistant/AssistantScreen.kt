package tw.hoonsor.voicehub.assistant

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tw.hoonsor.voicehub.ui.Glyph
import tw.hoonsor.voicehub.ui.theme.C
import kotlin.math.PI
import kotlin.math.sin

@Composable
fun AssistantScreen(
    state: UiState,
    onMic: () -> Unit,
    onSend: (String) -> Unit,
    onToggleKeyboard: () -> Unit,
    onConfirm: (Boolean) -> Unit,
    onSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val active = state.mode != Mode.IDLE
    Box(Modifier.fillMaxSize()) {
        // 上方：輕觸空白處關閉，透出原本的畫面
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(0f to Color.Transparent, 0.45f to C.scrim.copy(alpha = 0.55f), 1f to C.scrim.copy(alpha = 0.96f)))
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        )
        AuroraGlow(mode = state.mode, level = state.level, modifier = Modifier.align(Alignment.BottomCenter))

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Conversation(state, Modifier.weight(1f, fill = false))
            Spacer(Modifier.height(10.dp))
            StatusLine(state)
            Spacer(Modifier.height(12.dp))
            if (state.confirming && !active) {
                ConfirmRow(onConfirm)
                Spacer(Modifier.height(12.dp))
            }
            if (state.keyboard) {
                TextInput(state.draft, onSend)
                Spacer(Modifier.height(12.dp))
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                RoundButton(Glyph.Gear, "設定", onSettings)
                RoundButton(Glyph.Keyboard, "打字", onToggleKeyboard, selected = state.keyboard)
                MicButton(state.mode, state.level, onMic)
                RoundButton(Glyph.Close, "關閉", onDismiss)
            }
        }
    }
}

/** 底部極光：四色漸層緩慢旋轉，聆聽時隨音量變亮變高，思考時呼吸 */
@Composable
private fun AuroraGlow(mode: Mode, level: Float, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "aurora")
    val angle by t.animateFloat(0f, 360f, infiniteRepeatable(tween(9_000, easing = LinearEasing)), label = "angle")
    val wave by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2_400, easing = LinearEasing)), label = "wave")
    val breath by t.animateFloat(0.55f, 1f, infiniteRepeatable(tween(1_100, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breath")
    val target = when (mode) {
        Mode.IDLE -> 0.32f
        Mode.LISTENING -> 0.55f + level * 0.9f
        Mode.TRANSCRIBING, Mode.THINKING -> 0.5f * breath + 0.25f
        Mode.EXECUTING -> 0.85f
        Mode.SPEAKING -> 0.55f + 0.2f * sin(wave * 2 * PI).toFloat()
    }
    val intensity by animateFloatAsState(target.coerceIn(0f, 1.4f), spring(stiffness = 300f), label = "intensity")
    val colors = listOf(C.auroraBlue, C.auroraViolet, C.auroraPink, C.auroraTeal, C.auroraBlue)

    Canvas(
        modifier
            .fillMaxWidth()
            .height(320.dp)
            .blur(48.dp),
    ) {
        val w = size.width
        val h = size.height
        // 三團光球沿底部左右擺動
        for (i in 0 until 3) {
            val phase = wave * 2 * PI + i * 2.1
            val cx = w * (0.2f + 0.3f * i) + (w * 0.08f * sin(phase)).toFloat()
            val r = w * (0.32f + 0.10f * intensity)
            val cy = h + r * (0.55f - 0.45f * intensity.coerceAtMost(1f))
            rotate(angle + i * 40f, Offset(cx, cy)) {
                drawCircle(
                    brush = Brush.sweepGradient(colors, Offset(cx, cy)),
                    radius = r,
                    center = Offset(cx, cy),
                    alpha = (0.45f + 0.4f * intensity).coerceAtMost(0.95f),
                )
            }
        }
    }
}

@Composable
private fun Conversation(state: UiState, modifier: Modifier = Modifier) {
    val list = rememberLazyListState()
    LaunchedEffect(state.messages.size, state.partial) {
        val n = state.messages.size + if (state.partial.isNotEmpty()) 1 else 0
        if (n > 0) list.animateScrollToItem(n - 1)
    }
    if (state.messages.isEmpty() && state.partial.isEmpty()) {
        Column(modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Text("有什麼需要幫忙的？", style = MaterialTheme.typography.titleLarge.copy(fontSize = 26.sp), color = C.text)
            Spacer(Modifier.height(6.dp))
            Text("例如：「明天早上七點叫我」「打開 LINE」「計時五分鐘」", style = MaterialTheme.typography.bodyMedium, color = C.muted)
        }
        return
    }
    LazyColumn(modifier.fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(state.messages, key = { it.id }) { m -> MessageRow(m) }
        if (state.partial.isNotEmpty()) {
            item(key = "partial") { Bubble(state.partial, user = true, faded = true) }
        }
    }
}

@Composable
private fun MessageRow(m: Msg) {
    when (m) {
        is Msg.User -> Bubble(m.text, user = true)
        is Msg.Bot -> Bubble(m.text, user = false, error = m.error)
        is Msg.Action -> ActionChip(m)
        is Msg.Note -> Text(m.text, color = C.dim, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 4.dp))
    }
}

@Composable
private fun Bubble(text: String, user: Boolean, error: Boolean = false, faded: Boolean = false) {
    Box(Modifier.fillMaxWidth(), contentAlignment = if (user) Alignment.CenterEnd else Alignment.CenterStart) {
        Text(
            text,
            color = when {
                error -> C.danger
                faded -> C.muted
                else -> C.text
            },
            style = if (user) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp, lineHeight = 26.sp),
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(18.dp, 18.dp, if (user) 4.dp else 18.dp, if (user) 18.dp else 4.dp))
                .background(if (user) C.userBubble.copy(alpha = 0.92f) else C.botBubble.copy(alpha = 0.92f))
                .border(1.dp, if (user) C.borderHi else C.border, RoundedCornerShape(18.dp, 18.dp, if (user) 4.dp else 18.dp, if (user) 18.dp else 4.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun ActionChip(m: Msg.Action) {
    val tint = if (m.ok) C.ok else C.warn
    Row(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(C.surface.copy(alpha = 0.9f))
            .border(1.dp, tint.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (m.ok) Glyph.Check else Glyph.Cross, null, tint = tint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.size(6.dp))
        Text(m.title, color = C.textSoft, style = MaterialTheme.typography.labelLarge)
        if (m.detail.isNotBlank()) {
            Spacer(Modifier.size(6.dp))
            Text(m.detail, color = C.muted, style = MaterialTheme.typography.labelMedium, maxLines = 2)
        }
    }
}

@Composable
private fun StatusLine(state: UiState) {
    val text = when (state.mode) {
        Mode.IDLE -> if (state.confirming) "等你確認" else "輕觸麥克風開始說話"
        Mode.LISTENING -> "聆聽中…講完會自動送出"
        Mode.TRANSCRIBING -> "轉成文字中…"
        Mode.THINKING -> "思考中…"
        Mode.EXECUTING -> "執行中…"
        Mode.SPEAKING -> "回覆中（點麥克風可插話）"
    }
    Text(text, color = C.muted, style = MaterialTheme.typography.labelLarge, modifier = Modifier.fillMaxWidth().padding(start = 4.dp))
}

@Composable
private fun ConfirmRow(onConfirm: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PillButton("取消", C.surfaceHi, C.text, Modifier.weight(1f)) { onConfirm(false) }
        PillButton("確定執行", C.danger.copy(alpha = 0.9f), C.onAccent, Modifier.weight(1f)) { onConfirm(true) }
    }
}

@Composable
private fun PillButton(text: String, bg: Color, fg: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(bg)
            .border(1.dp, C.borderHi, RoundedCornerShape(24.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = fg, style = MaterialTheme.typography.labelLarge) }
}

@Composable
private fun TextInput(draft: String, onSend: (String) -> Unit) {
    var text by remember(draft) { mutableStateOf(draft) }
    val send = {
        onSend(text)
        text = ""
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(C.field.copy(alpha = 0.95f))
            .border(1.dp, C.borderHi, RoundedCornerShape(24.dp))
            .padding(start = 18.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).padding(vertical = 14.dp)) {
            if (text.isEmpty()) Text("輸入指令…", color = C.dim, style = MaterialTheme.typography.bodyLarge)
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = TextStyle(color = C.text, fontSize = 16.sp),
                cursorBrush = SolidColor(C.accent),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(if (text.isBlank()) C.surfaceHi else C.accent).clickable(enabled = text.isNotBlank()) { send() },
            contentAlignment = Alignment.Center,
        ) { Icon(Glyph.Send, "送出", tint = if (text.isBlank()) C.dim else C.onAccent, modifier = Modifier.size(20.dp)) }
    }
}

@Composable
private fun RoundButton(icon: ImageVector, desc: String, onClick: () -> Unit, selected: Boolean = false) {
    Box(
        Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(if (selected) C.surfaceHi else C.surface.copy(alpha = 0.85f))
            .border(1.dp, if (selected) C.accent.copy(alpha = 0.7f) else C.border, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, desc, tint = if (selected) C.accent else C.textSoft, modifier = Modifier.size(22.dp)) }
}

@Composable
private fun MicButton(mode: Mode, level: Float, onClick: () -> Unit) {
    val listening = mode == Mode.LISTENING
    val busy = mode == Mode.TRANSCRIBING || mode == Mode.THINKING || mode == Mode.EXECUTING
    val scale by animateFloatAsState(if (listening) 1f + level * 0.25f else 1f, spring(stiffness = 500f), label = "mic")
    Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
        // 外圈光環
        Canvas(Modifier.size((76 * scale).dp)) {
            drawCircle(
                brush = Brush.sweepGradient(listOf(C.auroraBlue, C.auroraViolet, C.auroraPink, C.auroraTeal, C.auroraBlue)),
                alpha = if (listening || busy) 0.95f else 0.55f,
            )
        }
        Box(
            Modifier
                .size(68.dp)
                .clip(CircleShape)
                .background(if (listening) C.text else C.bgDeep)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (listening) Glyph.Stop else Glyph.Mic,
                if (listening) "講完了" else "開始說話",
                tint = if (listening) C.bgDeep else C.text,
                modifier = Modifier.size(30.dp),
            )
        }
    }
}
