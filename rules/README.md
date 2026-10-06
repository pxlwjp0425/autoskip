# GKD + 李跳跳 规则转换包

把 GKD 订阅（json5）和李跳跳规则（json）自动转成快跳能吃的规则格式，然后合并成一份订阅。

---

## 授权说明（先读这条）

**本目录的规则数据不随仓库分发。** `gkd-compat.json`（转换产物）和 `_src/`（上游原文件）
都在 `.gitignore` 里，clone 下来是空的，需要你自己按下文流程生成。

原因 —— 这些数据派生自第三方订阅，而它们的授权状况是：

| 上游 | 许可证 | 额外声明 |
| --- | --- | --- |
| `AIsouler/GKD_subscription`（12k⭐） | **无许可证** | README 明写「禁止在国内平台传播」「本仓库仅供本人学习使用」 |
| `wtwang1998/LiTiaotiao-Custom-Rules`（10.2k⭐） | **无许可证** | 无 |
| `Adpro-Team/GKD_subscription`（1.9k⭐） | SATA 2.0（基于 MIT） | 需保留版权与项目地址、致谢、给项目点 star |
| `gkd-kit/gkd`（GKD App 本体） | GPL-3.0 | — |

无许可证 = 保留所有权利，他人无权复制、分发、演绎，**由它派生的规则文件同理**。

所以：

- **自己转换、自己用** —— 没问题，本文档下面全套流程都可以照跑。
- **想公开分享规则** —— 只挑许可证允许的上游（如 Adpro 的 SATA/MIT），并按它的要求署名致谢。
- 转换脚本 `../tools/*.py` 是**本项目原创代码**，不受上述限制，随仓库正常分发。
- 本仓库的 Java 源码不含 GKD 的任何代码，**不存在 GPL-3.0 传染**（详见主 README 第十三节）。

> 下表里标 **⛔ 不入库** 的条目，就是需要你自己生成或从上游获取的。

---

| 文件 | 说明 |
|---|---|
| ⛔ `merged_gkd.json5` | **不入库**<br>旧源订阅：甘霖 + AIsouler + Adpro 合并版（2.7 MB / 965 个应用）<br>**⚠️ 已作废**：合并脚本把 `rules` 的字符串简写当列表遍历，965 个应用里 **392 个被逐字符拆坏**，不再作为转换输入 |
| ⛔ `_src/AIsouler_gkd.json5` | **不入库**<br>✅ 干净源：从 npmmirror 重新下载的 AIsouler 订阅 |
| ⛔ `_src/Adpro_gkd.json5` | **不入库**<br>✅ 干净源：从 npmmirror 重新下载的 Adpro 订阅 |
| ⛔ `_src/LTT_Snoopy.json` | **不入库**<br>✅ 李跳跳源：`AllRules.json`（328 应用 / 787 条） |
| ⛔ `_src/LTT_user.txt` | **不入库**<br>✅ 李跳跳源：设备侧导出的规则快照（多段拼接，贡献 23 条增量） |
| ⛔ `gkd-compat.json` | **不入库**<br>**最终产物，直接拿去当快跳的订阅地址**（685 KB / 923 应用 / 2938 条规则，其中 796 条带 `anchor`） |
| `../tools/gkd2skip.py` | GKD → 快跳 转换脚本 |
| `../tools/ltt2skip.py` | 李跳跳 → 快跳 转换脚本（含 hashCode 反查、多段拼接容错） |
| `../tools/merge_compat.py` | 把多份转换产物合并成一份（**并集**，只增不减） |
| `../tools/ltt_verify.py` | 离线回放真机节点树，审计规则会不会误点 |
| `coverage-report.md` | 实机覆盖率普查报告 |

---

## 转换结果（2026-10-05 修复后重跑）

| 指标 | 数值 |
|---|---|
| 源规则总数（AIsouler） | 3217 条 |
| 成功转换 | **1971 条（61.3%）** |
| 输出规则 | 1888 条，覆盖 **721 个应用** |
| 带界面限定 | 88.9%（`activityIds` 非空，这是防误触的关键） |

**与李跳跳产物并集合并后**：**923 应用 / 2938 条规则**，其中 796 条是
「`anchor` 前提 + 点击目标」式 —— 这部分只有李跳跳那条链路能产出。

> **注意**：老报告里那个「11585 条源规则 / 成功率 19%」是**假数字**。
> 分母之所以膨胀到 11585，正是因为合并脚本把 `matches` 拆成了单字符——
> 每一个字符都被当成一条"规则"。真实转换率一直是 **61% 左右**。

### 已修的两个转换链路 bug

