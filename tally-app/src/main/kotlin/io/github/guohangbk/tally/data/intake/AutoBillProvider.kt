package io.github.guohangbk.tally.data.intake

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import io.github.guohangbk.tally.TallyApplication
import io.github.guohangbk.tally.ui.widget.InsightWidgets
import io.github.guohangbk.tally.ui.widget.TallyWidgets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * 给采集端（自动记账）用的稳定写入口，和 `tally://addbill` 是同一套参数，只是换了条通道。
 *
 * 为什么不只用 Scheme：`ContentProvider.call()` 的 [getCallingPackage] 在所有受支持的
 * Android 版本上都拿得到真实包名，而 Activity 要 34+ 才有 `getLaunchedFromPackage()`。
 * 自动账写的是钱，来源能校验比少写一个 Provider 重要。
 *
 * 调用示例（自动记账侧见 TallyAdapter）：
 * ```
 * contentResolver.call(
 *     "content://io.github.guohangbk.tally.autobill".toUri(),
 *     "addbill", null, bundleOf("type" to "0", "money" to "29.85", ...),
 * )
 * ```
 * 返回 Bundle：`ok` 表示这一笔已经处理完（含幂等命中，「之前已经记过」也算完成），
 * `message` 是给人看的一句话，成功时带 `billId`。
 */
class AutoBillProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        if (method != METHOD_ADD_BILL) return reply(ok = false, message = "不支持的方法：$method")
        val context = context ?: return reply(ok = false, message = "Tally 还没准备好")
        val caller = callingPackage
        val application = context.applicationContext as? TallyApplication
            ?: return reply(ok = false, message = "Tally 还没准备好")
        val graph = application.awaitGraph()
            ?: return reply(ok = false, message = "Tally 还在启动，请稍后重试")

        val outcome = runCatching {
            runBlocking {
                withContext(Dispatchers.IO) {
                    graph.autoBillIntake.submit(extras.toAutoBillUri(), caller)
                }
            }
        }.getOrElse { failure ->
            AutoBillOutcome.Failed(failure.message ?: "这一笔没有记上")
        }

        return when (outcome) {
            is AutoBillOutcome.Saved -> {
                // 桌面组件是只读快照：不刷就要等下次打开 App 才看到这一笔。
                TallyWidgets.refresh(context)
                InsightWidgets.refresh(context)
                reply(ok = true, message = outcome.message).apply {
                    putString(EXTRA_BILL_ID, outcome.billId)
                    putLong(EXTRA_AMOUNT_MINOR, outcome.amountMinor)
                }
            }
            is AutoBillOutcome.Skipped -> reply(ok = outcome.duplicate, message = outcome.reason)
            is AutoBillOutcome.Blocked -> reply(ok = false, message = outcome.message)
            is AutoBillOutcome.Failed -> reply(ok = false, message = outcome.reason)
        }
    }

    /**
     * Bundle 的键与 Scheme 的查询参数同名，省得采集端记两套字段。
     *
     * 这里刻意用无类型 [Bundle.get]：采集端可能塞 String、Int 或 Double，
     * 统一 toString() 后再交给 Scheme 解析，比按类型逐个取值更耐错。
     */
    @Suppress("DEPRECATION")
    private fun Bundle?.toAutoBillUri(): Uri? {
        if (this == null) return null
        val builder = Uri.Builder()
            .scheme(AutoBillScheme.SCHEME)
            .authority(AutoBillScheme.HOST)
        keySet().forEach { key ->
            val value = get(key)?.toString() ?: return@forEach
            builder.appendQueryParameter(key, value)
        }
        return builder.build()
    }

    private fun reply(ok: Boolean, message: String): Bundle = Bundle().apply {
        putBoolean(EXTRA_OK, ok)
        putString(EXTRA_MESSAGE, message)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        const val AUTHORITY_SUFFIX = ".autobill"
        const val METHOD_ADD_BILL = "addbill"
        const val EXTRA_OK = "ok"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_BILL_ID = "billId"
        const val EXTRA_AMOUNT_MINOR = "amountMinor"
    }
}
