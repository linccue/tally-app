# 接入「自动记账」（tally://addbill）

## 先把名字对齐

- **AutoRuleSubmit** 是「自动记账」的**规则提交 / BUG 反馈仓库**，仓库里没有采集代码，也没有可调用的接口。它不能"接入"。
- 真正做事的是 **[AutoAccounting](https://github.com/AutoAccountingOrg/AutoAccounting)**：一个 Xposed / LSPatch / 无障碍的账单采集模块，识别微信、支付宝、银行短信与通知，再通过各记账 App 的接口把账单写过去。
- 所以接入 = **Tally 开放写入口 + AutoAccounting 增加一个 Tally 适配器**。两边都已经在本仓库与本地克隆的 AutoAccounting 里改好（见文末清单）。

## 两条写入口

| 通道 | 地址 | 谁能用 | 来源校验 |
| --- | --- | --- | --- |
| ContentProvider（推荐） | `content://io.github.guohangbk.tally.autobill`，`call("addbill", extras)` | 已接入的采集端 | 全版本可用 `getCallingPackage()` |
| URL Scheme | `tally://addbill?...` | 任何自动化工具（Tasker、MacroDroid、快捷指令、adb） | Android 14+ 校验 `getLaunchedFromPackage()`；更早的版本需在设置里打开「允许任意应用」 |

两条通道共用同一份解析与写入逻辑（`data/intake/`），参数完全一致。

## 参数

| 键 | 必填 | 说明 |
| --- | --- | --- |
| `type` | 否 | `0` 支出（默认）、`1` 收入；`2/3`（转账、还款）会被拒绝并说明原因 |
| `money` | 是 | 元，正数，最多两位小数；支持 `29.85`、`￥29.85`、`1,299.00` |
| `time` | 否 | `yyyy-MM-dd HH:mm:ss` / `yyyy-MM-dd HH:mm` / `yyyy-MM-dd` / 10 位秒 / 13 位毫秒；缺省为写入时刻 |
| `catename` | 否 | `父`、`父/子` 或 `父/::/子`；按名称或别名落到 Tally 的分类树 |
| `bookname` | 否 | 账本名；找不到或已归档时落默认账本 |
| `remark` | 否 | 备注，超过 200 字截断 |
| `id` | 否 | 外部单号，幂等键；同一单号重复写入静默跳过 |
| `currency` | 否 | 只接受 `CNY` |

示例：

```
tally://addbill?type=0&money=29.85&time=2026-11-18 12:30:00&remark=午饭&catename=餐饮/::/午餐&bookname=日常账本&id=ankio-1024
```

## 落库后的样子

- `source = AUTO`，账单列表、详情、批量里显示金色「自动」角标；
- 时间带时刻的存 `DATETIME`，只给日期的存 `DATE`（与记账页「账单时间」开关同一把尺）；
- 不计入预算的开关、报销、标签都不由采集端决定——自动账进来就是一笔普通支出/收入，用户可以照常编辑、删除（软删，回收站可恢复）。

## 分类与账本怎么对

采集端的规则沿用的是钱迹那套分类名，不强行造分类，按下面的阶梯匹配：

1. 一级分类**同名**命中；
2. 命中**别名表**（`餐饮→三餐`、`生活缴费→水电煤`、`购物→购物`…，见 `AutoBillIntake.EXPENSE_ALIASES`）；
3. 名字里**包含**某个别名（「餐饮美食」也能落到三餐）；
4. 都不中 → 支出落「其它」，收入落「其它收入」。

二级只在同名且父级一致时挂上，否则只记一级——宁可粗一点，也不要挂错。

## 安全模型

- 默认开接口、**只接受白名单来源**（`net.ankio.auto`，以及用于调试的 `com.android.shell`）。
- ContentProvider 的 `getCallingPackage()` 由系统按 binder uid 填写，**无法伪造**，所以自动记账走这条通道。
- Scheme 通道在 Android 13 及以下拿不到调用方；即使在 14+，部分 ROM（实测 HyperOS / Android 17）对
  `adb shell am start` 也返回 `null`。拿不到来源一律按「未知来源」拒绝，Toast 里直接告诉用户去开
  设置 → 自动记账 → 允许任意应用写入。
- 关掉总开关后两条通道都写不进来。

## 幂等与去重

`bill.external_id` 上有唯一索引；写入前先按 `id` 查一次：

- 已存在（含回收站里的行）→ 静默跳过，采集端重复重试不会多记；
- 采集端不传 `id` → 每次都会新建一笔，重试就可能重复，**建议采集端总是带上稳定的单号**。

## 明确不支持

| 项 | 原因 | 现在的行为 |
| --- | --- | --- |
| 转账、信用卡还款 | Tally 还没有账户与资产模型 | 拒绝并提示「暂不支持转账/还款」 |
| 手续费、优惠 | 同上（没有转账语义） | 忽略；`money` 按采集端给的净额记账 |
| 账户名 | 无账户模型 | 忽略 |
| 多币种 | 固定人民币 | 非 `CNY` 拒绝 |
| 标签 | 采集端的标签名与本机标签不是一套 | 忽略；可在 Tally 里批量补标 |

## 采集端（AutoAccounting）的改动

在 AutoAccounting 仓里新增：

- `app/src/main/java/net/ankio/auto/adapter/TallyAdapter.kt`
- `AppAdapterManager.adapterList()` 追加 `TallyAdapter()`

适配器**不拉起 Activity**，直接 `contentResolver.call(...)`，返回值里 `ok=false` 时不清同步状态并弹提示——宁可让这一笔留在待同步，也不要静默丢掉。AutoAccounting 是 GPL-3.0，改动以 PR 形式回上游即可。

## 本地验证

```bash
# 1. 编译安装（仓库自带测试签名，直接出包）
./gradlew :tally-app:assembleDebug
adb install -r tally-app/build/outputs/apk/debug/tally-app-debug.apk

# 2. 模拟自动记账写入：走 Provider 这条真通道（shell 的 uid 会被 getCallingPackage 认出来）
#    注意 content 的 extras 要用 --extra key:type:value（--arg 只填 call() 的 arg 字符串，进不了 Bundle）
adb shell content call --uri content://io.github.guohangbk.tally.autobill --method addbill \
  --extra type:s:0 --extra money:s:29.85 --extra time:s:"2026-11-18 12:30:00" \
  --extra remark:s:test --extra catename:s:餐饮 --extra id:s:test-001
# 预期：Bundle[{ok=true, message=已自动记账 · 三餐 · ¥29.85, billId=…, amountMinor=2985}]

# 3. 再发一次同样的 id，应当静默跳过（账单仍是 1 笔）
# 预期：Bundle[{ok=true, message=这一笔已经记过了}]
```

Scheme 那条路要用 `adb shell am start -a android.intent.action.VIEW -d "tally://addbill?…"` 验证，
但如上所述 shell 启动的 Intent 在部分 ROM 上 `launchedFromPackage` 为 `null`，会先被拦下——
这是预期行为，先在设置里打开「允许任意应用写入」再测。

## 文件清单

Tally（本仓）：

- `data/intake/AutoBillScheme.kt` —— 参数解析（纯函数，拒绝原因说人话）
- `data/intake/AutoBillIntake.kt` —— 白名单、幂等、分类/账本匹配、落库
- `data/intake/AutoBillProvider.kt` —— `content://…autobill` 通道
- `ui/intake/AutoBillActivity.kt` —— `tally://addbill` 通道（无界面，Toast 结果）
- `ui/settings/AutoBillSettingsScreen.kt` + 设置页入口 + 路由
- `AndroidManifest.xml` —— Activity / Provider 声明
- `domain/model/Enums.kt` —— `BillSource.AUTO`
- `ui/design/BillBadges.kt` —— 「自动」角标

AutoAccounting（另一个仓）：

- `adapter/TallyAdapter.kt`、`adapter/AppAdapterManager.kt`
