package io.github.guohangbk.tally.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.guohangbk.tally.data.intake.AutoBillScheme
import io.github.guohangbk.tally.di.rememberAppGraph
import io.github.guohangbk.tally.ui.common.tallyViewModel
import io.github.guohangbk.tally.ui.design.ButtonVariant
import io.github.guohangbk.tally.ui.design.TallyButton
import io.github.guohangbk.tally.ui.design.TallyCard
import io.github.guohangbk.tally.ui.design.TallyToast
import io.github.guohangbk.tally.ui.design.TallyToastEffects
import io.github.guohangbk.tally.ui.design.TallyType
import io.github.guohangbk.tally.ui.design.tallyColors
import io.github.guohangbk.tally.ui.shell.TallyTopBar

/**
 * 自动记账接口：这里只回答三件事——开不开、谁能写、地址怎么填。
 *
 * 采集端（自动记账 App）负责识别支付信息，Tally 只负责把它落成一笔带「自动」角标的账；
 * 分类按名称对齐默认分类树，对不上的落「其它」，账本按名称匹配，匹配不上落默认账本。
 */
@Composable
fun AutoBillSettingsScreen(onBack: () -> Unit) {
    val graph = rememberAppGraph()
    val context = LocalContext.current
    val colors = tallyColors
    val viewModel = tallyViewModel(key = "auto-bill-settings") { SettingsViewModel(graph) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings = state.settings

    fun copyExample() {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText("自动记账地址", AutoBillScheme.EXAMPLE))
        TallyToast.show("地址已复制 · 换成真实金额与分类即可")
    }

    TallyToastEffects(
        notice = state.notice,
        error = state.error,
        errorTitle = "这项设置没有保存",
        onNoticeShown = viewModel::dismissNotice,
        onErrorShown = viewModel::dismissError,
    )

    Column(modifier = Modifier.fillMaxSize().background(colors.paper)) {
        TallyTopBar(title = "自动记账", onBack = onBack)

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 15.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            SettingsSection(title = "总开关") {
                SettingsSwitchRow(
                    checked = settings.autoBillEnabled,
                    onCheckedChange = viewModel::setAutoBillEnabled,
                    label = "自动记账接口",
                    description = if (settings.autoBillEnabled) {
                        "已开启 · 采集端写入后立刻落成一笔账单"
                    } else {
                        "已关闭 · 任何来源都写不进来"
                    },
                )
            }

            Spacer(Modifier.height(10.dp))
            SettingsSection(title = "来源") {
                SettingsSwitchRow(
                    checked = settings.autoBillAllowAnyApp,
                    onCheckedChange = viewModel::setAutoBillAllowAnyApp,
                    label = "允许任意应用写入",
                    description = if (settings.autoBillAllowAnyApp) {
                        "已开启 · 任何应用都能通过 tally://addbill 记一笔，谨慎使用"
                    } else {
                        "已关闭 · 只接受已接入的自动记账 App；其它应用用 tally:// 链接写入时要开这一项"
                    },
                )
            }

            Spacer(Modifier.height(10.dp))
            SettingsSection(title = "接入地址") {
                TallyCard(contentPadding = 12.dp) {
                    Column {
                        Text(
                            text = AutoBillScheme.EXAMPLE,
                            style = TallyType.helper.copy(fontFamily = FontFamily.Monospace),
                            color = colors.ink2,
                        )
                        Spacer(Modifier.height(10.dp))
                        TallyButton(
                            label = "复制示例地址",
                            onClick = { copyExample() },
                            variant = ButtonVariant.GHOST,
                            height = 32,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            SettingsSection(title = "参数") {
                Text(
                    text = "type：0 支出、1 收入\n" +
                        "money：金额（元，最多两位小数）\n" +
                        "time：yyyy-MM-dd HH:mm:ss，可省略\n" +
                        "catename：分类，支持「父/子」或「父/::/子」\n" +
                        "bookname：账本名，找不到时用默认账本\n" +
                        "remark：备注\n" +
                        "id：外部单号，同一单号重复写入会被跳过",
                    style = TallyType.helper,
                    color = colors.ink2,
                )
            }

            Spacer(Modifier.height(10.dp))
            SettingsSection(title = "自动记账 App") {
                Text(
                    text = "在带 Tally 适配器的「自动记账」里把记账软件选成 Tally，规则命中后会直接写入本机；" +
                        "不需要联网，也不经过任何服务器。转账与信用卡还款暂不支持，会在写入时明确提示。",
                    style = TallyType.helper,
                    color = colors.ink2,
                )
            }

            Spacer(Modifier.height(14.dp))
            Text(
                text = "自动记账只写账，不改账：回滚一笔请到账单详情删除。",
                style = TallyType.helper,
                color = colors.ink3,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}
