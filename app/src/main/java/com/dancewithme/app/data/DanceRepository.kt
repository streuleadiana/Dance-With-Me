package com.dancewithme.app.data

import android.content.Context
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.math.cos
import kotlin.math.sin

/**
 * Interfața Repository-ului pentru obținerea și gestionarea dansurilor de referință (Clean Architecture).
 */
interface DanceRepository {
    suspend fun getAvailableDances(): List<ReferenceDance>
    suspend fun getDanceById(danceId: String): ReferenceDance?
    suspend fun saveCustomDance(dance: ReferenceDance)
    suspend fun deleteCustomDance(danceId: String)
    fun parseReferenceDanceJson(jsonString: String): ReferenceDance
    fun serializeReferenceDance(dance: ReferenceDance): String
}

/**
 * Implementarea [DanceRepository] care încarcă cu prioritate fișierele JSON din `assets/dances/`
 * și oferă cele 4 coregrafii pre-calibrate la 30 FPS (600 cadre / 20 secunde, 8 unghiuri articulare),
 * plus suport complet offline pentru salvarea și ștergerea dansurilor personalizate în `filesDir/custom_dances/`:
 * - Hora Simplă (`hora_simpla_01`)
 * - Sârba (`sarba_02`)
 * - Makedoneasca (`makedoneasca_03`)
 * - Gangnam Style (`gangnam_style_04`)
 * - Dansuri create de utilizator (`custom_*`)
 */