1. **`rules` 简写没处理**（`gkd2skip.py: norm_rules()`）
   GKD 的 `rules` 可以是 list / dict / **str** 三种写法，旧版遇到后两种会把字符串当列表遍历 →
   逐字符拆开。这是 `merged_gkd.json5` 被污染的根源。
2. **短 ID 连坐**（`gkd2skip.py: convert_rule()`）
   旧逻辑「无 activityIds 的 ID 规则要求所有 ID ≥8 字符」用的是 `all(...)`，
   于是 12306 的 `[vid="tv_main_splash_skip" || vid="tv_skip"]` 被 `tv_skip`（7 字符）拖死。
   现在改成**只剔除过短的 ID**，并额外放行 `skip/close/btn/button/exit/dismiss/cancel`
   结尾的短 ID（规则本身按包名隔离，不存在跨应用误匹配）。

## 为什么只有 19%？

GKD 的选择器是**结构化查询语言**，快跳只有「文本 / 描述 / 资源 ID」三个匹配维度。剩下 81% 长这样：

```js
// 没有任何文字或 ID，纯靠结构和属性定位 —— 快跳表达不了
ViewFactoryHolder FrameLayout[childCount=5] > FrameLayout[childCount=1]
  > ImageView[childCount=0][id=null][desc=null][width<60 && height<60]
```

这类规则占 **9167 条（79%）**。硬转只能丢掉所有约束、退化成"点这个位置的图"，那是灾难性的误触源，所以**一律丢弃**。

另外丢弃：关键词过于宽泛 74 条、无语义 64 条、不支持的动作（长按/滑动）5 条。

**结论：留下来的 19% 是 GKD 规则里最"硬"的那部分** —— 有明确文字或控件 ID，改版后依然能打。

---

## 转换规则（怎么保证不误点）

1. **精确匹配完整复刻**：GKD 的 `text="跳过"`（精确）→ 快跳正则 `^跳过$`；
   `text*="跳过"`（包含）→ `跳过`；`text^="跳过"`（前缀）→ `^跳过`。
   快跳的 `regex` 用的是 `find()`，所以 `^…$` 就是精确匹配。
2. **系统通用 ID 拉黑**：`android:id/content` 这类几乎所有 App 都有的根容器 ID 直接丢弃，
   否则会在任何界面疯狂误点。
3. **短 ID 锚定**：`[vid="arg"]` 不转成"包含 arg"（会命中 `large_button`），
   而是转成正则 `/arg$`，只匹配资源名末尾。
4. **泛词必须限定界面**：「关闭」「取消」「X」这类词，没有 `activityIds` 一律丢弃。
5. **无界面限定的规则单屏只点一次**（`maxClick: 1`），把误触概率压到最低。
6. **层级约束丢失后的补偿**：GKD 里 `A > [text="X"]` 的层级关系快跳无法表达，
   只会退化成按 `X` 匹配 —— 这类规则靠 `activityIds` 兜底。
7. **只从「点击目标」那一段取关键词**（v1.2.4 起）。GKD 选择器是多段拼的，
   **只有 `@` 标记的那段是点击目标**；没有 `@` 时按搜索方向推断：
   `A > B` 目标在右（B），而 `A <<n B` 目标是**左边**的 A（GKD 自顶向下搜索）。
   祖先 / 兄弟段落里的 `vid` 只是定位约束，拿来当点击目标就会点中整个容器。
8. **容器型资源 ID 不当点击目标**：以 `recycler` / `list` / `layout` / `container` /
   `pager` / `grid` / `scroll` 结尾的 ID 一律排除（带 `close` / `btn` / `skip`
   等动作词的放行）。
9. **内容 / 导航词不转**：`推荐`、`广告`、`荐读`、`关注`、`查看详情` 这类词
   在任何 App 里都是内容标签或入口，GKD 靠 `preKeys` 多步流程才安全，
   快跳只有单步点击，转过来必然乱跳。

### 实际踩过的坑：微博「一进详情页就自己乱跳转」

```js
RelativeLayout >7 FrameLayout > @[name$="FrameLayout" || name$="ImageView"]
  [clickable=true][childCount<2][width<50&&height<50] <<n [vid="view_recycler" || vid="tweet_list"]
```

真要点的目标是 `@` 那个 **小于 50×50 的小图标**；`vid="view_recycler"` 只是 `<<n`
（祖先）上下文约束。老版本把它当成点击目标 → 「点整个信息流列表」。
而 `view_recycler` 实测 `bounds=[0,240][1080,2269]`，**占屏 84.5%**，
点它的正中心正好落在某条微博上 —— 现象就是「开屏跳得挺好，一进详情页就乱跳」。

