package io.legado.app.help.gesture

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import io.legado.app.help.config.AppConfig
import kotlin.math.abs

/**
 * 体感翻页：左右抖动手机翻页
 *
 * 以加速度 X 轴为检测对象，使用动态基线过滤持机姿态的静态重力分量，
 * 只有短促的水平冲击（主动抖动）才会触发翻页；走路、颠簸等持续低幅运动被基线跟踪自然滤除。
 */
class ShakePageDetector(
    context: Context,
    private val onShake: (direction: Int) -> Unit,
) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var accelerometer: Sensor? = null

    // 动态基线（低通滤波跟踪静态重力分量）
    private var baselineX = 0f

    // 上次触发时间，冷却期防连翻
    private var lastTriggerTime = 0L

    // 本次注册时间，用于侧躺时的基线预热
    private var registerTime = 0L

    fun register() {
        if (!AppConfig.shakePageTurn) {
            return
        }
        if (accelerometer == null) {
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        }
        accelerometer?.let {
            baselineX = 0f
            registerTime = SystemClock.elapsedRealtime()
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun unregister() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) {
            return
        }
        val x = event.values[0]
        val now = SystemClock.elapsedRealtime()

        // 侧躺优化：注册后先预热基线（约2秒），避免持机姿态突变导致误翻
        if (now - registerTime < WARMUP_MILLIS) {
            baselineX += BASELINE_ALPHA * (x - baselineX)
            return
        }

        val delta = x - baselineX
        // 冲击期间冻结基线，防止把抖动本身吸收进基线
        if (abs(delta) <= threshold * 0.6f) {
            baselineX += BASELINE_ALPHA * (x - baselineX)
        }

        if (now - lastTriggerTime < COOLDOWN_MILLIS) {
            return
        }

        if (abs(delta) > threshold) {
            lastTriggerTime = now
            if (AppConfig.shakePageBothNext) {
                // 左右抖动都翻下一页（侧躺阅读）
                onShake(1)
            } else {
                // 抖向左侧（delta<0）默认下一页，与滑动方向一致
                val direction = if (delta < 0) 1 else -1
                onShake(if (AppConfig.shakePageReverse) -direction else direction)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private val threshold: Float
        get() = 7.5f - AppConfig.shakePageSensitivity.coerceIn(1, 5)

    companion object {
        // 触发后冷却，抖一次只翻一页
        private const val COOLDOWN_MILLIS = 800L
        private const val BASELINE_ALPHA = 0.02f

        // 侧躺/姿态切换后的基线预热时间
        private const val WARMUP_MILLIS = 2000L
    }
}
