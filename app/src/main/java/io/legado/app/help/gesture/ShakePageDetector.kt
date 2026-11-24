package io.legado.app.help.gesture

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.view.Surface
import android.view.WindowManager
import io.legado.app.help.config.AppConfig
import kotlin.math.abs

/**
 * 体感翻页：左右抖动手机翻页
 *
 * 核心设计：
 * 1. 屏幕坐标系投影：通过旋转矢量传感器 + 屏幕旋转角度，把加速度投影到"屏幕横向"，
 *    竖屏、横屏、侧躺横持下抖动方向都与屏幕内容方向一致（侧躺优化核心）。
 * 2. 动态基线：跟踪重力在屏幕横向的静态分量，隔离持机姿态差异。
 * 3. 边沿触发：触发一次后必须先回到基线附近才重新武装，
 *    手机转过去停住不归位只会翻一页，不会连续翻页。
 */
class ShakePageDetector(
    private val context: Context,
    private val onShake: (direction: Int) -> Unit,
) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var accelerometer: Sensor? = null
    private var rotationVector: Sensor? = null

    private val rotationMatrix = FloatArray(9)
    private val screenMatrix = FloatArray(9)
    private var hasOrientation = false

    // 屏幕横向的动态基线
    private var baseline = 0f

    private var lastTriggerTime = 0L

    // 边沿触发状态：false = 离开基线中（已触发或基线未稳定），禁止触发
    private var armed = false

    // 回摆确认：第一次冲击只登记，等反方向回摆才算真抖动
    private var candidateStage = false
    private var candidateDir = 0f
    private var candidateTime = 0L

    fun register() {
        if (!AppConfig.shakePageTurn) {
            return
        }
        if (accelerometer == null) {
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            rotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        }
        baseline = 0f
        armed = false
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        rotationVector?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun unregister() {
        sensorManager.unregisterListener(this)
        hasOrientation = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                val screenRotation = try {
                    val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                    wm.defaultDisplay.rotation
                } catch (e: Exception) {
                    Surface.ROTATION_0
                }
                when (screenRotation) {
                    Surface.ROTATION_90 -> SensorManager.remapCoordinateSystem(
                        rotationMatrix, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, screenMatrix
                    )
                    Surface.ROTATION_270 -> SensorManager.remapCoordinateSystem(
                        rotationMatrix, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, screenMatrix
                    )
                    Surface.ROTATION_180 -> SensorManager.remapCoordinateSystem(
                        rotationMatrix, SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Z, screenMatrix
                    )
                    else -> System.arraycopy(rotationMatrix, 0, screenMatrix, 0, 9)
                }
                hasOrientation = true
            }

            Sensor.TYPE_ACCELEROMETER -> handleAccelerometer(event.values)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun handleAccelerometer(values: FloatArray) {
        // 加速度投影到屏幕横向（无旋转矢量数据时退化为传感器 X 轴）
        val screenX = if (hasOrientation) {
            screenMatrix[0] * values[0] + screenMatrix[1] * values[1] + screenMatrix[2] * values[2]
        } else {
            values[0]
        }

        // 灵敏度 1-100 → 阈值 7.95（最钝）~2.5（最灵敏）
        val threshold = 8.0f - AppConfig.shakePageSensitivity.coerceIn(1, 100) / 100f * 5.5f
        val now = SystemClock.elapsedRealtime()
        val delta = screenX - baseline

        // 候选确认期：冻结基线、跳过归位判定，只等反方向回摆。
        // 否则回摆经过零点会被"归位分支"拦截，反方向冲击永远无法确认。
        if (candidateStage) {
            if (now - candidateTime > REVERSAL_WINDOW_MILLIS) {
                // 窗口内没有回摆：单次冲击（放下/挪动手机），忽略
                candidateStage = false
            } else {
                val dir = if (delta < 0) -1f else 1f
                if (dir != candidateDir && abs(delta) > threshold * REVERSAL_RATIO) {
                    // 反方向回摆确认 => 真实抖动，翻页
                    candidateStage = false
                    armed = false
                    lastTriggerTime = now
                    if (AppConfig.shakePageBothNext) {
                        // 左右抖动都翻下一页（侧躺阅读）
                        onShake(1)
                    } else {
                        // 抖向屏幕左侧默认下一页，与滑动方向一致
                        val direction = if (candidateDir < 0) 1 else -1
                        onShake(if (AppConfig.shakePageReverse) -direction else direction)
                    }
                }
            }
            return
        }

        // 在基线附近：快速收敛基线，回到待命状态
        if (abs(delta) <= threshold * ARM_RATIO) {
            baseline += BASELINE_FAST * (screenX - baseline)
            armed = true
            return
        }

        // 离开基线：慢速吸收静态偏移（转动不归位时基线约3秒追上）
        baseline += BASELINE_SLOW * (screenX - baseline)

        if (!armed || now - lastTriggerTime < COOLDOWN_MILLIS) {
            return
        }

        // 第一次冲击：只登记候选方向，不翻页。
        // 放下手机、挪位置、调整姿势都只产生单次冲击，等不到回摆就会被丢弃。
        if (abs(delta) > threshold) {
            candidateStage = true
            candidateDir = if (delta < 0) -1f else 1f
            candidateTime = now
        }
    }

    companion object {
        // 触发后冷却，抖一次只翻一页
        private const val COOLDOWN_MILLIS = 800L

        // 回到基线的判定比例（阈值×0.4 以内视为归位）
        private const val ARM_RATIO = 0.4f

        // 回摆确认窗口与回摆幅度要求
        private const val REVERSAL_WINDOW_MILLIS = 600L
        private const val REVERSAL_RATIO = 0.5f

        // 基线收敛系数：归位时快（约0.5秒收敛），偏移时慢（避免吸收抖动、慢慢吸收姿态偏移）
        private const val BASELINE_FAST = 0.06f
        private const val BASELINE_SLOW = 0.008f
    }
}
