package com.dancewithme.app.engine

import com.dancewithme.app.data.AngleVector
import com.dancewithme.app.data.DefaultDanceRepository
import com.dancewithme.app.data.Point3D
import com.dancewithme.app.data.PoseFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Teste unitare pentru verificarea matematicii 3D (produs scalar), a filtrului [LandmarkSmoother]
 * și a motorului [DtwEngine] cu bandă Sakoe-Chiba și erori cu semn.
 */
class EngineAndDtwUnitTest {

    @Test
    fun `calculateAngle3D returns 90 degrees for perpendicular 3D vectors`() {
        val a = Point3D(x = 0f, y = 1f, z = 0f)
        val b = Point3D(x = 0f, y = 0f, z = 0f) // Vârful (articulația)
        val c = Point3D(x = 1f, y = 0f, z = 0f)

        val angle = JointAngleCalculator.calculateAngle3D(a, b, c)
        assertEquals(90.0f, angle, 0.01f)
    }

    @Test
    fun `calculateAngle3D returns 180 degrees for collinear opposite 3D vectors`() {
        val a = Point3D(x = 0f, y = 2f, z = 0f)
        val b = Point3D(x = 0f, y = 1f, z = 0f)
        val c = Point3D(x = 0f, y = 0f, z = 0f)

        val angle = JointAngleCalculator.calculateAngle3D(a, b, c)
        assertEquals(180.0f, angle, 0.01f)
    }

    @Test
    fun `extractAngleVector computes all 8 joint angles including shoulders from 33 landmarks`() {
        val landmarks = MutableList(PoseFrame.REQUIRED_LANDMARKS_COUNT) {
            Point3D(0f, 0f, 0f)
        }
        // Configurăm genunchiul stâng (23 - 25 - 27) la 90 de grade
        landmarks[PoseFrame.LEFT_HIP] = Point3D(0f, 1f, 0f)
        landmarks[PoseFrame.LEFT_KNEE] = Point3D(0f, 0f, 0f)
        landmarks[PoseFrame.LEFT_ANKLE] = Point3D(1f, 0f, 0f)

        // Configurăm genunchiul drept (24 - 26 - 28) la 180 de grade (picior drept întins)
        landmarks[PoseFrame.RIGHT_HIP] = Point3D(0f, 2f, 0f)
        landmarks[PoseFrame.RIGHT_KNEE] = Point3D(0f, 1f, 0f)
        landmarks[PoseFrame.RIGHT_ANKLE] = Point3D(0f, 0f, 0f)

        // Configurăm umărul stâng (23 - 11 - 13) la 90 de grade (braț ridicat la orizontală)
        landmarks[PoseFrame.LEFT_SHOULDER] = Point3D(0f, 2f, 0f)
        landmarks[PoseFrame.LEFT_ELBOW] = Point3D(-1f, 2f, 0f)

        // Configurăm umărul drept (24 - 12 - 14) la 180 de grade (braț ridicat vertical deasupra capului)
        landmarks[PoseFrame.RIGHT_SHOULDER] = Point3D(0f, 3f, 0f)
        landmarks[PoseFrame.RIGHT_ELBOW] = Point3D(0f, 4f, 0f)

        val frame = PoseFrame(timestamp = 1000L, landmarks = landmarks)
        val vector = JointAngleCalculator.extractAngleVector(frame)

        assertEquals(8, vector.toFloatArray().size)
        assertEquals(8, vector.toJointMap().size)
        assertEquals(90.0f, vector.genunchiStang, 0.01f)
        assertEquals(180.0f, vector.genunchiDrept, 0.01f)
        assertEquals(90.0f, vector.umarStang, 0.01f)
        assertEquals(180.0f, vector.umarDrept, 0.01f)
    }