class DefaultDanceRepository(
    private val context: Context? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : DanceRepository {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = false
    }

    private val customLock = Any()
    private val customDancesInMemory = mutableListOf<ReferenceDance>()

    override suspend fun getAvailableDances(): List<ReferenceDance> = withContext(ioDispatcher) {
        val assetDances = loadDancesFromAssetsIfAvailable()
        val baseDances = if (assetDances.isNotEmpty()) {
            assetDances
        } else {
            builtInReferenceDances()
        }
        val customDances = loadCustomDances()
        baseDances + customDances
    }

    override suspend fun getDanceById(danceId: String): ReferenceDance? = withContext(ioDispatcher) {
        getAvailableDances().find { it.id == danceId }
    }

    override suspend fun saveCustomDance(dance: ReferenceDance): Unit = withContext(ioDispatcher) {
        val ctx = context
        if (ctx != null) {
            val dir = File(ctx.filesDir, CUSTOM_DANCES_DIR_NAME)
            if (!dir.exists()) {
                dir.mkdirs()
            }
            val targetFile = File(dir, "${dance.id}.json")
            targetFile.writeText(serializeReferenceDance(dance), Charsets.UTF_8)
        } else {
            synchronized(customLock) {
                customDancesInMemory.removeAll { it.id == dance.id }
                customDancesInMemory.add(dance)
            }
        }
    }

    override suspend fun deleteCustomDance(danceId: String): Unit = withContext(ioDispatcher) {
        val ctx = context
        if (ctx != null) {
            val dir = File(ctx.filesDir, CUSTOM_DANCES_DIR_NAME)
            val targetFile = File(dir, "$danceId.json")
            if (targetFile.exists()) {
                targetFile.delete()
            }
        } else {
            synchronized(customLock) {
                customDancesInMemory.removeAll { it.id == danceId }
            }
        }
    }

    override fun parseReferenceDanceJson(jsonString: String): ReferenceDance {
        return json.decodeFromString(ReferenceDance.serializer(), jsonString)
    }

    override fun serializeReferenceDance(dance: ReferenceDance): String {
        return json.encodeToString(ReferenceDance.serializer(), dance)
    }

    private fun loadCustomDances(): List<ReferenceDance> {
        val ctx = context
        if (ctx == null) {
            return synchronized(customLock) {
                customDancesInMemory.toList()
            }
        }
        return try {
            val dir = File(ctx.filesDir, CUSTOM_DANCES_DIR_NAME)
            if (!dir.exists() || !dir.isDirectory) return emptyList()
            dir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".json") }
                ?.sortedBy { it.name }
                ?.mapNotNull { file ->
                    runCatching {
                        parseReferenceDanceJson(file.readText(Charsets.UTF_8))
                    }.getOrNull()
                }
                .orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun loadDancesFromAssetsIfAvailable(): List<ReferenceDance> {
        val ctx = context ?: return emptyList()
        return try {
            val files = ctx.assets.list("dances")?.filter { it.endsWith(".json") }.orEmpty()
            files.mapNotNull { fileName ->
                runCatching {
                    val rawJson = ctx.assets.open("dances/$fileName").bufferedReader().use { it.readText() }
                    parseReferenceDanceJson(rawJson)
                }.getOrNull()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    companion object {
        private const val DEFAULT_DURATION_SEC = 20
        private const val DEFAULT_FPS = 30
        private const val TOTAL_FRAMES = DEFAULT_DURATION_SEC * DEFAULT_FPS // 600 cadre

        const val CUSTOM_DANCE_ID_PREFIX = "custom_"
        const val CUSTOM_DANCES_DIR_NAME = "custom_dances"

        const val ID_HORA_SIMPLA = "hora_simpla_01"
        const val ID_SARBA = "sarba_02"
        const val ID_MAKEDONEASCA = "makedoneasca_03"
        const val ID_GANGNAM_STYLE = "gangnam_style_04"

        fun isCustomDance(danceId: String): Boolean = danceId.startsWith(CUSTOM_DANCE_ID_PREFIX)

        /**
         * Generează cele 4 dansuri de referință conform specificațiilor (600 cadre @ 30 FPS)
         * cu traiectorii continue ale celor 8 unghiuri articulare (inclusiv umerii) și `ghostFrames` animate.
         */
        fun builtInReferenceDances(): List<ReferenceDance> = listOf(
            createHoraSimplaDance(),
            createSarbaDance(),
            createMakedoneascaDance(),
            createGangnamStyleDance()
        )

        private fun createHoraSimplaDance(): ReferenceDance {
            val frames = List(TOTAL_FRAMES) { frameIdx ->
                val t = frameIdx.toFloat() / DEFAULT_FPS // timpul în secunde [0..20]
                val phase = (t * Math.PI * 2.0).toFloat() // 1 ciclu pe secundă (pasul lin de Horă)

                AngleVector(
                    genunchiStang = 155f + 20f * sin(phase),
                    genunchiDrept = 155f + 20f * sin(phase),
                    soldStang = 160f + 15f * cos(phase),
                    soldDrept = 160f - 15f * cos(phase),
                    cotStang = 110f + 35f * sin(phase),
                    cotDrept = 110f + 35f * cos(phase),
                    // Hora Simplă: umerii ridicați lateral (~85°–105°)
                    umarStang = 95f + 10f * sin(phase),
                    umarDrept = 95f + 10f * sin(phase)
                )
            }

            val ghostFrames = List(TOTAL_FRAMES) { frameIdx ->
                val t = frameIdx.toFloat() / DEFAULT_FPS
                val phase = (t * Math.PI * 2.0).toFloat()
                val swayX = 0.04f * sin(phase * 0.5f)
                val bounceY = 0.02f * absSin(phase)
                val armWave = 0.04f * sin(phase)

                buildSkeletonFrame(
                    centerX = 0.50f + swayX,
                    headY = 0.17f + bounceY,
                    shoulderY = 0.28f + bounceY,
                    hipY = 0.54f + bounceY,
                    leftElbow = Pair(0.24f + swayX, 0.29f + bounceY - armWave * 0.5f),
                    rightElbow = Pair(0.76f + swayX, 0.29f + bounceY - armWave * 0.5f),
                    leftWrist = Pair(0.16f + swayX, 0.22f + bounceY - armWave),
                    rightWrist = Pair(0.84f + swayX, 0.22f + bounceY - armWave),
                    leftKnee = Pair(0.41f + swayX * 1.1f, 0.73f + bounceY * 0.6f),
                    rightKnee = Pair(0.59f + swayX * 1.1f, 0.73f + bounceY * 0.6f),
                    leftAnkle = Pair(0.40f + swayX * 1.2f, 0.90f),
                    rightAnkle = Pair(0.60f + swayX * 1.2f, 0.90f)
                )
            }

            return ReferenceDance(
                id = ID_HORA_SIMPLA,
                title = "Hora Simplă",
                durationSec = DEFAULT_DURATION_SEC,
                bpm = 60,
                frames = frames,
                description = "Pas lateral lin stânga-dreapta în cerc. Ține brațele ridicate lateral la nivelul umerilor (umerii la ~95°, coatele ușor flexate la ~110°) și îndoaie ușor ambii genunchi pe ritm.",
                videoAssetName = "hora_simpla.mp4",
                ghostFrames = ghostFrames
            )
        }

        private fun createSarbaDance(): ReferenceDance {
            val frames = List(TOTAL_FRAMES) { frameIdx ->
                val t = frameIdx.toFloat() / DEFAULT_FPS
                val phase = (t * Math.PI * 3.0).toFloat() // ritm alert de Sârbă

                AngleVector(
                    genunchiStang = 150f + 25f * sin(phase),
                    genunchiDrept = 150f - 25f * sin(phase),
                    soldStang = 155f + 20f * sin(phase),
                    soldDrept = 155f - 20f * sin(phase),
                    cotStang = 95f + 15f * cos(phase),
                    cotDrept = 95f - 15f * cos(phase),
                    // Sârba: brațele întinse lateral pe umerii partenerilor (~90°–100°)
                    umarStang = 95f + 5f * cos(phase),
                    umarDrept = 95f - 5f * cos(phase)
                )
            }

            val ghostFrames = List(TOTAL_FRAMES) { frameIdx ->
                val t = frameIdx.toFloat() / DEFAULT_FPS
                val phase = (t * Math.PI * 3.0).toFloat()
                val stepShiftX = 0.03f * cos(phase)
                val leftKneeLift = (-0.06f * sin(phase)).coerceAtMost(0f)
                val rightKneeLift = (0.06f * sin(phase)).coerceAtMost(0f)

                buildSkeletonFrame(
                    centerX = 0.50f + stepShiftX,
                    headY = 0.16f,
                    shoulderY = 0.27f,
                    hipY = 0.53f,
                    leftElbow = Pair(0.25f + stepShiftX, 0.27f),
                    rightElbow = Pair(0.75f + stepShiftX, 0.27f),
                    leftWrist = Pair(0.15f + stepShiftX, 0.26f),
                    rightWrist = Pair(0.85f + stepShiftX, 0.26f),
                    leftKnee = Pair(0.41f + stepShiftX, 0.72f + leftKneeLift),
                    rightKnee = Pair(0.59f + stepShiftX, 0.72f + rightKneeLift),
                    leftAnkle = Pair(0.41f + stepShiftX, 0.90f + leftKneeLift * 0.8f),
                    rightAnkle = Pair(0.59f + stepShiftX, 0.90f + rightKneeLift * 0.8f)
                )
            }

            return ReferenceDance(
                id = ID_SARBA,
                title = "Sârba",
                durationSec = DEFAULT_DURATION_SEC,
                bpm = 90,
                frames = frames,
                description = "Ritm rapid și energic! Ține brațele întinse lateral pe umerii partenerilor (~95°) și execută pași săltați alternativi, ridicând genunchiul stâng și cel drept în contratimp.",
                videoAssetName = "sarba.mp4",
                ghostFrames = ghostFrames
            )
        }

        private fun createMakedoneascaDance(): ReferenceDance {
            val frames = List(TOTAL_FRAMES) { frameIdx ->
                val t = frameIdx.toFloat() / DEFAULT_FPS
                val phase = (t * Math.PI * 2.5).toFloat()

                AngleVector(
                    genunchiStang = 140f + 25f * cos(phase),
                    genunchiDrept = 140f + 25f * cos(phase),
                    soldStang = 145f + 20f * sin(phase),
                    soldDrept = 145f + 20f * sin(phase),
                    cotStang = 90f + 50f * sin(phase),
                    cotDrept = 90f + 50f * sin(phase + 1.5f),
                    // Makedoneasca: balans alternativ al umerilor (~70°–125°)
                    umarStang = 97.5f + 27.5f * sin(phase),
                    umarDrept = 97.5f - 27.5f * sin(phase)
                )
            }

            val ghostFrames = List(TOTAL_FRAMES) { frameIdx ->
                val t = frameIdx.toFloat() / DEFAULT_FPS
                val phase = (t * Math.PI * 2.5).toFloat()
                val lungeDrop = 0.035f * (1f - cos(phase)) * 0.5f
                val swayX = 0.045f * sin(phase)
                val armArc = 0.07f * sin(phase)

                buildSkeletonFrame(
                    centerX = 0.50f + swayX,
                    headY = 0.17f + lungeDrop,
                    shoulderY = 0.28f + lungeDrop,
                    hipY = 0.54f + lungeDrop,
                    leftElbow = Pair(0.27f + swayX, 0.34f + lungeDrop - armArc * 0.5f),
                    rightElbow = Pair(0.73f + swayX, 0.34f + lungeDrop + armArc * 0.5f),
                    leftWrist = Pair(0.22f + swayX, 0.21f + lungeDrop - armArc),
                    rightWrist = Pair(0.78f + swayX, 0.21f + lungeDrop + armArc),
                    leftKnee = Pair(0.39f + swayX, 0.73f + lungeDrop * 0.7f),
                    rightKnee = Pair(0.61f + swayX, 0.73f + lungeDrop * 0.7f),
                    leftAnkle = Pair(0.37f + swayX, 0.90f),
                    rightAnkle = Pair(0.63f + swayX, 0.90f)
                )
            }

            return ReferenceDance(
                id = ID_MAKEDONEASCA,
                title = "Makedoneasca",
                durationSec = DEFAULT_DURATION_SEC,
                bpm = 75,
                frames = frames,
                description = "Măsură dinamică tradițională cu fandări ample din genunchi și balans expresiv al umerilor și brațelor (un braț urcă la ~125° în timp ce celălalt coboară la ~70°).",
                videoAssetName = "makedoneasca.mp4",
                ghostFrames = ghostFrames
            )
        }

        private fun createGangnamStyleDance(): ReferenceDance {
            val frames = List(TOTAL_FRAMES) { frameIdx ->
                val t = frameIdx.toFloat() / DEFAULT_FPS
                val gallopPhase = (t * Math.PI * 4.0).toFloat() // 2 pași de galop pe secundă
                val isLassoPhase = t >= 10.0f // 0..10s: brațe încrucișate ("căluțul"), 10..20s: lasoul cu mâna dreaptă

                val leftKneeAngle = 135f + 22f * sin(gallopPhase)
                val rightKneeAngle = 135f - 22f * sin(gallopPhase)
                val leftHipAngle = 142f + 16f * sin(gallopPhase)
                val rightHipAngle = 142f - 16f * sin(gallopPhase)

                val leftElbowAngle = if (!isLassoPhase) {
                    85f + 15f * absSin(gallopPhase)
                } else {
                    95f + 10f * sin(gallopPhase)
                }
                val rightElbowAngle = if (!isLassoPhase) {
                    85f + 15f * absSin(gallopPhase)
                } else {
                    115f + 35f * cos(gallopPhase)
                }

                // Gangnam Style umeri:
                // 0..10s: brațele în față ținând hățurile (~55°–75°)
                // 10..20s: umărul stâng rămâne în față (~65°), umărul drept ridicat sus pentru lasou (~145°–165°)
                val leftShoulderAngle = if (!isLassoPhase) {
                    65f + 10f * absSin(gallopPhase)
                } else {
                    65f + 8f * sin(gallopPhase)
                }
                val rightShoulderAngle = if (!isLassoPhase) {
                    65f + 10f * absSin(gallopPhase)
                } else {
                    155f + 10f * sin(gallopPhase)
                }

                AngleVector(
                    genunchiStang = leftKneeAngle,
                    genunchiDrept = rightKneeAngle,
                    soldStang = leftHipAngle,
                    soldDrept = rightHipAngle,
                    cotStang = leftElbowAngle,
                    cotDrept = rightElbowAngle,
                    umarStang = leftShoulderAngle,
                    umarDrept = rightShoulderAngle
                )
            }

            val ghostFrames = List(TOTAL_FRAMES) { frameIdx ->
                val t = frameIdx.toFloat() / DEFAULT_FPS
                val gallopPhase = (t * Math.PI * 4.0).toFloat()
                val isLassoPhase = t >= 10.0f

                // Mișcarea "Horse-Riding" (galop stânga-dreapta cu picioarele depărtate)
                val bounceY = 0.03f * absSin(gallopPhase)
                val hipSwayX = 0.035f * sin(gallopPhase * 0.5f)
                val leftLegBounce = (-0.05f * sin(gallopPhase)).coerceAtMost(0.01f)
                val rightLegBounce = (0.05f * sin(gallopPhase)).coerceAtMost(0.01f)

                val leftElbowPos: Pair<Float, Float>
                val rightElbowPos: Pair<Float, Float>
                val leftWristPos: Pair<Float, Float>
                val rightWristPos: Pair<Float, Float>

                if (!isLassoPhase) {
                    // Faza 1 (0..10s): Mâinile încrucișate în față (ținând hățurile) și pompând sus-jos
                    val reinPumpY = 0.035f * sin(gallopPhase)
                    leftElbowPos = Pair(0.32f + hipSwayX, 0.42f + bounceY)
                    rightElbowPos = Pair(0.68f + hipSwayX, 0.42f + bounceY)
                    leftWristPos = Pair(0.54f + hipSwayX, 0.39f + bounceY + reinPumpY)
                    rightWristPos = Pair(0.46f + hipSwayX, 0.39f + bounceY + reinPumpY)
                } else {
                    // Faza 2 (10..20s): Mâna stângă în față, mâna dreaptă rotește lasoul deasupra capului
                    val lassoCircleX = 0.06f * cos(gallopPhase * 1.5f)
                    val lassoCircleY = 0.035f * sin(gallopPhase * 1.5f)
                    leftElbowPos = Pair(0.33f + hipSwayX, 0.42f + bounceY)
                    leftWristPos = Pair(0.48f + hipSwayX, 0.40f + bounceY)
                    rightElbowPos = Pair(0.72f + hipSwayX, 0.24f + bounceY)
                    rightWristPos = Pair(0.66f + hipSwayX + lassoCircleX, 0.11f + bounceY + lassoCircleY)
                }

                buildSkeletonFrame(
                    centerX = 0.50f + hipSwayX,
                    headY = 0.17f + bounceY,
                    shoulderY = 0.28f + bounceY,
                    hipY = 0.55f + bounceY,
                    leftElbow = leftElbowPos,
                    rightElbow = rightElbowPos,
                    leftWrist = leftWristPos,
                    rightWrist = rightWristPos,
                    // Picioare depărtate în poziția de călărie ("Horse Stance")
                    leftKnee = Pair(0.34f + hipSwayX, 0.73f + leftLegBounce),
                    rightKnee = Pair(0.66f + hipSwayX, 0.73f + rightLegBounce),
                    leftAnkle = Pair(0.33f + hipSwayX, 0.90f + leftLegBounce * 0.7f),
                    rightAnkle = Pair(0.67f + hipSwayX, 0.90f + rightLegBounce * 0.7f)
                )
            }

            return ReferenceDance(
                id = ID_GANGNAM_STYLE,
                title = "Gangnam Style",
                durationSec = DEFAULT_DURATION_SEC,
                bpm = 132,
                frames = frames,
                description = "Pasul iconic „Horse-Riding”: depărtează picioarele cu genunchii flexați și sari alternativ stânga-dreapta! În primele 10 secunde ține încheieturile încrucișate la nivelul pieptului, iar în ultimele 10 secunde ridică umărul drept (~155°) și rotește brațul ca un lasou deasupra capului.",
                videoAssetName = "gangnam_style.mp4",
                ghostFrames = ghostFrames
            )
        }

        private fun absSin(rad: Float): Float = kotlin.math.abs(sin(rad))

        /**
         * Construiește o listă completă de 33 de puncte [Point3D] normalizate [0..1]
         * conform topologiei BlazePose / MediaPipe Pose.
         */
        private fun buildSkeletonFrame(
            centerX: Float,
            headY: Float,
            shoulderY: Float,
            hipY: Float,
            leftElbow: Pair<Float, Float>,
            rightElbow: Pair<Float, Float>,
            leftWrist: Pair<Float, Float>,
            rightWrist: Pair<Float, Float>,
            leftKnee: Pair<Float, Float>,
            rightKnee: Pair<Float, Float>,
            leftAnkle: Pair<Float, Float>,
            rightAnkle: Pair<Float, Float>
        ): List<Point3D> {
            val pts = MutableList(PoseFrame.REQUIRED_LANDMARKS_COUNT) {
                Point3D(x = centerX, y = hipY, z = 0f, visibility = 1.0f)
            }

            pts[PoseFrame.NOSE] = Point3D(x = centerX.coerceIn(0.05f, 0.95f), y = headY.coerceIn(0.05f, 0.95f), z = 0f, visibility = 1.0f)
            pts[PoseFrame.LEFT_SHOULDER] = Point3D(x = (centerX - 0.12f).coerceIn(0.05f, 0.95f), y = shoulderY.coerceIn(0.05f, 0.95f), z = 0f, visibility = 1.0f)
            pts[PoseFrame.RIGHT_SHOULDER] = Point3D(x = (centerX + 0.12f).coerceIn(0.05f, 0.95f), y = shoulderY.coerceIn(0.05f, 0.95f), z = 0f, visibility = 1.0f)

            pts[PoseFrame.LEFT_ELBOW] = Point3D(x = leftElbow.first.coerceIn(0.05f, 0.95f), y = leftElbow.second.coerceIn(0.05f, 0.95f), z = -0.05f, visibility = 1.0f)
            pts[PoseFrame.RIGHT_ELBOW] = Point3D(x = rightElbow.first.coerceIn(0.05f, 0.95f), y = rightElbow.second.coerceIn(0.05f, 0.95f), z = -0.05f, visibility = 1.0f)

            pts[PoseFrame.LEFT_WRIST] = Point3D(x = leftWrist.first.coerceIn(0.05f, 0.95f), y = leftWrist.second.coerceIn(0.05f, 0.95f), z = -0.10f, visibility = 1.0f)
            pts[PoseFrame.RIGHT_WRIST] = Point3D(x = rightWrist.first.coerceIn(0.05f, 0.95f), y = rightWrist.second.coerceIn(0.05f, 0.95f), z = -0.10f, visibility = 1.0f)

            pts[PoseFrame.LEFT_HIP] = Point3D(x = (centerX - 0.08f).coerceIn(0.05f, 0.95f), y = hipY.coerceIn(0.05f, 0.95f), z = 0f, visibility = 1.0f)
            pts[PoseFrame.RIGHT_HIP] = Point3D(x = (centerX + 0.08f).coerceIn(0.05f, 0.95f), y = hipY.coerceIn(0.05f, 0.95f), z = 0f, visibility = 1.0f)

            pts[PoseFrame.LEFT_KNEE] = Point3D(x = leftKnee.first.coerceIn(0.05f, 0.95f), y = leftKnee.second.coerceIn(0.05f, 0.95f), z = 0f, visibility = 1.0f)
            pts[PoseFrame.RIGHT_KNEE] = Point3D(x = rightKnee.first.coerceIn(0.05f, 0.95f), y = rightKnee.second.coerceIn(0.05f, 0.95f), z = 0f, visibility = 1.0f)

            pts[PoseFrame.LEFT_ANKLE] = Point3D(x = leftAnkle.first.coerceIn(0.05f, 0.95f), y = leftAnkle.second.coerceIn(0.05f, 0.95f), z = 0f, visibility = 1.0f)
            pts[PoseFrame.RIGHT_ANKLE] = Point3D(x = rightAnkle.first.coerceIn(0.05f, 0.95f), y = rightAnkle.second.coerceIn(0.05f, 0.95f), z = 0f, visibility = 1.0f)

            return pts
        }
    }
}
