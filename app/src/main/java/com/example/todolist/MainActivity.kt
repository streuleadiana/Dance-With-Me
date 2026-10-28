package com.example.todolist

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import com.example.todolist.ui.theme.ToDoListTheme
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class TodoItem(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val description: String = "",
    val isCompleted: Boolean = false
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ToDoListTheme {
                TodoApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodoApp() {
    val context = LocalContext.current
    val todoList = remember {
        mutableStateListOf<TodoItem>().apply {
            addAll(loadTasks(context))
        }
    }

    var quickTaskTitle by remember { mutableStateOf("") }
    var showAddDialog by remember { mutableStateOf(false) }
    var taskToEdit by remember { mutableStateOf<TodoItem?>(null) }

    fun persistTasks() {
        saveTasks(context, todoList)
    }

    val completedCount = todoList.count { it.isCompleted }
    val totalCount = todoList.size
    val progress = if (totalCount > 0) completedCount.toFloat() / totalCount else 0f

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "To-Do List",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (totalCount == 0) "Nicio sarcină activă"
                            else "$completedCount din $totalCount sarcini finalizate",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAddDialog = true },
                icon = {
                    Icon(
                        imageVector = AppIcons.Add,
                        contentDescription = "Adaugă sarcină"
                    )
                },
                text = { Text("Add Task") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            // Card de progres
            if (totalCount > 0) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Progres zilnic",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = "${(progress * 100).toInt()}%",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(CircleShape),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f)
                        )
                    }
                }
            }

            // Bara rapida de adaugare sarcina + buton Add
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = quickTaskTitle,
                    onValueChange = { quickTaskTitle = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Scrie o sarcină nouă...") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (quickTaskTitle.isNotBlank()) {
                                todoList.add(0, TodoItem(title = quickTaskTitle.trim()))
                                quickTaskTitle = ""
                                persistTasks()
                            }
                        }
                    )
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        if (quickTaskTitle.isNotBlank()) {
                            todoList.add(0, TodoItem(title = quickTaskTitle.trim()))
                            quickTaskTitle = ""
                            persistTasks()
                        } else {
                            showAddDialog = true
                        }
                    },
                    modifier = Modifier.height(56.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = AppIcons.Add,
                        contentDescription = "Add"
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Lista de sarcini sau mesaj cand lista e goala
            if (todoList.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Surface(
                            modifier = Modifier.size(72.dp),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = AppIcons.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(36.dp),
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Nu ai nicio sarcină momentan",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Apasă pe butonul Add pentru a adăuga o sarcină nouă.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentPadding = PaddingValues(bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(
                        items = todoList,
                        key = { it.id }
                    ) { item ->
                        TodoItemCard(
                            item = item,
                            onToggleComplete = {
                                val index = todoList.indexOfFirst { it.id == item.id }
                                if (index != -1) {
                                    todoList[index] = item.copy(isCompleted = !item.isCompleted)
                                    persistTasks()
                                }
                            },
                            onEditClick = {
                                taskToEdit = item
                            },
                            onDeleteClick = {
                                todoList.removeAll { it.id == item.id }
                                persistTasks()
                            }
                        )
                    }
                }
            }
        }
    }

    // Dialog pentru adaugare sarcina detaliata
    if (showAddDialog) {
        TaskDialog(
            dialogTitle = "Adaugă sarcină nouă",
            confirmButtonText = "Add",
            initialTitle = quickTaskTitle,
            initialDescription = "",
            onDismiss = { showAddDialog = false },
            onConfirm = { title, description ->
                todoList.add(
                    0,
                    TodoItem(
                        title = title,
                        description = description
                    )
                )
                quickTaskTitle = ""
                persistTasks()
                showAddDialog = false
            }
        )
    }

    // Dialog pentru editare sarcina existenta
    taskToEdit?.let { currentTask ->
        TaskDialog(
            dialogTitle = "Editează sarcina",
            confirmButtonText = "Salvează",
            initialTitle = currentTask.title,
            initialDescription = currentTask.description,
            onDismiss = { taskToEdit = null },
            onConfirm = { newTitle, newDescription ->
                val index = todoList.indexOfFirst { it.id == currentTask.id }
                if (index != -1) {
                    todoList[index] = currentTask.copy(
                        title = newTitle,
                        description = newDescription
                    )
                    persistTasks()
                }
                taskToEdit = null
            }
        )
    }
}

