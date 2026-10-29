import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.File

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}

/**
 * 把 CHANGELOG.md 中某个版本的段落渲染成 plugin.xml `<change-notes>` 能显示的 HTML。
 *
 * 背景：IntelliJ Platform Gradle Plugin 2.x 不会自动读 CHANGELOG.md（本项目没有
 * `patchChangelog` 任务，`buildPlugin` 也不依赖它），plugin.xml 里的 `<change-notes>`
 * 一直是手写的静态文案 —— 结果是每个版本在 Marketplace 详情页上都显示同一段 0.5.0
 * 「Initial release」。这里在配置期显式注入对应版本的段落，发版即带正确的更新说明。
 *
 * 找不到对应版本时返回空串，此时保留 plugin.xml 的静态内容作为兜底。
 */
fun changelogSectionHtml(changelog: File, version: String): String {
    if (!changelog.exists()) return ""
    val lines = changelog.readLines()
    val header = Regex("""^##\s+\[?${Regex.escape(version)}\]?(\s|$)""")
    val start = lines.indexOfFirst { header.containsMatchIn(it.trim()) }
    if (start < 0) return ""

    fun inline(text: String): String = text
        .replace(Regex("""<((?:https?://)[^>\s]+)>""")) { m ->
            """<a href="${m.groupValues[1]}">${m.groupValues[1]}</a>"""
        }
        .replace(Regex("""\[([^\]]+)\]\(([^)]+)\)""")) { m ->
            """<a href="${m.groupValues[2]}">${m.groupValues[1]}</a>"""
        }
        .replace(Regex("""\*\*([^*]+)\*\*""")) { m -> "<b>${m.groupValues[1]}</b>" }
        .replace(Regex("""`([^`]+)`""")) { m -> "<code>${m.groupValues[1]}</code>" }

    val body = lines.drop(start + 1).takeWhile { !it.trimStart().startsWith("## ") }
    val html = StringBuilder()
    var inList = false
    var itemOpen = false

    // Markdown 里一个列表项经常折成多行，续行缩进后不以 "- " 开头，
    // 必须并进上一个 <li>，否则会被误判为段落而把列表拆成多段 <ul>。
    fun closeItem() { if (itemOpen) { html.append("</li>"); itemOpen = false } }
    fun closeList() { closeItem(); if (inList) { html.append("</ul>"); inList = false } }

    for (raw in body) {
        val line = raw.trim()
        when {
            line.isEmpty() -> closeList()
            line.startsWith("### ") -> {
                closeList()
                html.append("<br/><b>").append(inline(line.removePrefix("### ").trim())).append("</b><br/>")
            }
            line.startsWith("- ") || line.startsWith("* ") -> {
                closeItem()
                if (!inList) { html.append("<ul>"); inList = true }
                itemOpen = true
                html.append("<li>").append(inline(line.drop(2).trim()))
            }
            inList -> html.append(' ').append(inline(line))
            else -> html.append(inline(line)).append("<br/>")
        }
    }
    closeList()
    return html.toString().trim()
}

group = "com.commitwise"
version = providers.gradleProperty("pluginVersion").get()

dependencies {
    intellijPlatform {
        // 发布构建用远程 IntelliJ Platform 分发包；本地开发若网络受限，
        // 可在 gradle.properties 里设 useLocalIde=true 并指定 localIdePath
        if (providers.gradleProperty("useLocalIde").getOrElse("false").toBoolean()) {
            local(providers.gradleProperty("localIdePath").get())
        } else {
            intellijIdea(providers.gradleProperty("platformVersion").get())
        }
        bundledPlugin("Git4Idea")
        pluginVerifier()
        zipSigner()
    }

    implementation(libs.gson)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        freeCompilerArgs.add("-jvm-default=no-compatibility")
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "com.commitwise.idea"
        name = "CommitWise"
        version = providers.gradleProperty("pluginVersion")
        // 每个版本的更新说明取自 CHANGELOG.md 对应段落（见文件顶部 changelogSectionHtml）。
        // 段落缺失时返回空串，patchPluginXml 会保留 plugin.xml 里的静态兜底文案。
        changeNotes.set(providers.provider {
            changelogSectionHtml(file("CHANGELOG.md"), version.get())
        })
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = providers.gradleProperty("pluginUntilBuild").orNull?.ifBlank { null }
        }
        vendor {
            name = "miaosha-ikio"
            // Marketplace 插件页的 vendor 链接。此前指向 github.com/miaosha-ikio（404），
            // 统一改为官方站点；这里才是构建时的真相源 —— patchPluginXml 会用本段覆盖 plugin.xml。
            url = "https://commitwise.aikio.blog/"
        }
    }

    // Plugin Signing —— JetBrains Marketplace 自 2021.2 起强制要求签名，未签名会被拒绝上传。
    // 证书链与私钥从环境变量读取（不写入 gradle.properties，避免泄漏）。
    // 获取方式：登录 Marketplace → 你的插件 → Signing → Generate / Download certificate chain。
    // 在 CI 中通过 GitHub Secrets 注入；本地若不发布可不设置（signPlugin 任务会跳过）。
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN").orElse("")
        privateKey = providers.environmentVariable("PRIVATE_KEY").orElse("")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD").orElse("")
    }

    // Publishing —— 发布到 JetBrains Marketplace 的上传令牌。
    // 在 https://plugins.jetbrains.com/author/me/tokens 生成 Personal Access Token，
    // 在 CI 中通过 GitHub Secret `PUBLISH_TOKEN` 注入。
    // 本地构建时 token 为空，publishPlugin 任务会提示需要 token 而中止（不影响 buildPlugin）。
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN").orElse("")
    }

    // Plugin Verification —— 发布前验证插件在推荐 IDE 版本上的二进制兼容性。
    // GitHub Actions release 工作流会运行 verifyPlugin；本地构建可手动跳过以节省时间。
    pluginVerification {
        ides {
            recommended()
        }
    }

    buildSearchableOptions = false
    instrumentCode = false
}

tasks {
    patchPluginXml {
        sinceBuild.set(providers.gradleProperty("pluginSinceBuild"))
        untilBuild.set(providers.gradleProperty("pluginUntilBuild").orNull?.ifBlank { null })
    }
    named("buildPlugin") {
        dependsOn("prepareSandbox")
    }
    test {
        useJUnitPlatform()
        // 排除 IntelliJ Platform 的测试框架 jar（testFramework.jar / test-framework-*.jar）。
        // 它通过 META-INF/services 注册了 JUnit Platform 的 LauncherSessionListener
        // （com.intellij.testFramework.junit5.impl.JUnit5TestEnvironmentInitializer），
        // 被 ServiceLoader 自动加载后会调用 Logger.setFactory，而后者需要 JUnit 4 的
        // org.junit.rules.TestRule。本项目 206 个测试全部是纯 Kotlin 单元测试（diff 解析、
        // 脱敏、模板、SSE/NDJSON 解析、git 采集、配置校验），不启动 IDE 实例、不使用
        // IntelliJ 测试框架，因此直接把该 jar 从测试 classpath 移除以避免
        // NoClassDefFoundError: org/junit/rules/TestRule。
        classpath = classpath.filter { file ->
            !file.name.contains("testFramework", ignoreCase = true) &&
                !file.name.contains("test-framework", ignoreCase = true)
        }
        testLogging {
            events("passed", "failed", "skipped")
            showStandardStreams = false
        }
    }
    runIde {
        // 本地调试时禁用插件自更新检查，避免误连 Marketplace
        systemProperty("intellij.platform.update.checking.disabled", "true")
    }
}
