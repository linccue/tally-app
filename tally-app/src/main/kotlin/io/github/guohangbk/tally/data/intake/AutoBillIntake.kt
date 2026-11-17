package io.github.guohangbk.tally.data.intake

import android.net.Uri
import io.github.guohangbk.tally.data.db.dao.CategoryDao
import io.github.guohangbk.tally.data.db.dao.LedgerDao
import io.github.guohangbk.tally.data.db.entity.CategoryEntity
import io.github.guohangbk.tally.data.preferences.TallyPreferences
import io.github.guohangbk.tally.data.repository.BillRepository
import io.github.guohangbk.tally.data.repository.LedgerRepository
import io.github.guohangbk.tally.domain.formatAmount
import io.github.guohangbk.tally.domain.model.BillDraft
import io.github.guohangbk.tally.domain.model.BillSource
import io.github.guohangbk.tally.domain.model.BillType
import io.github.guohangbk.tally.domain.model.CategoryType
import io.github.guohangbk.tally.domain.model.TallyError
import io.github.guohangbk.tally.domain.model.TimePrecision

/**
 * 把 `tally://addbill` 落成一笔真实账单。
 *
 * 三层判断，顺序不能换：
 * 1. 开关关着就一句话也不写（用户明确不要这条通路）；
 * 2. 来源不在白名单里就拦下并说明是谁在写（自动记账走 ContentProvider，能拿到调用方包名）；
 * 3. 同一个外部单号已经落过库就静默跳过——采集端重试是常态，重复提示只会变成噪音。
 *
 * 分类与账本都按名字匹配，匹配不上才回落：账本 → 默认账本；分类 → 「其它」/「其它收入」。
 * 不新建分类：自动写入若顺手造出一堆「餐饮」「购物」镜像分类，分类树几天就废了。
 */
class AutoBillIntake(
    private val ledgerRepository: LedgerRepository,
    private val billRepository: BillRepository,
    private val ledgerDao: LedgerDao,
    private val categoryDao: CategoryDao,
    private val preferences: TallyPreferences,
) {
    /**
     * @param callerPackage 调用方包名。ContentProvider 能给到全版本，Activity/Scheme 只在 34+ 拿得到，
     *   拿不到时按「未知来源」处理，只有在允许任意应用时才放行。
     */
    suspend fun submit(uri: Uri?, callerPackage: String?): AutoBillOutcome {
        if (!preferences.autoBillEnabled()) {
            return AutoBillOutcome.Skipped("自动记账接口已关闭", silent = true)
        }
        val allowAny = preferences.autoBillAllowAnyApp()
        if (!allowAny && !AutoBillCallers.isTrusted(callerPackage)) {
            return AutoBillOutcome.Blocked(callerPackage)
        }

        return when (val parsed = AutoBillScheme.parse(uri)) {
            is AutoBillParsed.Rejected -> AutoBillOutcome.Failed(parsed.reason)
            is AutoBillParsed.Ok -> write(parsed.request)
        }
    }

    private suspend fun write(request: AutoBillRequest): AutoBillOutcome {
        request.externalId?.let { externalId ->
            if (billRepository.existsByExternalIdAny(externalId)) {
                return AutoBillOutcome.Skipped("这一笔已经记过了", silent = true, duplicate = true)
            }
        }

        val ledger = request.ledgerName
            ?.let { name -> ledgerDao.findByName(name)?.takeUnless { it.isArchived } }
            ?: ledgerDao.findDefault()
            ?: runCatching { ledgerRepository.bootstrap() }.getOrNull()
            ?: return AutoBillOutcome.Failed("没有可用账本")

        val categoryType = if (request.type == BillType.INCOME) CategoryType.INCOME else CategoryType.EXPENSE
        val active = categoryDao.findActive(categoryType.name)
        val l1 = resolveLevel1(active, request.categoryL1Name, categoryType)
            ?: return AutoBillOutcome.Failed("没有可用分类")
        val l2 = request.categoryL2Name?.let { name ->
            active.firstOrNull { it.parentId == l1.id && it.name == name }
        }

        val draft = BillDraft(
            ledgerId = ledger.id,
            categoryL1Id = l1.id,
            categoryL2Id = l2?.id,
            type = request.type,
            amountMinor = request.amountMinor,
            occurredAt = request.occurredAt,
            timePrecision = if (request.hasClockTime) TimePrecision.DATETIME else TimePrecision.DATE,
            note = request.note,
            source = BillSource.AUTO,
            externalId = request.externalId,
        )

        return try {
            val billId = billRepository.save(draft)
            AutoBillOutcome.Saved(
                billId = billId,
                ledgerName = ledger.name,
                categoryPath = listOfNotNull(l1.name, l2?.name).joinToString(" · "),
                amountMinor = request.amountMinor,
            )
        } catch (tallyError: TallyError) {
            AutoBillOutcome.Failed(tallyError.message ?: "这一笔没有记上")
        } catch (failure: Exception) {
            AutoBillOutcome.Failed(failure.message ?: "这一笔没有记上")
        }
    }

    /**
     * 一级分类的匹配阶梯：原名 → 别名 → 「名字里含别名」（「餐饮美食」这类）→ 兜底分类。
     * 兜底永远是同一枚，自动账不会因为一次没匹配上就散进不同分类。
     */
    private fun resolveLevel1(
        active: List<CategoryEntity>,
        raw: String?,
        type: CategoryType,
    ): CategoryEntity? {
        val parents = active.filter { it.parentId == null }
        if (parents.isEmpty()) return null
        val name = raw?.trim()?.ifEmpty { null }
        if (name != null) {
            parents.firstOrNull { it.name == name }?.let { return it }
            val aliases = if (type == CategoryType.INCOME) INCOME_ALIASES else EXPENSE_ALIASES
            aliases[name]?.let { mapped -> parents.firstOrNull { it.name == mapped }?.let { return it } }
            aliases.entries
                .sortedByDescending { it.key.length }
                .firstOrNull { name.contains(it.key) }
                ?.let { hit -> parents.firstOrNull { it.name == hit.value }?.let { return it } }
        }
        val fallbackName = if (type == CategoryType.INCOME) "其它收入" else "其它"
        return parents.firstOrNull { it.name == fallbackName } ?: parents.first()
    }

    private companion object {
        /**
         * 采集端（自动记账）的规则沿用的是钱迹那套分类名，与 Tally 的默认树并不一一对应。
         * 这里只做「名字 → Tally 默认分类」的桥，不做语义猜测：桥不到的落「其它」。
         */
        val EXPENSE_ALIASES = mapOf(
            "餐饮" to "三餐",
            "吃饭" to "三餐",
            "美食" to "三餐",
            "早餐" to "三餐",
            "午餐" to "三餐",
            "晚餐" to "三餐",
            "夜宵" to "三餐",
            "外卖" to "三餐",
            "出行" to "交通",
            "打车" to "交通",
            "购物" to "购物",
            "超市" to "日用品",
            "日用" to "日用品",
            "零食" to "零食",
            "饮料" to "零食",
            "娱乐" to "娱乐",
            "休闲" to "娱乐",
            "医疗" to "医疗",
            "健康" to "医疗",
            "药店" to "医疗",
            "水电煤" to "水电煤",
            "生活缴费" to "水电煤",
            "缴费" to "水电煤",
            "电器" to "电器数码",
            "数码" to "电器数码",
            "保险" to "保险",
            "住房" to "住房",
            "房租" to "住房",
            "物业" to "住房",
            "请客送礼" to "请客送礼",
            "礼品" to "请客送礼",
            "美妆" to "美妆",
            "化妆" to "美妆",
            "红包" to "发红包",
            "话费" to "话费网费",
            "网费" to "话费网费",
            "通讯" to "话费网费",
            "聚会" to "聚会",
            "衣服" to "衣服",
            "服饰" to "衣服",
            "学习" to "学习",
            "教育" to "学习",
            "家居家电" to "家居家电",
            "爱车" to "爱车",
            "汽车" to "爱车",
            "生活服务" to "生活服务",
            "人情社交" to "人情社交",
            "其它" to "其它",
            "其他" to "其它",
        )

        val INCOME_ALIASES = mapOf(
            "工资" to "工资",
            "薪资" to "工资",
            "薪水" to "工资",
            "奖金" to "奖金补贴",
            "补贴" to "奖金补贴",
            "报销" to "报销",
            "兼职" to "兼职",
            "理财" to "理财",
            "投资收益" to "理财",
            "利息" to "理财",
            "其它收入" to "其它收入",
            "其他收入" to "其它收入",
            "退款" to "其它收入",
            "红包" to "其它收入",
        )
    }
}

