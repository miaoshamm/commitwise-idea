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
