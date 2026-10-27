import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}

group = "com.commitwise"
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

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
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = providers.gradleProperty("pluginUntilBuild").orNull?.ifBlank { null }
        }
        vendor {
            name = "miaosha-ikio"
            url = "https://github.com/miaosha-ikio"
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