现在只从 `@` 段取目标（那段是纯属性断言、没有 id/text，于是被正确丢弃），问题消失。
另有一道运行期护栏：命中节点占屏 >40% 一律不点（见工程 README 第一节）。

### 误触实测

用 65 个常见 UI 文本对 130 条"无界面限定"的规则做暴力匹配测试：

| 文本 | 命中规则数 |
|---|---|
| 跳过 | 127 |
| 跳过广告 | 113 |
| 关闭广告 | 2 |
| **取消 / 确定 / 设置 / 我的 / 首页 / 关闭 / 登录 / 以后再说 / 我知道了 …（其余 62 个）** | **0** |

只有广告关闭类按钮会被命中，常规操作按钮一个都不碰。

---

## 李跳跳规则转换

除了 GKD，社区里体量最大的另一套现成规则是**李跳跳**。它和 GKD 同源（GKD 就是从李跳跳这条线发展出来的），
但写法不同，需要单独一条转换链路：`tools/ltt2skip.py`。

### 上游从哪来

- 主源：`wtwang1998/LiTiaotiao-Custom-Rules`（原 `Snoopy1866/...`，1 万星，**默认分支是 `rm` 不是 `main`**，
  按 `@main` 拉会拿到一份 328 条的残旧版）。规则文件 `AllRules.json`。
- 该仓库 README 已明确声明**不再更新应用内广告 / 弹窗规则**，所以它的内容基本停在某个时间点。
  也正因如此它更适合当「补充库」，而不是唯一来源。

### 文件形态

```json
[ {"<包名.hashCode()>": "{\"popup_rules\":[{\"id\":\"青少年模式\",\"action\":\"我知道了\"}]}"} , ... ]
```

两个坑：

1. key 是 Java `String.hashCode()`（**可为负，且不可逆**）→ 必须拿一份包名清单反算回来。
   脚本按「上游 AppList.md → `_src/pkg_pool.txt`」的顺序合并包名池，池子越大还原率越高。
   当前 1863 个包名，能还原 329/333 个应用。
2. 从各处收集来的规则文件经常是**多段数组首尾直接拼接**（段间没有逗号），
   严格 `json.loads` 会报 `Extra data` → 脚本用 `raw_decode` 扫描式解析。

### 语义映射（关键，也是快跳为此加了新能力）

李跳跳的一条规则是**两个控件**，不是一条文案：

| 字段 | 含义 |
|---|---|
| `id` | 弹窗的**识别特征**（出现它，说明弹窗在） |
| `action` | 要**点击**的按钮 |

也就是「出现 A 时点击 B」。而快跳原来的规则只有「匹配到什么就点什么」，
直接拿 `action` 去点会出事 —— `action` 里最高频的是 `取消`(38)、`我知道了`(37)、
`关闭`(26)，这些满屏都是，单独匹配必然误点。

为此给快跳加了 **`anchor`（前提条件）** 字段，规则变成「anchor 成立，才允许点击 matches 命中的节点」：

```json
{ "anchor": ["打开通知权限"], "matches": ["再考虑下"], "matchType": "any" }
```

`matchType="any"` 表示 text / desc / resourceId 三者任一命中即可，这跟李跳跳的匹配口径一致。

### 语法：`&` 与 `|`

- `&` = 与（同时包含），`|` = 或（任一满足），**AND 优先级高于 OR**：`A&B|C` = (A 且 B) 或 C
- `|` 拆出来的空组（如 `"| 跳过"`）直接忽略
- 统计过：`&` **只出现在 `id` 侧**、`action` 侧干净，所以 anchor 原样透传给引擎解析，
  matches 则把 `|` 拆成多项（快跳的 matches 数组本身就是「或」）
- 「与项」的判定是「树里存在某个节点包含它」，不是「同一个节点同时包含全部」——
  上游用 `&` 描述的是「弹窗里同时出现了这几段文字」，同一节点并不一定共存

### 转换结果（2026-10-05）

| 指标 | 数值 |
|---|---|
| 上游规则条数 | 805 |
| 成功转换 | **797**（丢弃 8 条：anchor 短于 2 字符，失去「前提」意义） |
| 覆盖应用 | 328 |

产出在 `rules/_src/from_LTT.json`，再经 `tools/merge_compat.py` 与 GKD 产物**并集**合并，
最终订阅从 748 应用 / 2142 规则涨到 **923 应用 / 2938 规则**（其中 796 条是李跳跳式）。

### 合并进来的第二份李跳跳源

