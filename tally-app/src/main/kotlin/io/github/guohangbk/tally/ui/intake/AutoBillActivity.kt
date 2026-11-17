package io.github.guohangbk.tally.ui.intake

import android.os.Build
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import io.github.guohangbk.tally.TallyApplication
import io.github.guohangbk.tally.data.intake.AutoBillOutcome
import io.github.guohangbk.tally.ui.widget.InsightWidgets
import io.github.guohangbk.tally.ui.widget.TallyWidgets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * `tally://addbill` 的落点。没有界面：收下参数、写库、弹一句结果、结束自己。
 *
 * 用 Toast 而不是 App 内的气泡，是因为这个 Activity 不属于任何页面——自动记账写入时
 * Tally 很可能在后台，用户看到的只有系统提示这一条。
 *
 * 写入逻辑全在 [io.github.guohangbk.tally.data.intake.AutoBillIntake]，
 * 这里只负责取调用方包名与把结果说给人听。
 */
class AutoBillActivity : ComponentActivity() {
    private var submitted = false

    /**
     * 放在 onStart 而不是 onCreate：`launchedFromPackage` 要等这次启动真正开始之后才可靠，
     * onCreate 里读可能拿到 null，那会把 Scheme 通路误判成「未知来源」。
     */
    override fun onStart() {
        super.onStart()
        if (submitted) return
        submitted = true

        val uri = intent?.data
        // 34+ 才拿得到「是谁把这个 Intent 发过来的」；更早的版本只能当未知来源，
        // 默认被白名单挡掉，需要用户显式打开「允许任意应用」。自动记账走 Provider，不受这条限制。
        val caller = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            launchedFromPackage
        } else {
            null
        }

        lifecycleScope.launch {
            val application = TallyApplication.from(this@AutoBillActivity)
            val outcome = runCatching {
                withContext(Dispatchers.IO) { application.graph.autoBillIntake.submit(uri, caller) }
            }.getOrElse { failure ->
                AutoBillOutcome.Failed(failure.message ?: "这一笔没有记上")
            }

            when (outcome) {
                is AutoBillOutcome.Saved -> {
                    // 桌面组件是只读快照，写完不刷就等于这次没记。
                    TallyWidgets.refresh(this@AutoBillActivity)
                    InsightWidgets.refresh(this@AutoBillActivity)
                    toast(outcome.message)
                }
                is AutoBillOutcome.Skipped -> if (!outcome.silent) toast("未自动记账 · ${outcome.reason}")
                is AutoBillOutcome.Blocked -> toast(outcome.message)
                is AutoBillOutcome.Failed -> toast("未自动记账 · ${outcome.reason}")
            }
            finish()
        }
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
}
