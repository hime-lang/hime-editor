package gui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.window.*
import com.wakaztahir.codeeditor.model.CodeLang
import com.wakaztahir.codeeditor.prettify.PrettifyParser
import com.wakaztahir.codeeditor.prettify.lang.LangHime
import com.wakaztahir.codeeditor.theme.CodeThemeType
import com.wakaztahir.codeeditor.utils.parseCodeAsAnnotatedString
import gui.MTopMenuBar.MMenu
import gui.MTopMenuBar.MMenuBar
import org.hime.call
import org.hime.lang.Env
import org.hime.lang.IOConfig
import java.io.OutputStream
import java.io.PrintStream
import javax.swing.UIManager

val ThirdColor = Color(0xFFBB86FC)
val SecondaryColor = Color(0xFF03DAC5)
val MainColor = Color(0xFF1e88a8)

var run = false
class HimeEditorOutPutStream(private val func: (String) -> Unit): OutputStream() {
    private val builder = StringBuilder()
    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        builder.append(String(buffer, offset, length))
        func(builder.toString())
    }

    override fun write(b: Int) {
        write(byteArrayOf(b.toByte()), 0, 1)
    }
}

fun mainApp() = application {
    val windowState = rememberWindowState(size = DpSize(1024.dp, 1300.dp))
    val themeState by remember { mutableStateOf(CodeThemeType.Default) }
    val theme = remember(themeState) { themeState.theme }

    fun parse(code: String): AnnotatedString {
        return parseCodeAsAnnotatedString(
            parser = PrettifyParser(),
            theme = theme,
            lang = CodeLang({ LangHime(code) }, LangHime.fileExtensions),
            code = code
        )
    }

    var codeFieldValue by remember { mutableStateOf(TextFieldValue(parse(""))) }
    var resultFieldValue by remember { mutableStateOf(TextFieldValue()) }
    var resultLineTops by remember { mutableStateOf(emptyArray<Float>()) }
    Window(onCloseRequest = ::exitApplication, title = "??????????????????", state = windowState, undecorated = true, transparent = true) {
        MaterialTheme(
            colors = if (isSystemInDarkTheme()) darkColors(
                MainColor,
                SecondaryColor,
                ThirdColor
            ) else lightColors(MainColor, SecondaryColor, ThirdColor)
        ) {
            val scaffoldState = rememberScaffoldState()
            val sideBarVisible by remember { mutableStateOf(false) }
            Scaffold(
                scaffoldState = scaffoldState,
                modifier = Modifier.clip(RoundedCornerShape(5.dp)),
                topBar = {
                    MMenuBar("??????????????????", windowState) {
                        MMenu("??????") {
                            if (!run) {
                                Thread {
                                    run = true
                                    val outBuilder = HimeEditorOutPutStream(fun(value: String) {
                                        resultFieldValue = TextFieldValue(value)
                                    })
                                    call(
                                        Env(IOConfig(PrintStream(outBuilder), PrintStream(outBuilder), System.`in`)),
                                        codeFieldValue.text
                                    )
                                    outBuilder.close()
                                    run = false
                                }.start()
                            }
                        }
                    }
                },
                content = {
                    Row {
                        sideBarVisible && Unit == Box(
                            Modifier.fillMaxHeight().width(40.dp).background(MainColor)
                        ) { //?????????
                            Column(
                                Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.SpaceBetween,
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Column {
                                    Spacer(Modifier.height(10.dp))
                                    Icon(Icons.Default.Face, null)
                                    Icon(Icons.Default.Favorite, null)
                                    Icon(Icons.Default.AccountBox, null)
                                }
                                Column {
                                    Icon(Icons.Default.ExitToApp, null)
                                    Spacer(Modifier.height(20.dp))
                                }
                            }
                        }
                        var codeLineTops by remember { mutableStateOf(emptyArray<Float>()) }

                        Column {
                            Column(modifier = Modifier.fillMaxHeight(0.7f).verticalScroll(rememberScrollState()).border(
                                width = 2.dp,
                                color = Color.DarkGray
                            )) {
                                Row(modifier = Modifier.fillMaxWidth()) {
                                    if (codeLineTops.isNotEmpty()) {
                                        Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                                            Text(
                                                text = (1..codeLineTops.size).joinToString("\n") { it.toString() },
                                                color = MaterialTheme.colors.onBackground.copy(.3f),
                                                fontSize = 1.4.em
                                            )
                                        }
                                    }
                                    BasicTextField(
                                        value = codeFieldValue,
                                        onValueChange = {
                                            codeFieldValue = it.copy(annotatedString = parse(it.text))
                                        },
                                        onTextLayout = { result ->
                                            codeLineTops = Array(result.lineCount) { result.getLineTop(it) }
                                        },
                                        textStyle = TextStyle(fontSize = 1.4.em)
                                    )
                                }
                            }
                            Column(
                                modifier = Modifier.fillMaxHeight().verticalScroll(rememberScrollState()).border(
                                    width = 2.dp,
                                    color = Color.DarkGray,
                                )
                            ) {
                                Row(modifier = Modifier.fillMaxWidth()) {
                                    if (resultLineTops.isNotEmpty()) {
                                        Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                                            Text(
                                                text = (1..resultLineTops.size).joinToString("\n") { it.toString() },
                                                color = MaterialTheme.colors.onBackground.copy(.3f),
                                                fontSize = 1.4.em
                                            )
                                        }
                                    }
                                    BasicTextField(
                                        value = resultFieldValue,
                                        onValueChange = {},
                                        onTextLayout = { result ->
                                            resultLineTops = Array(result.lineCount) { result.getLineTop(it) }
                                        },
                                        readOnly = true,
                                        textStyle = TextStyle(fontSize = 1.4.em)
                                    )
                                }
                            }
                        }
                    }
                }
            )
        }
    }
}

fun main() {
    UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
    mainApp()
}
