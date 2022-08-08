package gui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.window.*
import kotlin.system.exitProcess

object MTopMenuBar {
    @Composable
    fun WindowScope.MMenuBar(
        title: String,
        windowState: WindowState,
        onIconClicked: () -> Unit = {},
        modifier: Modifier = Modifier.height(50.dp),
        menus: @Composable () -> Unit = {}
    ) = TopAppBar(modifier = modifier) {
        WindowDraggableArea {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(horizontalArrangement = Arrangement.Start, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = {
                            onIconClicked()
                        },
                        content = { Icon(Icons.Default.Menu, null) }
                    )
                    menus()
                }

                Text(title, maxLines = 1, fontSize = 1.4.em)

                Row(horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { windowState.isMinimized = true },
                        content = { Icon(Icons.Default.ArrowDropDown, null) }
                    )
                    IconButton(
                        onClick = {
                            windowState.placement =
                                if (windowState.placement == WindowPlacement.Maximized) WindowPlacement.Floating else WindowPlacement.Maximized
                        },
                        content = { Icon(Icons.Default.Add, null) }
                    )
                    IconButton(
                        onClick = { exitProcess(0) },
                        content = { Icon(Icons.Default.Close, null) }
                    )
                }
            }
        }
    }

    interface MMenuScope : ColumnScope {
        // 关闭Menu
        fun collapseMenu()
    }

    @Composable
    fun MMenu(text: String, dropdownMenuItems: @Composable MMenuScope.() -> Unit) {
        var menuExpanded by remember { mutableStateOf(false) }
        Column {
            Text(
                text,
                modifier = Modifier.padding(6.dp, 0.dp).clickable { menuExpanded = true; },
                maxLines = 1,
                fontSize = 1.4.em
            )
            DropdownMenu(
                expanded = menuExpanded, onDismissRequest = { menuExpanded = false }, focusable = true,
            ) {
                object : MMenuScope, ColumnScope by this {
                    override fun collapseMenu() {
                        menuExpanded = false
                    }
                }.dropdownMenuItems()
            }
        }
    }
}
