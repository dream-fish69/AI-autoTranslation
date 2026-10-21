# 参与贡献

感谢你愿意帮忙。这个项目主要接受三类贡献：**术语表补充**、**翻译质量反馈**、**代码改动**。

## 开发环境

- JDK 25（Minecraft 26.3 的 class 版本是 69，JDK 21/17 读不了）
- 一份已安装的 NeoForge 26.3 客户端（构建脚本直接对着本地游戏 jar 编译，不走 Gradle）

```powershell
powershell -ExecutionPolicy Bypass -File .\build.ps1 -Jdk C:\jdk-25
powershell -ExecutionPolicy Bypass -File .\build.ps1 -Jdk C:\jdk-25 -GameDir "D:\path\to\versions\26.3-NeoForge_x" -Pack
```

离线验证工具（不需要启动游戏）：

```powershell
# 离线词典：词条数、样例、真实字符串覆盖率
java -cp "build/classes;<gson>;<slf4j>" OfflineProbe "<.minecraft>" "<client jar>" "<en_us.json>"
# 完整流水线（桩翻译器）
java -cp "build/classes;<gson>;<slf4j>;build/harness" Harness "<fixtures>" "<work dir>"
```

## 补充术语表

编辑 `src/main/resources/assets/auto_translation/lang/offline-glossary.json`，格式是平铺的 `"English": "中文"`：

```json
{
  "Waypoint": "路径点",
  "Render Distance": "渲染距离"
}
```

约定：

- 无占位符（`%s` 之类会让译文依赖上下文，离线词典不会复用）
- 尽量使用 Minecraft 官方译名（参考 [译名标准化](https://zh.minecraft.wiki/w/Minecraft_Wiki:%E8%AF%91%E5%90%8D%E6%A0%87%E5%87%86%E5%8C%96)）
- 一个条目只表达一个概念，长句请拆成短语

## 重新生成图标

`icon.png`（512×512）由 `tools/make_icon.py` 生成，需要 Pillow 和 Windows 自带的 Segoe UI Bold / 微软雅黑：

```powershell
python .\tools\make_icon.py --size 512
```

## 接入新的翻译后端

实现 `Translator` 接口（一个 `translate(List<String>)` 方法），在 `Translators.create` 的 `switch` 里加一个分支即可；占位符保护、缓存、记忆、资源包生成都不用改。

## 代码风格

- Java 25，4 空格缩进
- 不引入新的运行时依赖（Gson / slf4j 由游戏提供）
- 不新增 Mixin：语言层通过资源包注入，避免和 Sodium/Iris 抢注入点
- 提交前跑一遍 `build.ps1`，确认 `javac -Xlint:all` 对项目代码零警告

## 反馈翻译问题

请附上：

1. 模组名 + 版本（或 jar 文件名）
2. 出问题的原文与当前译文
3. `config/auto-translation/memory.json` 或 `pack/assets/<ns>/lang/zh_cn.json` 里对应的那一条

直接提 Issue 即可，模板会提示需要的信息。
