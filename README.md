# AutoTranslation —— 给"没有中文"的模组自动补汉化

一个 **NeoForge 26.x（Minecraft 26.x）** 客户端模组：扫描 `mods/` 里所有模组的语言文件，找出**没有 `zh_cn.json` 的命名空间**，先用 **CFPA 社区汉化库** 覆盖，剩下的缺口用 **大模型** 补——没填 API Key 时自动改用**内置离线词典**。最后以**普通资源包**注入游戏，几秒后自动重载成中文。

> 目标环境：Minecraft 26.x + NeoForge 26.x.，Java 25

## 下载与安装

1. 到本仓库右侧的 **Releases** 页面下载 `AI-autoTranslation-1.0.0+mc26.3-neoforge.jar`
2. 放进 `<游戏目录>/versions/<版本>/mods/`
3. 启动游戏；模组列表里能看到 **AI-autoTranslation / 自动汉化**
4. 想用大模型翻译：模组列表 → Config → 填接口地址与 Key → 保存并翻译
5. 不填也能用：CFPA 社区汉化 + 内置离线词典会先补一部分

> 这是纯客户端模组，装到专用服务器上会被 FML 跳过。

---

## 1. 它解决什么问题

以最初的例子为例：

| 文件 | `assets/xaeroworldmap/lang/` 内容 |
|---|---|
| `xaeroworldmap-1.47.0.jar` | `de_de`, `en_us`, **`zh_cn`** |
| `xaeroworldmap-1.47.1.jar` | `de_de`, `en_us` —— 没有中文 |

同一个模组，新版本反而丢了中文；`sodium`、`trender`、`mezz_config` 这类库也常常只有 `en_us`。传统做法是等汉化组更新资源包，或者自己解包改 JSON。

本项目把这件事自动化：

```
扫描 mods/*.jar（含 JarJar 嵌套 jar）
   → 找缺 zh_cn 的命名空间
   → 逐 key 取 CFPA 社区译文（有就用，质量最高）
   → 剩余 key 交给翻译后端：填了 Key 用大模型，没填用内置离线词典
   → 生成资源包 → 注册为内置资源包 → 自动重载
```

**只补缺，不覆盖**：模组自带的中文永远优先；没有中文就整包翻，有部分中文就只补缺（`fillMissingKeys`，默认开）。资源包里也只写"补出来的 key"，其余字符串继续走模组自带的 `en_us` / 中文兜底。

每次运行还会做四件事：把用到的 CFPA 短词条**学进内置词典**（`glossary-learned.json`）、把译文写进**翻译记忆**（`memory.json`，相同英文串跨模组复用）、统计每个模组的官方/CFPA/记忆/AI 占比、并支持在游戏内对单个模组**单独重译**。

### 运行生命周期：每次启动只跑一次

模组内部是一个只在本次 JVM 里存在的状态机：

```
WAITING ──(首次资源加载完成)──> RUNNING ──(成功)──> DONE
                                      └──(三次都失败)──> FAILED
DONE / FAILED ──(配置界面「保存并翻译」/「仅重新扫描」)──> RUNNING
```

- **重复启动先判断有没有活干**：比对 `run-state.json` 里的指纹（mods 列表/大小/修改时间 + 关键配置 + 资源包状态），一致且 CFPA 未到 24h 检查点 → 直接休眠，连扫描、词典加载、联网都不做
- `mods/` **只扫描一次**，就在进入 `RUNNING` 的这一刻；之后任何资源重载（包括翻译完成后自己触发的重载、手动 F3+T、改视频设置）都不会再触发扫描
- 进入 `DONE`/`FAILED` 后本次游戏内不再有任何自动活动，日志打印
  `AutoTranslation is now dormant until the next launch or a manual reload`
- 只有重启游戏（新 JVM）状态才会回到 `WAITING`；**唯一的例外是配置界面里的手动按钮**
- 资源包注册在每次重载时仍会执行——这是必须的，否则 F3+T 之后汉化会消失；但它只构造几个内存对象，不读 mods、不联网、不写盘
- 想彻底关掉：`"enabled": false`（不扫描、不联网，也不注册资源包）

### 失败重试：最多 3 次

一次「运行」= 最多 3 轮尝试，轮与轮之间退避 **10 s、20 s**：

