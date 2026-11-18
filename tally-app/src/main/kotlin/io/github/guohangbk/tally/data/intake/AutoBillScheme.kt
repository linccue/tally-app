package io.github.guohangbk.tally.data.intake

import android.net.Uri
import io.github.guohangbk.tally.domain.model.BillType
import io.github.guohangbk.tally.domain.parseAmount
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 第三方自动记账写入口。
 *
 * 合同只有一条 URI：
 *
 * ```
 * tally://addbill?type=0&money=29.85&time=2026-11-18 12:30:00&remark=午饭
 *      &catename=三餐&bookname=日常账本&id=<外部单号>
 * ```
 *
 * 参数与钱迹 / 一羽那类 Scheme 对齐，采集端（自动记账 App）不用为 Tally 单独记一套规则：
 * - `type`：`0` 支出、`1` 收入；转账与信用卡还款在 Tally 还没有账户模型，直接拒收而不是猜成收支。
 * - `money`：正数十进制，允许两位小数，单位元。
 * - `time`：`yyyy-MM-dd[ HH:mm[:ss]]`，缺省为写入时刻；只给日期时按「日粒度」落库。
 * - `catename`：`父`、`父/子` 或 `父/::/子`；父级按名称/别名映射到 Tally 的默认分类树。
 * - `bookname`：账本名，找不到时落到默认账本。
 * - `id`：外部单号，用于幂等；同一单号重复写入会被静默跳过。
 * - `remark`：备注。
 * - `currency`：只接受 `CNY`。
 *
 * 解析是纯函数，不读偏好、不碰数据库：拒绝原因要说人话，写入与否由 [AutoBillIntake] 决定。
 */
object AutoBillScheme {
    const val SCHEME = "tally"
    const val HOST = "addbill"

    /** 设置页「复制」按钮给出的那条示例，字段都在，方便对方对照调试。 */
    const val EXAMPLE =
        "tally://addbill?type=0&money=29.85&time=2026-11-18 12:30:00&remark=午饭&catename=三餐&bookname=日常账本&id=demo-1"

    private val dateTimeFormats = listOf(
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"),
    )
    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    /** 侧滑/隐式启动都先过这一关，别把别的 tally:// 链接当成记账。 */
    fun matches(uri: Uri?): Boolean =
        uri != null && uri.scheme.equals(SCHEME, ignoreCase = true) &&
            uri.host.equals(HOST, ignoreCase = true)

    fun parse(uri: Uri?): AutoBillParsed {
        if (!matches(uri)) return AutoBillParsed.Rejected("不是自动记账地址")
        val params = uri!!

        val type = when (params.getQueryParameter("type")?.trim()?.lowercase()) {
            null, "", "0", "expense", "支出" -> BillType.EXPENSE
            "1", "income", "收入" -> BillType.INCOME
            "2", "3", "transfer", "转账", "还款", "信用卡还款" ->
                return AutoBillParsed.Rejected("Tally 还没有账户与转账，暂不支持转账/还款")
            else -> return AutoBillParsed.Rejected("type 只能是 0（支出）或 1（收入）")
        }

        val amountMinor = params.getQueryParameter("money")
            ?.let(::parseAmount)
            ?: return AutoBillParsed.Rejected("money 缺失或不是合法金额")
        if (amountMinor <= 0L) return AutoBillParsed.Rejected("金额必须大于 0")

        val currency = params.getQueryParameter("currency")?.trim()?.uppercase()
        if (!currency.isNullOrEmpty() && currency != "CNY") {
            return AutoBillParsed.Rejected("只支持人民币（currency=CNY）")
        }

        val rawTime = params.getQueryParameter("time")?.trim().orEmpty()
        val time = parseTime(rawTime)
        if (time == null) return AutoBillParsed.Rejected("time 格式应为 yyyy-MM-dd HH:mm:ss")

        val (l1, l2) = splitCategory(params.getQueryParameter("catename"))

        return AutoBillParsed.Ok(
            AutoBillRequest(
                type = type,
                amountMinor = amountMinor,
                occurredAt = time.first,
                hasClockTime = time.second,
                note = params.getQueryParameter("remark")?.trim()?.take(200)?.ifEmpty { null },
                categoryL1Name = l1,
                categoryL2Name = l2,
                ledgerName = params.getQueryParameter("bookname")?.trim()?.ifEmpty { null },
                externalId = params.getQueryParameter("id")?.trim()?.ifEmpty { null }
                    ?: params.getQueryParameter("externalId")?.trim()?.ifEmpty { null },
            ),
        )
    }

    /** `父/::/子` 是钱迹的写法，`父/子` 是一羽的；两种都认，其余按一级分类处理。 */
    private fun splitCategory(raw: String?): Pair<String?, String?> {
        val text = raw?.trim()?.ifEmpty { null } ?: return null to null
        val parts = if (text.contains("/::/")) {
            text.split("/::/", limit = 2)
        } else {
            text.split("/", limit = 2)
        }
        return parts.firstOrNull()?.trim()?.ifEmpty { null } to
            parts.getOrNull(1)?.trim()?.ifEmpty { null }
    }

    /** 返回 (毫秒时间戳, 是否带时刻)。没给时间就是「现在」，并且是带时刻的。 */
    private fun parseTime(raw: String): Pair<Long, Boolean>? {
        val zone = ZoneId.systemDefault()
        if (raw.isEmpty()) return System.currentTimeMillis() to true

        if (raw.all(Char::isDigit)) {
            val value = raw.toLongOrNull() ?: return null
            // 10 位是秒，13 位是毫秒；再短就不像时间戳了，宁可拒绝也不猜。
            val millis = when (raw.length) {
                13 -> value
                10 -> value * 1000L
                else -> return null
            }
            return millis to true
        }
        dateTimeFormats.forEach { format ->
            runCatching { LocalDateTime.parse(raw, format) }
                .onSuccess { return it.atZone(zone).toInstant().toEpochMilli() to true }
        }
        runCatching { LocalDate.parse(raw, dateFormat) }
            .onSuccess { return it.atStartOfDay(zone).toInstant().toEpochMilli() to false }
        return null
    }
}

data class AutoBillRequest(
    val type: BillType,
    val amountMinor: Long,
    val occurredAt: Long,
    /** 带时刻的按 DATETIME 落库；只给日期的按 DATE，与记账页「账单时间」开关同口径。 */
    val hasClockTime: Boolean,
    val note: String?,
    val categoryL1Name: String?,
    val categoryL2Name: String?,
    val ledgerName: String?,
    val externalId: String?,
)

sealed interface AutoBillParsed {
    data class Ok(val request: AutoBillRequest) : AutoBillParsed

    /** 拒绝原因直接进 Toast，必须是一句能看懂的话。 */
    data class Rejected(val reason: String) : AutoBillParsed
}
