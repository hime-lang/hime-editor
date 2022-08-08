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
import org.hime.call
import gui.MTopMenuBar.MMenu
import gui.MTopMenuBar.MMenuBar
import org.hime.lang.Env
import org.hime.lang.IOConfig
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import javax.swing.UIManager

val ThirdColor = Color(0xFFBB86FC)
val SecondaryColor = Color(0xFF03DAC5)
val MainColor = Color(0xFF1e88a8)

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
    Window(onCloseRequest = ::exitApplication, title = "姬语言编辑器", state = windowState, undecorated = true, transparent = true) {
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
                    MMenuBar("姬语言编辑器", windowState) {
                        MMenu("运行") {
                            val outBuilder = ByteArrayOutputStream()
                            val errorBuilder = ByteArrayOutputStream()
                            call(Env(IOConfig(PrintStream(outBuilder), PrintStream(errorBuilder), System.`in`)), codeFieldValue.text)
                            resultFieldValue = TextFieldValue(outBuilder.toString())
                        }
                    }
                },
                content = {
                    Row {
                        sideBarVisible && Unit == Box(
                            Modifier.fillMaxHeight().width(40.dp).background(MainColor)
                        ) { //侧边栏
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
