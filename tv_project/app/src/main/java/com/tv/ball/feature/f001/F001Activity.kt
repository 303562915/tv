package com.tv.ball.feature.f001

import android.os.Bundle
import android.view.KeyEvent
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.tv.ball.R
import com.tv.ball.databinding.ActivityF001Binding
import com.tv.ball.util.CustomToast

/**
 * 「播放器锁定」页。
 *
 * 这里做三件事：展示真实的锁定状态（读同一份 SharedPreferences）、提供锁定开关、
 * 给一个可聚焦的按键拦截演示区 —— 演示区的判定全部走 [F001Engine.handleKey]，
 * 和播放页将来接线用的是同一个状态机，不是假动画。
 */
class F001Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF001Binding
    private lateinit var engine: F001Engine

    /** 演示记录，最新一条在前；旋转时随 onSaveInstanceState 一起带回来。 */
    private val keyLog = ArrayList<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityF001Binding.inflate(layoutInflater)
        setContentView(binding.root)

        engine = F001Engine.get(this)

        savedInstanceState?.getStringArrayList(STATE_KEY_LOG)?.let { saved ->
            keyLog.clear()
            keyLog.addAll(saved)
        }

        binding.f001LockSwitch.setOnClickListener { toggleLock() }
        binding.f001ClearLog.setOnClickListener {
            keyLog.clear()
            renderKeyLog()
        }
        // 演示区自己吃按键：未锁定时放行（交回系统做焦点导航），锁定后按白名单吞掉
        binding.f001KeyDemoArea.setOnKeyListener { _, keyCode, event -> onDemoKey(keyCode, event) }

        renderAll()
        // 电视没有触摸屏：进页面就把焦点落在开关上，遥控器立刻能操作
        binding.f001LockSwitch.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        // 从播放页回来时锁定状态可能已经变了（播放页也能解锁），重新读一次
        renderAll()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(STATE_KEY_LOG, ArrayList(keyLog))
    }

    /**
     * 演示区按键：判定交给 Engine，返回值决定要不要把事件吞掉。
     */
    private fun onDemoKey(keyCode: Int, event: KeyEvent): Boolean {
        val decision = engine.handleKey(event)
        if (event.action == KeyEvent.ACTION_DOWN) {
            recordKey(keyCode, decision)
        }
        return when (decision) {
            // 未锁定：不消费，方向键继续做焦点移动，返回键正常退出
            F001KeyDecision.PASS -> false
            // 锁定：吞掉，播放页里这一下不会改变任何播放状态
            F001KeyDecision.INTERCEPT -> true
            // 解锁的这一次按键也吞掉，避免同一下顺带触发播放/退出
            F001KeyDecision.UNLOCK -> true
        }
    }

    private fun recordKey(keyCode: Int, decision: F001KeyDecision) {
        val keyName = F001Keys.nameOf(keyCode)
        val line = when (decision) {
            F001KeyDecision.INTERCEPT -> getString(R.string.f001_demo_result_intercept, keyName)
            F001KeyDecision.PASS -> getString(R.string.f001_demo_result_pass, keyName)
            F001KeyDecision.UNLOCK -> getString(R.string.f001_demo_result_unlock, keyName)
        }

        keyLog.add(0, line)
        while (keyLog.size > MAX_LOG) {
            keyLog.removeAt(keyLog.size - 1)
        }

        binding.f001KeyDemoResult.text = line
        renderKeyLog()

        if (decision == F001KeyDecision.UNLOCK) {
            renderState()
            CustomToast.show(this, getString(R.string.f001_toast_unlocked))
        }
    }

    private fun toggleLock() {
        val locked = engine.toggle()
        renderState()
        CustomToast.show(
            this,
            getString(if (locked) R.string.f001_toast_locked else R.string.f001_toast_unlocked)
        )
        if (locked) {
            // 锁上后把焦点送到演示区，立刻可以按几下验证拦截
            binding.f001KeyDemoArea.requestFocus()
        }
    }

    private fun renderAll() {
        renderState()
        renderKeyLog()
    }

    private fun renderState() {
        val locked = engine.isLocked

        binding.f001StateValue.setText(
            if (locked) R.string.f001_state_locked else R.string.f001_state_unlocked
        )
        binding.f001StateValue.setTextColor(
            ContextCompat.getColor(
                this,
                if (locked) R.color.theme_error else R.color.theme_success
            )
        )
        binding.f001StateHint.setText(
            if (locked) R.string.f001_state_locked_hint else R.string.f001_state_unlocked_hint
        )
        binding.f001LockSwitchLabel.setText(
            if (locked) R.string.f001_action_unlock else R.string.f001_action_lock
        )
        binding.f001LockCount.text = getString(R.string.f001_lock_count, engine.lockCount)
        binding.f001GestureState.text = getString(
            R.string.f001_demo_gesture,
            getString(
                if (engine.shouldBlockGestures()) R.string.f001_gesture_blocked
                else R.string.f001_gesture_allowed
            )
        )
    }

    private fun renderKeyLog() {
        binding.f001KeyDemoLog.text =
            if (keyLog.isEmpty()) getString(R.string.f001_demo_log_empty)
            else keyLog.joinToString("\n")
    }

    private companion object {
        const val STATE_KEY_LOG = "f001_key_log"

        /** 演示区只留最近几条，避免把布局顶长。 */
        const val MAX_LOG = 6
    }
}