/** 写入结果；文案在数据层定好，调用方（Activity / Provider）只负责显示。 */
sealed interface AutoBillOutcome {
    data class Saved(
        val billId: String,
        val ledgerName: String,
        val categoryPath: String,
        val amountMinor: Long,
    ) : AutoBillOutcome {
        val message: String get() = "已自动记账 · $categoryPath · ¥${formatAmount(amountMinor)}"
    }

    /** [silent] 的是「不用告诉用户」的那几种：接口关着、这一笔已经记过。 */
    data class Skipped(
        val reason: String,
        val silent: Boolean = false,
        /** 幂等命中：账已经在库里了。采集端应视作成功，否则会一直重试。 */
        val duplicate: Boolean = false,
    ) : AutoBillOutcome

    data class Blocked(val callerPackage: String?) : AutoBillOutcome {
        val message: String
            get() = "已拦截自动记账写入 · 来源 ${callerPackage ?: "未知应用"} 未接入"
    }

    data class Failed(val reason: String) : AutoBillOutcome
}

/**
 * 已接入的采集端。
 *
 * `net.ankio.auto` 是「自动记账」App；它通过 [AutoBillProvider] 写入，包名由 ContentProvider
 * 的 `getCallingPackage()` 给出，无法伪造。Scheme 那条路在 Android 14 以下拿不到调用方，
 * 因此默认不放行，需要用户在设置里打开「允许任意应用」。
 */
object AutoBillCallers {
    /**
     * `com.android.shell` 也在里面：`adb shell am start … tally://addbill` 是排查这条通路最直接的手段，
     * 而能拿到 adb 的人本来就能直接改库。
     */
    private val TRUSTED = setOf("net.ankio.auto", "com.android.shell")

    fun isTrusted(packageName: String?): Boolean = packageName != null && packageName in TRUSTED
}