| 情况 | 判定 | 行为 |
|---|---|---|
| 第 N 轮抛异常（网络、API 报错、文件写失败…） | 失败 | 等 10 s / 20 s 再试 |
| 第 N 轮跑完但**一条都没翻出来**，且仍有缺口（`pending > 0`） | 失败 | 同上 |
| 缺口为 0，或没配 AI 后端（`pending > 0` 但本来就没 Key） | 成功/无需重试 | 直接结束 |
| 第 3 轮仍失败 | 放弃 | 状态 `FAILED`，日志记录原因，**本次游戏不再自动重试**，下次启动重新来 |

这样断网、API 临时 5xx 都能自愈；同时不会因为「部分成功」而反复重试——已经翻译的 key 进了缓存，下一轮只会补缺口。

### 配置界面：直接填 API，不用改 JSON

模组列表里点本模组的 **Config** 会打开 `AutoTranslation · 自动汉化`：

```
              AutoTranslation · 自动汉化
   上次结果：432 条（CFPA 288 + AI 144），2 个模组
接口地址（OpenAI 兼容，填完整的 chat/completions URL）
[ https://api.deepseek.com/v1/chat/completions        ]
API Key
[ sk-****...****abcd                                   ]
模型
[ deepseek-chat                                        ]
[ 保存并翻译 ]  [ 仅重新扫描 ]  [   完成   ]
设置会写入 config/auto-translation/config.json
```

- **接口地址 / API Key / 模型**：打开时自动带入当前值；Key 用 `*` 遮挡显示（只影响渲染，保存的是原值）
- **保存并翻译**：校验地址必须以 `http://` 或 `https://` 开头 → 写回 `config.json` → 自动把 `provider` 设为 `openai` → 立即开始一轮翻译（若正在运行则提示稍后再试）
- **仅重新扫描**：不改配置，直接再跑一次（用于模组更新后补翻）
- **完成**：返回模组列表
- 状态行会实时显示 `正在扫描并翻译（第 2/3 次尝试）`、`已保存，开始翻译…`、`正在运行中，请稍候…` 等
- 布局按窗口高度自适应，默认 GUI 缩放下不会超出屏幕

> Key 以明文保存在 `config/auto-translation/config.json`，和大多数模组一致；不要把该文件发给别人。

## 2. 翻译来源：CFPA 优先，大模型 / 内置词典兜底

