import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "com.github.liuyuhua"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    intellijPlatform {
        intellijIdeaCommunity("2026.1")
        // 终端 API 来自捆绑插件，必须显式声明才能编译
        bundledPlugin("org.jetbrains.plugins.terminal")
        testFramework(TestFrameworkType.Platform)
    }
}

kotlin {
    jvmToolchain(21)
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "261"
            // 刻意不设 untilBuild：见 docs/superpowers/plans 中的决策记录。
            // 代价是终端 API 漂移时会在运行时抛 NoSuchMethodError 而非安装期拒绝。
            untilBuild = provider { null }
        }
    }
}