    @Test
    fun `LandmarkSmoother applies EMA smoothing and resets properly`() {
        val smoother = LandmarkSmoother(alpha = 0.4f)

        val frame1 = listOf(Point3D(x = 0f, y = 10f, z = 0f))
        val frame2 = listOf(Point3D(x = 10f, y = 20f, z = 10f))

        // Primul cadru trece nemodificat
        val out1 = smoother.smooth(frame1)
        assertEquals(0f, out1[0].x, 0.001f)
        assertEquals(10f, out1[0].y, 0.001f)

        // Al doilea cadru: S_2 = 0.4 * X_2 + 0.6 * S_1
        val out2 = smoother.smooth(frame2)
        assertEquals(4.0f, out2[0].x, 0.001f)
        assertEquals(14.0f, out2[0].y, 0.001f)
        assertEquals(4.0f, out2[0].z, 0.001f)

        // După reset, următorul cadru devine noua bază fără istoric
        smoother.reset()
        val outAfterReset = smoother.smooth(frame2)
        assertEquals(10.0f, outAfterReset[0].x, 0.001f)
    }

    @Test
    fun `DtwEngine returns 100 score for identical 600-frame dance sequences`() {
        val dances = DefaultDanceRepository.builtInReferenceDances()
        assertEquals(4, dances.size)
        assertEquals("Hora Simplă", dances[0].title)
        assertEquals("Sârba", dances[1].title)
        assertEquals("Makedoneasca", dances[2].title)
        assertEquals("Gangnam Style", dances[3].title)

        val referenceDance = dances.first()
        val result = DtwEngine().compare(
            userFrames = referenceDance.frames,
            reference = referenceDance
        )

        assertEquals(100.0f, result.totalScore, 0.1f)
        assertEquals(100.0f, result.formScore, 0.1f)
        assertEquals(100.0f, result.timingScore, 0.1f)
        result.jointErrors.values.forEach { error ->
            assertEquals(0.0f, error, 0.01f)
        }
        result.signedJointErrors.values.forEach { signedError ->
            assertEquals(0.0f, signedError, 0.01f)
        }
    }

    @Test
    fun `DtwEngine aligns time-shifted sequence within Sakoe-Chiba band with high form score`() {
        val referenceDance = DefaultDanceRepository.builtInReferenceDances().first()
        // Întârziem utilizatorul cu 10 cadre (~0.33 secunde la 30 FPS)
        val delayFrames = 10
        val delayedUserFrames = List(delayFrames) { referenceDance.frames.first() } +
            referenceDance.frames.dropLast(delayFrames)

        val result = DtwEngine.evaluateDrill(
            userFrames = delayedUserFrames,
            referenceFrames = referenceDance.frames,
            windowSize = 45
        )

        // Forma trebuie să fie aproape de 100% deoarece DTW aliniază secvențele decalate
        assertTrue("Form score should be > 95%, was ${result.formScore}", result.formScore > 95f)
        // Timing-ul este ușor penalizat din cauza întârzierii constante de 10 cadre
        assertTrue("Timing score should be between 70% and 99%, was ${result.timingScore}", result.timingScore in 70f..99f)
    }

    @Test
    fun `DtwEngine detects specific absolute and signed joint errors accurately`() {
        val baseFrame = AngleVector(160f, 160f, 170f, 170f, 120f, 120f)
        // Utilizatorul îndoaie prea mult cotul stâng (-30°) și ține genunchiul drept prea întins (+20°)
        val userFrameWithErrors = baseFrame.copy(
            cotStang = 90f,
            genunchiDrept = 180f
        )

        val refList = List(120) { baseFrame }
        val userList = List(120) { userFrameWithErrors }

        val result = DtwEngine.evaluateDrill(userList, refList)

        assertEquals(30.0f, result.jointErrors[AngleVector.JOINT_LEFT_ELBOW] ?: -1f, 0.1f)
        assertEquals(-30.0f, result.signedJointErrors[AngleVector.JOINT_LEFT_ELBOW] ?: 0f, 0.1f)

        assertEquals(20.0f, result.jointErrors[AngleVector.JOINT_RIGHT_KNEE] ?: -1f, 0.1f)
        assertEquals(20.0f, result.signedJointErrors[AngleVector.JOINT_RIGHT_KNEE] ?: 0f, 0.1f)

        assertEquals(0.0f, result.jointErrors[AngleVector.JOINT_LEFT_KNEE] ?: -1f, 0.1f)
        assertEquals(0.0f, result.signedJointErrors[AngleVector.JOINT_LEFT_KNEE] ?: -1f, 0.1f)
    }
}