**CFPA** = [CFPAOrg/Minecraft-Mod-Language-Package](https://github.com/CFPAOrg/Minecraft-Mod-Language-Package)，MC 模组简中翻译的社区仓库（CC BY-NC-SA 4.0）。它的 GitHub Release 里按 Minecraft 版本发布整包资源包，例如：

```
https://github.com/CFPAOrg/Minecraft-Mod-Language-Package/releases/download/autobuild/
    Minecraft-Mod-Language-Modpack-1-21.zip     (3.3 MB, 388 个命名空间)
    Minecraft-Mod-Language-Modpack-26-1.zip     (27 KB, 新版本占位包)
```

本模组做了这些事：

| 环节 | 做法 |
|---|---|
| 版本选择 | `cfpaVersion: "auto"` 时按游戏版本推导包名（`26.3` → `26-3`），再退回 `26-1` / `1-21` / `1-20` / `1-19`；**用 Range 请求读取 `Content-Range` 里的文件大小，跳过 <512 KB 的占位包**，所以 26.3 会自动选 1.21 的完整包而不是 26.1 的空壳 |
| 下载 | 直连 GitHub 失败时依次换 `gh-proxy.com`、`ghfast.top` 镜像；下载后先校验能作为 zip 打开，再原子替换 |
| 更新 | 每 24 小时拉一次 `1.21.md5` 比对，没变就不重新下载；结果写进 `state.json` |
| 取用 | 只按命名空间读 `assets/<ns>/lang/zh_cn.json`，不整包塞进游戏 |
| 按模组拉取 | `cfpaPerMod` 开启时用 **HTTP Range 读远程 zip**：先取中央目录，再只下载需要的几个 `zh_cn.json`（实测 1177 条目录 + 单文件解压共 271 ms，不再拉 3.3 MB 整包）；Range 不可用时自动回退整包下载 |
| 优先级 | 同一个 key：CFPA 译文 > AI/缓存译文 > 不干预（继续显示模组原文） |
| 质量闸门 | CFPA 译文若丢失 `%s`/`%d`/`§a` 等占位符，直接丢弃该条，交给 AI 重翻 |
| 离线 | `cfpaLocalZip` 可指定本地 zip；完全离线时只用缓存 + AI |

实测（`tools/CfpaProbe.java`，本机 26.3 环境）：首次解析+下载 3.3 MB 用时 9.9 s，之后 25 ms 命中缓存；对 Xaero 世界地图 1.47.1 的 327 条英文，CFPA 覆盖 **288 条（88%）**，AI 只需补 39 条。

### 没有 API Key 时：内置离线词典

**这不是神经网络模型**，而是一个刻意保守的词典引擎（`OfflineDictionary`，不联网、不下载、不占 jar 体积）：

| 词源 | 说明 |
|---|---|
| 原版术语 | 运行时从本地游戏文件读取 `en_us`（客户端 jar 内）与 `zh_cn`（`assets/objects` 里的语言对象），按 key 对齐，约 **6.6k** 条；不打包任何游戏数据，随游戏版本自动更新 |
| 内置术语表 | 模组自带 `offline-glossary.json`（**819 条**），除模组 UI 词汇外，还补了常用词、代词、介词、连词、数字：camera→相机、character→角色、your→你的、follow→跟随、show→显示、the→（丢弃）…… |

匹配顺序：整串精确命中 → 最长 3 词短语 → 单词逐词替换（含简单复数）；两句语法处理：**冠词 a/an/the 直接丢弃**、**句尾孤立的 at/to 丢弃**（通常是截断的标签）；**字母覆盖率低于 60% 的句子直接放弃**，保持英文。

```
Camera is attached to your character -> 相机附着在你的角色上
The camera follows your character    -> 相机跟随你的角色
Show your coordinates                -> 显示你的坐标
Load New Chunks                      -> 加载新的区块
Open World Map                       -> 打开世界地图
Xaero's World Map Settings           -> Xaero's 世界地图设置
Biomes In Vanilla Mode               -> 生物群系中 Vanilla 模式   (语序仍生硬)
```

实测：词典加载 **7 064** 条 / 约 225 ms；对 Xaero 327 条英文单条覆盖率 **64%**（扩充前 43%）；与 CFPA 叠加后 Xaero **308/327**、Sodium **51/105**，合计 359 条有中文。长句仍翻不动（`sodium` 的说明文字还有一半保持英文），这是词典方案的边界。

填了 Key 之后，在配置界面点一次「保存并翻译」即可让大模型把这些缺口补上——**离线译文会被 AI 译文覆盖**（同一 key 以 CFPA > AI > 离线词典的优先级合并）。缓存文件里用 `model` 字段标注来源：离线产物记为 `offline`，一旦检测到可用 API 后端，这些 key 会被重新翻译；**如果这次 API 全失败，标记会保留**，下次继续尝试，不会被"已缓存"永久挡住。

## 3. 为什么用"资源包"而不是 Mixin 注入

Minecraft 26.3 的语言加载链路已经确认：

```
LanguageManager.onResourceManagerReload(ResourceManager)
    → ClientLanguage.loadFrom(manager, ["en_us", "zh_cn"], rtl)   // Map.put 合并，后加载的覆盖先加载的
    → Language.inject(result)
```

理论上可以 Mixin 到 `ClientLanguage.loadFrom` 或 `LanguageManager` 上直接改 Map。但它依赖 Mojang 内部实现——26.x 已经把 `ResourceLocation` 改名成 `Identifier`、把 `PackSelectionConfig` 从 `repository` 包挪到了 `packs` 包，内部类说变就变。

资源包方案只用公开的、语义稳定的东西：

- `AddPackFindersEvent#addRepositorySource`（NeoForge 的公开资源包入口）
- `Pack` / `PackLocationInfo` / `Pack.Metadata` / `PathPackResources.PathResourcesSupplier`（原版资源包 API）
- 语言合并本身交给原版 `ClientLanguage.appendFrom`，天然支持"后加载覆盖先加载"

代价是：译文必须以 `assets/<namespace>/lang/zh_cn.json` 落盘，所以首次启动要等流水线跑完再触发一次 `Minecraft#reloadResourcePacks()`。

## 4. 目录

```
auto-translation/
├── build.ps1                     # 用本机 JDK25 + 已安装的游戏 jar 直接编译打包（不依赖 Gradle/联网）
├── LICENSE / CHANGELOG.md / CONTRIBUTING.md
├── .github/ISSUE_TEMPLATE/bug_report.md
├── dist/AI-autoTranslation-1.0.0+mc26.3-neoforge.jar      # 本地构建产物（不进 Git，发布走 Releases）
├── tools/Harness.java            # 离线冒烟测试：跑完整流水线（假翻译器 / 真离线词典）
├── tools/CfpaProbe.java          # 只测 CFPA 解析/下载/覆盖统计
├── tools/OfflineProbe.java       # 只测离线词典：词条数、样例、真实字符串覆盖率
├── src/main/resources/META-INF/neoforge.mods.toml
├── src/main/resources/icon.png             # 512×512 模组图标（A → 文）
├── src/main/resources/assets/auto_translation/lang/offline-glossary.json   # 内置术语表
└── src/main/java/com/dsh/autotranslation/
    ├── AutoTranslationMod.java          # 入口：注册资源包/配置界面、一次性调度、3 次重试、重载
    ├── AutoTranslationConfigScreen.java # 配置界面：状态行 + 接口地址/Key/模型 + 保存/重扫按钮
    ├── Pipeline.java             # 扫描 → CFPA 取词 → 查缓存 → 批量翻译 → 写包
    ├── CfpaClient.java           # CFPA：版本解析、镜像下载、md5 校验、按命名空间取词
    ├── ModsScanner.java          # 遍历 mods/*.jar（含 META-INF/jarjar 嵌套 jar）
    ├── PackWriter.java           # 生成 config/auto-translation/pack/assets/<ns>/lang/zh_cn.json
    ├── TranslationCache.java     # 按命名空间缓存 + 源文件哈希
    ├── Translator.java           # 翻译后端接口（available/retryable）
    ├── OpenAiTranslator.java     # OpenAI 兼容接口（DeepSeek / Moonshot / SiliconFlow / Ollama）
    ├── OfflineTranslator.java    # 无 Key 时的内置后端（不联网、不重试）
    ├── OfflineDictionary.java    # 原版 en/zh 对齐 + 内置术语表 + 短语合成
    ├── MyMemoryTranslator.java   # 免密钥兜底（质量一般，仅备用）
    ├── PlaceholderGuard.java     # 保护并校验 %s / %d / %1$s / %% / §a
    ├── Translators.java          # 后端选择（空 Key → offline）
    └── Json.java / Config.java / Logs.java
```

## 5. 构建

需要 **JDK 25**（Minecraft 26.3 的 class 版本是 69，JDK 21/17 都读不了）和一份已安装的 NeoForge 26.3 客户端。国内 JDK 可从清华镜像取：

```
https://mirrors.tuna.tsinghua.edu.cn/Adoptium/25/jdk/x64/windows/
```

```powershell
# 默认从 D:\game\MC\.minecraft\versions\26.3-NeoForge_26.3.0.77 取客户端 jar 与 libraries
powershell -ExecutionPolicy Bypass -File .\build.ps1 -Jdk C:\jdk-25

# 其它安装位置
.\build.ps1 -Jdk C:\jdk-25 -GameDir "E:\MC\.minecraft\versions\26.3-NeoForge_26.3.0.94"

# 编译并直接装进 mods/
.\build.ps1 -Jdk C:\jdk-25 -Pack
```

产物：`build/AI-autoTranslation-1.0.0+mc26.3-neoforge.jar`（`dist/` 是本地副本，已在 `.gitignore` 里，发布走 GitHub Releases）。

脚本会从游戏目录反查 `26.3-NeoForge_*.jar`、`neoforge-*-universal.jar`（取版本号最大的）、FML loader、event bus、gson、slf4j、brigadier 等 jar，`javac --release 25` 编译，`jar` 打包。

> 之所以不用 Gradle：这台机器连 `maven.neoforged.net` 超时，而游戏本体和全部依赖已经在本地，直接对着安装好的 jar 编译反而更稳、可重现。要接 CI 再换 ModDevGradle 也不冲突。

## 6. 配置

首次启动会生成 `config/auto-translation/config.json`。最常用的：

```json
{
  "provider": "openai",
  "apiKey": "sk-你的deepseek密钥",
  "baseUrl": "https://api.deepseek.com/v1/chat/completions",
  "model": "deepseek-chat",

  "targetLanguage": "zh_cn",
  "overwriteExistingTranslations": false,
  "batchSize": 25,
  "parallelism": 4,
  "autoReload": true,

  "cfpaEnabled": true,
  "cfpaVersion": "auto",
  "cfpaLocalZip": ""
}
```

| 字段 | 说明 |
|---|---|
| `provider` | `openai`（任意 OpenAI 兼容端点；**Key/地址为空时自动降级为 `offline`**）、`offline`（内置离线词典）、`mymemory`（免密钥，质量差）、`none`（只用 CFPA + 缓存） |
| `baseUrl` / `apiKey` / `model` | 换成 Moonshot、SiliconFlow、本地 Ollama（`http://127.0.0.1:11434/v1/chat/completions`）都行；**不填 Key 也能用**：CFPA + 内置离线词典仍会补一部分 |
| `overwriteExistingTranslations` | `true` 会连官方已有中文的模组也重译并覆盖，**默认关** |
| `cfpaEnabled` | 是否启用 CFPA 社区汉化（默认开） |
| `cfpaVersion` | `auto` 或显式指定，如 `1-21`、`26-1` |
| `cfpaReleaseBase` | CFPA Release 目录，可换自建镜像 |
| `cfpaMirrorPrefixes` | 下载前缀列表，`""` 表示直连 GitHub，失败按顺序换 `gh-proxy.com`、`ghfast.top` |
| `cfpaLocalZip` | 本地 CFPA 资源包路径（离线用） |
| `cfpaCheckIntervalHours` | 多久检查一次 CFPA 更新 |
| `cfpaPerMod` | 按模组用 Range 精确拉取，而不是下载整包（默认开） |
| `fillMissingKeys` | 模组已有部分中文时只补缺（默认开） |
| `learnGlossary` | 把 CFPA 短词条学进离线词典（默认开） |
| `translationMemory` | 跨模组复用相同英文串的译文（默认开） |
| `includedNamespaces` / `excludedNamespaces` | 只翻某几个 / 排除某几个命名空间 |
| `retranslateChanged` | 模组更新、英文原文变了之后是否重译（默认保留旧译） |

## 7. 运行时会看到什么

1. 启动，日志出现 `AutoTranslation ready (...)`、`CFPA: using cached pack ...`；没填 Key 时还会打印 `No API endpoint/key configured: falling back to the built-in offline dictionary` 和 `Offline dictionary ready: 6668 phrases ...`
2. 首次资源加载完成后打印 `AutoTranslation: pipeline started (startup)`，后台线程开始工作；日志逐个命名空间打印 `xaeroworldmap: 327 strings (cfpa 288, ai 37, missing 2)`
3. 失败会看到 `attempt 1/3 ... untranslated`、`retrying in 10s`；三次都失败则 `AutoTranslation gave up after ... ms (3 attempt(s))`（离线词典不触发重试——结果确定，重试没意义）
4. 成功后弹出 Toast，日志打印 `AutoTranslation finished ... dormant until the next launch or a manual reload`，并按需重载资源
5. 本次游戏内不再扫描；想手动再来一次就去模组列表点本模组的 **Config** →「仅重新扫描」或「保存并翻译」

### API 报错对照表

| HTTP 状态 | 含义 | 模组行为 |
|---|---|---|
| **401 / 403** | Key 无效、被吊销、或不属于这个接口 | **立即停止，不再重试**；Toast / 状态行显示"API Key 无效或没有权限"，日志里带服务端原文 |
| **402** | 账户余额不足 | 同上 |
| **400** | 接口地址或模型名不对（例如把 `/v1/chat/completions` 换成了别家路径） | 同上 |
| 429 | 触发限流 | 按 3 次退避重试 |
| 超时 / 连接失败 | 网络问题 | 按 3 次退避重试 |

日志里只会出现 Key **后 4 位**（服务端返回体本身也只回显后 4 位），不会把完整 Key 打进日志。

产物：

```
config/auto-translation/
├── config.json
├── run-state.json                  # 上次成功运行的输入指纹 + 各模组统计（休眠时状态页直接读它）
├── glossary-learned.json           # 从 CFPA 学到的短词条，供离线词典使用
├── memory.json                     # 翻译记忆：英文串 → 译文（跨模组复用，上限 5 万条）
├── cache/<namespace>.json          # 仅 AI 译文 + 源文件哈希，可手工修正
├── cfpa/cfpa-1-21.zip              # 下载的社区汉化包
├── cfpa/state.json                 # 版本/md5/最后检查时间
└── pack/assets/<ns>/lang/zh_cn.json
```

## 8. 已经验证过的部分

| 验证 | 方法 | 结果 |
|---|---|---|
| 扫描能力（含 JarJar 嵌套） | `tools/Harness.java` 跑真实 `mods/` | 42 个 jar → 43 个命名空间；其中 8 处（去重 5 个命名空间）没有 `zh_cn` |
| 重复版本处理 | 同名模组多版本按 jar 修改时间取最新 | 1.47.0（有中文）与 1.47.1（无中文）并存时判定为"缺中文" |
| CFPA 下载 | `tools/CfpaProbe.java` 空目录首跑 | 自动跳过 26-1 占位包，选中 1-21（3.3 MB / 388 命名空间），9.9 s；二次 25 ms |
| CFPA 覆盖 | 用 Xaero 1.47.1 的 327 条英文做夹具 | 288 条来自 CFPA（88%），39 条交 AI |
| 离线词典加载 | `tools/OfflineProbe.java` | 原版 en/zh 对齐 + 819 条术语表，最终 7 064 条 / 约 225 ms |
| 离线词典质量 | 同一夹具逐条跑 `OfflineDictionary.translate` | 单条覆盖 208/327（64%）；`The camera follows your character→相机跟随你的角色`；`Biomes In Vanilla Mode→生物群系中 Vanilla 模式`（语序生硬）；无把握的仍返回 null |
| 离线 + CFPA 端到端 | `Harness.java` 用 `OfflineTranslator` 跑完整流水线 | Xaero 308/327、Sodium 51/105，合计 359 条，`pending=73`，无网络请求；`validate_pack.py` 校验占位符 0 丢失 |
| 局部补全 | 构造 en_us 10 条 / zh_cn 4 条的夹具 | 只输出缺失的 3 条（Chunk/Waypoint/Backpack），官方 4 条未被覆盖 |
| 按模组拉取 | `RemoteZipProbe` + 本地 Range 服务器 | 读 1177 条中央目录 271 ms，单条解压 26 KB，内容正确 |
| 术语表学习 | 用 Xaero 夹具跑两次 | 首轮从 CFPA 学到 108 条（`Cave Mode→洞穴模式` 等），次轮离线覆盖 +1 |
| 翻译记忆 | 同一个 workDir 先跑 Xaero，再跑共享同样 20 条英文的另一命名空间 | 首轮写入 317 条记忆；第二轮 `memory=18, ai=0`，全部命中记忆，未再调用翻译后端 |
| 合并与占位符 | `validate_pack.py` 对比源文与译文 | 432 条、0 处占位符丢失、0 个多余 key |
| 缓存复用 | 同一夹具跑第二遍 | `ai=0, changed=false` |
| 静态链接 | 解析 class 常量池，核对游戏 classpath | 所有引用类均存在（含 `Dist`、`PackSelectionConfig`） |
| 资源包注入 | 按 26.3 字节码核对 `Pack`/`PackSelectionConfig`/`AddPackFindersEvent` 签名 | 编译通过；`Pack.isRequired()` 会在 `PackRepository.rebuildSelected` 里被自动选中 |
| 失败重试 / 手动重载 | 静态检查状态机与调用路径 | 异常或缺口语义触发重试，上限 3 次、退避 10/20 s；`pending==0` 或未配置 AI 后端时直接结束；配置界面按钮与启动走同一入口，运行中按钮置灰 |
| 配置界面 | 对照 26.3 的 `Screen`/`Button`/`StringWidget`/`IConfigScreenFactory` 签名 | 编译通过；界面不重写渲染方法，只用默认渲染 + 三个 widget |

**没有做到**：本次会话没有真的启动游戏跑一遍（需要开窗口的游戏进程）。第一次实测时请重点看日志里有没有 `AutoTranslation finished` 和资源包注册异常。

## 9. 已知限制

- 只处理 `zh_cn`（改 `targetLanguage` 可换 `zh_tw` 等，但界面仍需对应语言才生效）
- 部分补全按 key 粒度做，但如果模组自带的中文本身有错，默认不会覆盖（需要 `overwriteExistingTranslations`）
- 内置离线词典是**术语级**的：短句（主谓宾、常见短语）能读，语序复杂或从句较多时会出现"生物群系中 Vanilla 模式"这种生硬语序；覆盖率 <60% 会主动放弃
- 离线词典依赖本地游戏文件：原版 `en_us` 在客户端 jar 内，`zh_cn` 在 `assets/objects` 里；资源不完整时会退化为"只用内置术语表"
- CFPA 目前没有真正的 26.3 包，自动回退到 1.21 包：key 基本兼容，但个别新版本字符串可能对不上（对不上就不会用，不会显示错字）
- CFPA 也没收录所有模组（例如 `sodium`、`trender` 在 1.21 包里就没有），这些只能靠 AI
- 翻译质量取决于模型，专有名词、按键名可能需要人工润色——直接改 `pack/` 下的 JSON 或 `cache/` 后重载即可
- 生成的是 `required` 内置资源包，资源包界面里无法关闭（想关就 `enabled=false` 或删 jar）
- 重试只在一次「运行」内进行：三次都失败后本次游戏不再自动尝试（想立刻再试就点配置界面的按钮）
- 首次启动会出现"先英文、后重载成中文"的现象，属预期
- CFPA 资源包采用 **CC BY-NC-SA 4.0**：本模组只在运行时下载、不打包分发；如需二次分发请遵守其协议

## 10. 许可证与署名

| 字段 | 当前值 | 建议 |
|---|---|---|
| `license` | `MIT` | 保持 MIT：允许整合包分发、修改、闭源再发布，只需保留版权声明 |
| `authors` | `dream_fish, dsh` | 两人共同署名；如需再加/去掉，改这一行即可 |
| `credits` | CFPA 署名 | 已写入：`CFPAOrg/Minecraft-Mod-Language-Package`（运行时下载，CC BY-NC-SA 4.0） |

为什么代码用 MIT、而不用 CFPA 的 CC BY-NC-SA：

- CC BY-NC-SA 是**非商业 + 相同方式共享**，套在代码上会禁止整合包商用、并强制衍生作品同样开源；而本模组**不打包** CFPA 的译文（运行时下载），代码本身可以用宽松协议
- 原版 `en_us` / `zh_cn` 是运行时从游戏文件读取，不随 jar 分发，不涉及再分发
- 内置术语表 `offline-glossary.json` 是自编词条；如果以后把 CFPA 的词条抄进来，那部分必须保留 CC BY-NC-SA 并在文件头注明
- 图标为自绘，无第三方素材

MIT 要求随分发保留版权声明，所以 `LICENSE` 已放在项目根目录并打进 jar（`LICENSE` 条目）。

发布到 Modrinth / CurseForge 时，平台上的 License 字段要与 jar 里的 `license="MIT"` 保持一致；署名按 `dream_fish, dsh` 填写即可，`LICENSE` 里的版权行已同步。

## 11. 参与贡献

欢迎补充术语表、反馈译文、提交代码，细节见 [CONTRIBUTING.md](CONTRIBUTING.md)。提 Issue 前请附上模组版本、翻译后端和 `latest.log` 里的相关段落（注意把 Key 打码）。

## 12. 免责声明

- 自动翻译（尤其离线词典和机翻）不保证准确，专有名词、按钮文案可能需要人工润色
- CFPA 译文由社区维护，遵循 CC BY-NC-SA 4.0；本模组只在运行时下载，不打包分发
- 使用在线 API 产生的费用由使用者承担；请自行保管 Key，不要提交到 Git 或分享 `config.json`
