package com.dancewithme.app.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.math.max

/**
 * Rezultatul înregistrării unei încercări de dans în [ScoreHistoryRepository].
 *
 * @property updatedProgress Progresul actualizat pentru dansul curent.
 * @property isNewBestScore `true` dacă scorul curent este un nou record personal (prima încercare cu `score > 0f`
 *                          sau `score > previousBestScore`).
 */
data class RecordAttemptResult(
    val updatedProgress: DanceProgress,
    val isNewBestScore: Boolean
)

/**
 * Interfața Repository-ului pentru persistența locală a recordurilor personale și a numărului de încercări.
 */
interface ScoreHistoryRepository {
    suspend fun getAllProgress(): Map<String, DanceProgress>
    suspend fun recordAttempt(
        danceId: String,
        score: Float,
        timestampMs: Long = System.currentTimeMillis()
    ): RecordAttemptResult

    suspend fun clearAll()
}

/**
 * Implementare în memorie a [ScoreHistoryRepository], ideală pentru teste unitare, Preview-uri Compose
 * și ca valoare default în [com.dancewithme.app.ui.DrillViewModel] fără dependență de Android [Context].
 */
class InMemoryScoreHistoryRepository : ScoreHistoryRepository {
    private val mutex = Mutex()
    private val storage = LinkedHashMap<String, DanceProgress>()

    override suspend fun getAllProgress(): Map<String, DanceProgress> = mutex.withLock {
        storage.toMap()
    }

    override suspend fun recordAttempt(
        danceId: String,
        score: Float,
        timestampMs: Long
    ): RecordAttemptResult = mutex.withLock {
        val existing = storage[danceId]
        val result = computeUpdatedProgress(existing, danceId, score, timestampMs)
        storage[danceId] = result.updatedProgress
        result
    }

    override suspend fun clearAll() = mutex.withLock {
        storage.clear()
    }
}

/**
 * Implementare persistentă a [ScoreHistoryRepository] bazată pe [android.content.SharedPreferences]
 * (`"dance_with_me_scores"`), care serializează în JSON harta `Map<String, DanceProgress>` pe [Dispatchers.IO].
 */
class SharedPreferencesScoreHistoryRepository(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ScoreHistoryRepository {

    private val appContext = context.applicationContext
    private val mutex = Mutex()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = false
    }

    private val mapSerializer = MapSerializer(String.serializer(), DanceProgress.serializer())

    override suspend fun getAllProgress(): Map<String, DanceProgress> = withContext(ioDispatcher) {
        mutex.withLock {
            readMapFromPrefs()
        }
    }

    override suspend fun recordAttempt(
        danceId: String,
        score: Float,
        timestampMs: Long
    ): RecordAttemptResult = withContext(ioDispatcher) {
        mutex.withLock {
            val currentMap = readMapFromPrefs().toMutableMap()
            val existing = currentMap[danceId]
            val result = computeUpdatedProgress(existing, danceId, score, timestampMs)
            currentMap[danceId] = result.updatedProgress
            writeMapToPrefs(currentMap)
            result
        }
    }

    override suspend fun clearAll() = withContext(ioDispatcher) {
        mutex.withLock {
            val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit { remove(KEY_PROGRESS_JSON) }
        }
    }

    private fun readMapFromPrefs(): Map<String, DanceProgress> {
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val rawJson = prefs.getString(KEY_PROGRESS_JSON, null) ?: return emptyMap()
        return runCatching {
            json.decodeFromString(mapSerializer, rawJson)
        }.getOrDefault(emptyMap())
    }

    private fun writeMapToPrefs(map: Map<String, DanceProgress>) {
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val encoded = json.encodeToString(mapSerializer, map)
        prefs.edit { putString(KEY_PROGRESS_JSON, encoded) }
    }

    companion object {
        const val PREFS_NAME = "dance_with_me_scores"
        const val KEY_PROGRESS_JSON = "dance_progress_map_json"
    }
}

/**
 * Funcție pură internă care calculează noul [DanceProgress] și determină dacă încercarea curentă
 * reprezintă un nou record personal (`isNewBestScore`).
 */
internal fun computeUpdatedProgress(
    existing: DanceProgress?,
    danceId: String,
    score: Float,
    timestampMs: Long
): RecordAttemptResult {
    val sanitizedScore = score.coerceIn(0f, 100f)
    val isNewBest = if (existing == null) {
        sanitizedScore > 0f
    } else {
        sanitizedScore > existing.bestScore
    }
    val newBestScore = if (existing == null) {
        sanitizedScore
    } else {
        max(existing.bestScore, sanitizedScore)
    }
    val updated = DanceProgress(
        danceId = danceId,
        bestScore = newBestScore,
        attemptsCount = (existing?.attemptsCount ?: 0) + 1,
        lastScore = sanitizedScore,
        lastPlayedTimestampMs = timestampMs
    )
    return RecordAttemptResult(
        updatedProgress = updated,
        isNewBestScore = isNewBest
    )
}