用户提供过一份从设备侧导出的 `ltt_2986.txt`（2964 条目 / 884 个 hashCode），
去重后与上面那份高度重合（交集 325/333），但**多出 23 条规则**，已并入。
这类文件往往是多份快照拼接而成，同一应用会重复出现，脚本按 `(id, action, times)` 去重。

### 转换后怎么验

```bash
python tools/ltt2skip.py                    # 生成 _src/from_LTT.json
python tools/merge_compat.py rules/gkd-compat.json rules/_src/prev_compat.json \
       rules/_src/from_AIsouler.json rules/_src/from_Adpro.json rules/_src/from_LTT.json
python tools/ltt_verify.py <真机dump目录>    # 离线回放，查误点
```

`tools/ltt_verify.py` 是**安全审计**用的：拿快跳诊断模式抓下来的真实节点树，
按引擎同样的判定顺序（activityIds → matches → anchor → 40% 面积闸）重跑一遍。
正常界面（没弹窗）上不该有任何规则命中，命中就是误点。

---

## 怎么用

1. 把 `gkd-compat.json` 放到任意能通过 HTTP 访问的地方（对象存储、你自己的服务器、局域网 HTTP 服务）。
   > ⛔ **别放进公开仓库或公开分享** —— 授权原因见本文档开头的「授权说明」。
   > 纯自用也可以不架 HTTP，直接走 App 的「导入本地规则」。
2. 快跳 App → 规则订阅 → 粘贴地址 → 「添加订阅」→「更新全部规则」。
3. 拉取成功后缓存在本地，**断网照样工作**。

## 规则更新了怎么办

**不要再拿 `merged_gkd.json5` 当输入**（它已经被拆坏）。直接对干净源跑：

```bash
# 需要 Python 3 + json5 库
pip install json5

# 1) 拉最新干净源（两个订阅都已停止维护，地址仍可用）
curl -sL -o rules/_src/AIsouler_gkd.json5 \
  https://registry.npmmirror.com/@aisouler/gkd_subscription/latest/files/dist/AIsouler_gkd.json5
curl -sL -o rules/_src/Adpro_gkd.json5 \
  https://registry.npmmirror.com/@adpro/gkd_subscription/latest/files/dist/Adpro_gkd.json5

# 2) 各自转换
python tools/gkd2skip.py rules/_src/AIsouler_gkd.json5 rules/_src/from_AIsouler.json
python tools/gkd2skip.py rules/_src/Adpro_gkd.json5    rules/_src/from_Adpro.json

# 3) 并集合并（把现行产物一起并进去 = 只增不减）
python tools/merge_compat.py rules/gkd-compat.json \
  rules/_src/prev_compat.json rules/_src/from_AIsouler.json rules/_src/from_Adpro.json
```

合并前记得先 `cp rules/gkd-compat.json rules/_src/prev_compat.json` 留一份旧版做并集。

跑完把 `gkd-compat.json` 放到原来的订阅地址，快跳里点一次「更新全部规则」就生效。

**正则改完一定要用真实 JVM 验一遍**（快跳的 `regex` 就是 `java.util.regex` 的 `find()`）：

```bash
# 把规则里所有 regex=true 的 matches 抽出来一行一条，然后：
javac RegexCheck.java && java RegexCheck pats.txt
# 期望输出：TOTAL=1573 OK=1573 BAD=0
```

---

## 已知局限

- **层级、索引、兄弟节点关系无法表达**，这是 72% 规则被丢弃的根本原因。
- **正则是 Java 语法**。已用 JDK 的 `java.util.regex` 逐个编译验证过（2243 个全部通过），
  但如果 GKD 上游用了 Java 特有语法（如 `\p{IsHan}`），Python 侧无法预览效果。
- **GKD 侧的 `preKeys`（前置条件）仍未转换**，但**引擎已经支持了**这种表达 ——
  v1.3.4 新增的 `anchor` 字段就是干这个的，李跳跳那条链路已经在用它。
  GKD 的 `preKeys` 没接进来是因为语义更复杂（支持选择器链、`<<n` 回退），需要单独设计映射。
  目前这类规则里的「内容 / 导航词」（`推荐`、`广告` 等）仍整体拉黑（第 9 条），
  纯按钮文字的会退化成"看到就点"，可能略微提前触发。
- **GKD 的 `resetMatch` / `matchTime`（时间窗）语义与快跳的 `cooldown` 不完全等价**，
  这里用的是近似映射（`actionCd` → `cooldown`，默认 1000 ms）。
- **自用可以，别传播**。这些规则的版权归甘霖 / AIsouler / Adpro 等原作者。
