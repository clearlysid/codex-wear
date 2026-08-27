package com.codex.wear.data.codex

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.codexTaskCache: DataStore<Preferences> by preferencesDataStore(name = "codex_task_cache")

data class TaskCacheSnapshot(
    val tasks: List<CodexTaskSummary> = emptyList(),
    val seenTaskIds: Set<String> = emptySet(),
    val completionNotifiedTaskIds: Set<String> = emptySet(),
    val usageRemainingPercent: Int? = null,
)

/** A summary-only cache. Full task transcripts and live approval handles are never persisted. */
class TaskSnapshotStore(context: Context) {
    private val dataStore = context.applicationContext.codexTaskCache

    val snapshotFlow: Flow<TaskCacheSnapshot> =
        dataStore.data
            .catch { error ->
                if (error is IOException) emit(emptyPreferences()) else throw error
            }
            .map { preferences -> decode(preferences[CACHE_KEY]) }

    suspend fun load(): TaskCacheSnapshot = snapshotFlow.first()

    suspend fun replaceTasks(tasks: List<CodexTaskSummary>) {
        dataStore.edit { preferences ->
            val current = decode(preferences[CACHE_KEY])
            val ids = tasks.asSequence().map(CodexTaskSummary::id).toSet()
            preferences[CACHE_KEY] =
                encode(
                    current.copy(
                        tasks = tasks,
                        seenTaskIds = current.seenTaskIds.intersect(ids),
                        completionNotifiedTaskIds = current.completionNotifiedTaskIds.intersect(ids),
                    ),
                )
        }
    }

    suspend fun replaceUsageRemainingPercent(remainingPercent: Int?) {
        update { it.copy(usageRemainingPercent = remainingPercent?.coerceIn(0, 100)) }
    }

    suspend fun markSeen(taskId: String) {
        update { snapshot ->
            snapshot.copy(
                tasks = snapshot.tasks.map { task ->
                    if (task.id == taskId) task.copy(isUnread = false) else task
                },
                seenTaskIds = snapshot.seenTaskIds + taskId,
            )
        }
    }

    suspend fun markCompletionNotified(taskId: String) {
        update { it.copy(completionNotifiedTaskIds = it.completionNotifiedTaskIds + taskId) }
    }

    private suspend fun update(transform: (TaskCacheSnapshot) -> TaskCacheSnapshot) {
        dataStore.edit { preferences ->
            preferences[CACHE_KEY] = encode(transform(decode(preferences[CACHE_KEY])))
        }
    }

    private fun encode(snapshot: TaskCacheSnapshot): String =
        JSONObject()
            .put("tasks", JSONArray().apply { snapshot.tasks.forEach { put(it.toJson()) } })
            .put("seen", JSONArray(snapshot.seenTaskIds.toList()))
            .put("notified", JSONArray(snapshot.completionNotifiedTaskIds.toList()))
            .putNullable("usageRemainingPercent", snapshot.usageRemainingPercent)
            .toString()

    private fun decode(raw: String?): TaskCacheSnapshot {
        if (raw.isNullOrBlank()) return TaskCacheSnapshot()
        return runCatching {
            val root = JSONObject(raw)
            TaskCacheSnapshot(
                tasks = root.optJSONArray("tasks").objects().mapNotNull(::taskFromJson),
                seenTaskIds = root.optJSONArray("seen").strings().toSet(),
                completionNotifiedTaskIds = root.optJSONArray("notified").strings().toSet(),
                usageRemainingPercent = root.optNullableInt("usageRemainingPercent"),
            )
        }.getOrDefault(TaskCacheSnapshot())
    }

    private companion object {
        val CACHE_KEY = stringPreferencesKey("task_snapshot_json")
    }
}

private fun CodexTaskSummary.toJson(): JSONObject =
    JSONObject()
        .put("id", id)
        .putNullable("title", title)
        .put("preview", preview)
        .put("state", state.name)
        .put(
            "threadStatus",
            JSONObject()
                .put("type", threadStatus.type.name)
                .put("flags", JSONArray(threadStatus.activeFlags.toList()))
                .putNullable("rawType", threadStatus.rawType),
        )
        .putNullable("project", project?.toJson())
        .putNullable("cwd", cwd)
        .putNullable("sourceKind", sourceKind)
        .put("created", createdAtEpochSeconds)
        .put("updated", updatedAtEpochSeconds)
        .putNullable("recency", recencyAtEpochSeconds)
        .putNullable("turn", latestTurnId)
        .put("unread", isUnread)
        .putNullable("error", errorMessage)

private fun CodexProject.toJson(): JSONObject =
    JSONObject()
        .put("id", id)
        .put("name", name)
        .putNullable("cwd", cwd)
        .put("lastUsed", lastUsedAtEpochSeconds)

private fun taskFromJson(json: JSONObject): CodexTaskSummary? {
    val id = json.optString("id")
    if (id.isBlank()) return null
    val statusJson = json.optJSONObject("threadStatus") ?: JSONObject()
    val threadStatus =
        CodexThreadStatus(
            type = enumValueOrDefault(statusJson.optString("type"), CodexThreadStatusType.UNKNOWN),
            activeFlags = statusJson.optJSONArray("flags").strings().toSet(),
            rawType = statusJson.optNullableString("rawType"),
        )
    val project = json.optJSONObject("project")?.let { projectJson ->
        CodexProject(
            id = projectJson.optString("id"),
            name = projectJson.optString("name").ifBlank { "Project" },
            cwd = projectJson.optNullableString("cwd"),
            lastUsedAtEpochSeconds = projectJson.optLong("lastUsed"),
        )
    }
    return CodexTaskSummary(
        id = id,
        title = json.optNullableString("title"),
        preview = json.optString("preview"),
        state = enumValueOrDefault(json.optString("state"), CodexTaskState.IDLE),
        threadStatus = threadStatus,
        project = project,
        cwd = json.optNullableString("cwd"),
        sourceKind = json.optNullableString("sourceKind"),
        createdAtEpochSeconds = json.optLong("created"),
        updatedAtEpochSeconds = json.optLong("updated"),
        recencyAtEpochSeconds = json.optNullableLong("recency"),
        latestTurnId = json.optNullableString("turn"),
        isUnread = json.optBoolean("unread"),
        errorMessage = json.optNullableString("error"),
    )
}

private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String, fallback: T): T =
    enumValues<T>().firstOrNull { it.name == value } ?: fallback

private fun JSONObject.putNullable(name: String, value: Any?): JSONObject =
    put(name, value ?: JSONObject.NULL)

private fun JSONObject.optNullableString(name: String): String? =
    if (!has(name) || isNull(name)) null else optString(name).takeIf(String::isNotBlank)

private fun JSONObject.optNullableLong(name: String): Long? =
    if (!has(name) || isNull(name)) null else optLong(name)

private fun JSONObject.optNullableInt(name: String): Int? =
    if (!has(name) || isNull(name)) null else optInt(name)

private fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull(::optJSONObject)

private fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { index ->
        optString(index).takeIf(String::isNotBlank)
    }
