package tw.hoonsor.voicehub.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** 手繪線條圖示（24×24，1.8 線寬），不用 Material 圖示庫 */
object Glyph {
    private fun line(name: String, vararg paths: String, fill: String? = null): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            fill?.let {
                addPath(PathParser().parsePathString(it).toNodes(), fill = SolidColor(Color.White))
            }
            paths.forEach { d ->
                addPath(
                    PathParser().parsePathString(d).toNodes(),
                    stroke = SolidColor(Color.White),
                    strokeLineWidth = 1.8f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()

    val Mic = line(
        "mic",
        "M12,3.5a3,3 0,0 1,3 3v5a3,3 0,0 1,-6 0v-5a3,3 0,0 1,3 -3z",
        "M6.5,11.2a5.5,5.5 0,0 0,11 0M12,16.8v3.4M9,20.2h6",
    )
    val Stop = line("stop", fill = "M8,7h8a1,1 0,0 1,1 1v8a1,1 0,0 1,-1 1h-8a1,1 0,0 1,-1 -1v-8a1,1 0,0 1,1 -1z")
    val Keyboard = line(
        "keyboard",
        "M4,6.5h16a1.5,1.5 0,0 1,1.5 1.5v8a1.5,1.5 0,0 1,-1.5 1.5h-16a1.5,1.5 0,0 1,-1.5 -1.5v-8a1.5,1.5 0,0 1,1.5 -1.5z",
        "M6.5,10h0.01M10,10h0.01M13.5,10h0.01M17,10h0.01M8,14h8",
    )
    val Send = line("send", "M4.5,12l15,-7l-4.5,15l-3,-6z", "M12,14l7.5,-9")
    val Close = line("close", "M6.5,6.5l11,11M17.5,6.5l-11,11")
    val Gear = line(
        "gear",
        "M12,9a3,3 0,1 1,0 6a3,3 0,1 1,0 -6z",
        "M12,2.8v2.4M12,18.8v2.4M2.8,12h2.4M18.8,12h2.4M5.5,5.5l1.7,1.7M16.8,16.8l1.7,1.7M5.5,18.5l1.7,-1.7M16.8,7.2l1.7,-1.7",
    )
    val Check = line("check", "M5,12.5l4.5,4.5l9.5,-10")
    val Cross = line("cross", "M7,7l10,10M17,7l-10,10")
    val Bolt = line("bolt", "M13,3l-7,10h5.5l-1,8l7,-10h-5.5z")
    val Refresh = line("refresh", "M19.5,8.5a8,8 0,1 0,0.5 6", "M20,3.5v5h-5")
    val Play = line("play", "M8,5.5l11,6.5l-11,6.5z")
    val Heart = line("heart", "M12,19.5s-7.5,-4.4 -7.5,-10a4.2,4.2 0,0 1,7.5 -2.6a4.2,4.2 0,0 1,7.5 2.6c0,5.6 -7.5,10 -7.5,10z")
    val Grid = line("grid", "M4.5,4.5h6v6h-6zM13.5,4.5h6v6h-6zM4.5,13.5h6v6h-6zM13.5,13.5h6v6h-6z")
    val Flask = line("flask", "M9.5,3.5h5M10.5,3.5v6l-5.5,9a1.5,1.5 0,0 0,1.3 2.2h11.4a1.5,1.5 0,0 0,1.3 -2.2l-5.5,-9v-6", "M7.5,15h9")
    val Undo = line("undo", "M9,7.5l-4.5,4.5l4.5,4.5", "M4.5,12h10a5,5 0,0 1,0 10h-2")
}