@Composable
fun TodoItemCard(
    item: TodoItem,
    onToggleComplete: () -> Unit,
    onEditClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    val containerColor by animateColorAsState(
        targetValue = if (item.isCompleted) {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        label = "cardColorAnimation"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggleComplete() },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = if (item.isCompleted) 0.dp else 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = item.isCompleted,
                onCheckedChange = { onToggleComplete() }
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    textDecoration = if (item.isCompleted) TextDecoration.LineThrough else TextDecoration.None,
                    color = if (item.isCompleted) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
                if (item.description.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = item.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textDecoration = if (item.isCompleted) TextDecoration.LineThrough else TextDecoration.None,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Buton Edit
            FilledTonalIconButton(
                onClick = onEditClick,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                )
            ) {
                Icon(
                    imageVector = AppIcons.Edit,
                    contentDescription = "Edit",
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            // Buton Delete
            FilledTonalIconButton(
                onClick = onDeleteClick,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            ) {
                Icon(
                    imageVector = AppIcons.Delete,
                    contentDescription = "Delete",
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
fun TaskDialog(
    dialogTitle: String,
    confirmButtonText: String,
    initialTitle: String,
    initialDescription: String,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit
) {
    var title by remember { mutableStateOf(initialTitle) }
    var description by remember { mutableStateOf(initialDescription) }
    var showError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = dialogTitle,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = title,
                    onValueChange = {
                        title = it
                        if (it.isNotBlank()) showError = false
                    },
                    label = { Text("Titlu sarcină *") },
                    placeholder = { Text("Ex: Cumpără fructe") },
                    isError = showError,
                    supportingText = if (showError) {
                        { Text("Titlul nu poate fi gol") }
                    } else null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Detalii / Descriere (opțional)") },
                    placeholder = { Text("Adaugă notițe suplimentare...") },
                    minLines = 3,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (title.isBlank()) {
                        showError = true
                    } else {
                        onConfirm(title.trim(), description.trim())
                    }
                }
            ) {
                Text(confirmButtonText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Anulează")
            }
        }
    )
}

private const val PREFS_NAME = "todo_prefs"
private const val KEY_TASKS = "tasks_json"
private const val KEY_INITIALIZED = "initialized_sample"

private fun saveTasks(context: Context, tasks: List<TodoItem>) {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val jsonArray = JSONArray()
    for (task in tasks) {
        val obj = JSONObject().apply {
            put("id", task.id)
            put("title", task.title)
            put("description", task.description)
            put("isCompleted", task.isCompleted)
        }
        jsonArray.put(obj)
    }
    prefs.edit { putString(KEY_TASKS, jsonArray.toString()) }
}

private fun loadTasks(context: Context): List<TodoItem> {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val isInitialized = prefs.getBoolean(KEY_INITIALIZED, false)
    if (!isInitialized) {
        val initialSamples = listOf(
            TodoItem(
                title = "Studiază pentru proiectul Android",
                description = "Verifică funcțiile de Add, Edit și Delete din aplicație",
                isCompleted = false
            ),
            TodoItem(
                title = "Cumpărături",
                description = "Lapte, pâine, cafea și fructe",
                isCompleted = false
            ),
            TodoItem(
                title = "Configurează Android Studio",
                description = "Proiectul To-Do List este gata!",
                isCompleted = true
            )
        )
        prefs.edit { putBoolean(KEY_INITIALIZED, true) }
        saveTasks(context, initialSamples)
        return initialSamples
    }

    val jsonString = prefs.getString(KEY_TASKS, null) ?: return emptyList()
    return try {
        val jsonArray = JSONArray(jsonString)
        val list = mutableListOf<TodoItem>()
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            list.add(
                TodoItem(
                    id = obj.optString("id", UUID.randomUUID().toString()),
                    title = obj.getString("title"),
                    description = obj.optString("description", ""),
                    isCompleted = obj.optBoolean("isCompleted", false)
                )
            )
        }
        list
    } catch (_: Exception) {
        emptyList()
    }
}

object AppIcons {
    val Add: ImageVector by lazy {
        ImageVector.Builder(
            name = "Add",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(19f, 13f)
                horizontalLineToRelative(-6f)
                verticalLineToRelative(6f)
                horizontalLineToRelative(-2f)
                verticalLineToRelative(-6f)
                horizontalLineTo(5f)
                verticalLineToRelative(-2f)
                horizontalLineToRelative(6f)
                verticalLineTo(5f)
                horizontalLineToRelative(2f)
                verticalLineToRelative(6f)
                horizontalLineToRelative(6f)
                verticalLineToRelative(2f)
                close()
            }
        }.build()
    }

    val Edit: ImageVector by lazy {
        ImageVector.Builder(
            name = "Edit",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(3f, 17.25f)
                verticalLineTo(21f)
                horizontalLineToRelative(3.75f)
                lineTo(17.81f, 9.94f)
                lineToRelative(-3.75f, -3.75f)
                lineTo(3f, 17.25f)
                close()
                moveTo(20.71f, 7.04f)
                curveToRelative(0.39f, -0.39f, 0.39f, -1.02f, 0f, -1.41f)
                lineToRelative(-2.34f, -2.34f)
                curveToRelative(-0.39f, -0.39f, -1.02f, -0.39f, -1.41f, 0f)
                lineToRelative(-1.83f, 1.83f)
                lineToRelative(3.75f, 3.75f)
                lineToRelative(1.83f, -1.83f)
                close()
            }
        }.build()
    }

    val Delete: ImageVector by lazy {
        ImageVector.Builder(
            name = "Delete",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(6f, 19f)
                curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
                horizontalLineToRelative(8f)
                curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
                verticalLineTo(7f)
                horizontalLineTo(6f)
                verticalLineToRelative(12f)
                close()
                moveTo(19f, 4f)
                horizontalLineToRelative(-3.5f)
                lineToRelative(-1f, -1f)
                horizontalLineToRelative(-5f)
                lineToRelative(-1f, 1f)
                horizontalLineTo(5f)
                verticalLineToRelative(2f)
                horizontalLineToRelative(14f)
                verticalLineTo(4f)
                close()
            }
        }.build()
    }

    val Check: ImageVector by lazy {
        ImageVector.Builder(
            name = "Check",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(9f, 16.17f)
                lineTo(4.83f, 12f)
                lineToRelative(-1.42f, 1.41f)
                lineTo(9f, 19f)
                lineTo(21f, 7f)
                lineToRelative(-1.41f, -1.41f)
                close()
            }
        }.build()
    }
}

@Preview(showBackground = true)
@Composable
fun TodoAppPreview() {
    ToDoListTheme {
        TodoApp()
    }
}
