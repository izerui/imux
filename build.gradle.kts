import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode

plugins {
    // 必须与 IDEA 2026.2 自身的 Kotlin 版本对齐：平台 jar 的 kotlin_module
    // 元数据是 2.3.0 版本，用 2.1.x 编译会报「incompatible version of Kotlin」。
    id("org.jetbrains.kotlin.jvm") version "2.3.21"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "com.github.izerui"
version = "0.2.15"

repositories {
    // repo.maven.apache.org 在本机网络下 TLS 握手被重置，改用可达镜像。
    // 实测：cache-redirector.jetbrains.com 与 aliyun 可达，maven central 与
    // download.jetbrains.com 不可达。
    maven("https://maven.aliyun.com/repository/public")
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    // 读取 Codex 的 ~/.codex/state_5.sqlite 取会话标题。
    // 该库自带各平台原生库，体积较大，但 IDEA 未捆绑任何标准 JDBC 驱动，
    // 且 codex 的权威标题只存在于这个 sqlite 里（rollout 文件里没有）。
    implementation("org.xerial:sqlite-jdbc:3.49.1.0")

    testImplementation("junit:junit:4.13.2")

    intellijPlatform {
        // 用本机已安装的 IDEA 2026.2（IU-262.8665.337）作为平台依赖。
        //
        // 不用 intellijIdea("2026.2") 的原因：它会去 download.jetbrains.com 拉
        // 完整安装包（macOS 上是 .dmg，1G+），而该主机在本机网络下不可达。
        // 用 local 既避开下载，也保证编译期看到的就是运行期那份 jar ——
        // 对我们依赖 Experimental 终端 API 的场景反而更准确。
        local("/Applications/IntelliJ IDEA.app")
        bundledPlugin("org.jetbrains.plugins.terminal")
        // 暂不引入 testFramework(TestFrameworkType.Platform)：
        // com.jetbrains.intellij.platform:test-framework 需从 JetBrains 仓库下载，
        // 本机网络下该主机 TLS 稳定被重置。它只服务于 BasePlatformTestCase 类的
        // 平台测试；核心逻辑测试仅依赖 JUnit 4，不受影响。
        // 网络条件允许时可加回，并恢复 AgentToolWindowRegistrationTest。
    }
}

kotlin {
    jvmToolchain(25)

    compilerOptions {
        // 默认的 `enable` 模式会给每个实现类补一份「桥接 override」，把接口里所有
        // 继承来的默认方法（含平台已弃用的 ToolWindowFactory.isApplicable、
        // EditorTabTitleProvider.getEditorTabTooltipText 等）都重新实现一遍并回调过去。
        // 我们一行都没写，插件验证器却报出 6 处「已弃用 API 用法」，来源就是这些桥接。
        // no-compatibility 让实现类直接继承接口的 JVM default 方法，桥接不再生成。
        // 本项目自己声明的接口都没有默认实现，不存在 DefaultImpls 的二进制兼容包袱。
        jvmDefault = JvmDefaultMode.NO_COMPATIBILITY
    }
}

intellijPlatform {
    // sqlite-jdbc 会向 JVM 全局 DriverManager 注册驱动，旧插件 ClassLoader
    // 无法可靠热卸载。开发时明确重启沙箱，避免 prepareSandbox 触发失败的动态重载。
    autoReload = false

    // 关闭字节码插桩：它需要下载 com.jetbrains.intellij.java:java-compiler-ant-tasks，
    // 而该仓库在本机网络下不可达。本插件不使用 GUI Designer 的 .form，
    // 也不依赖平台的 @NotNull 运行时断言插桩，关掉无影响。
    instrumentCode = false

    pluginConfiguration {
        ideaVersion {
            sinceBuild = "262"
            // 刻意不设 untilBuild：见 docs/superpowers/plans 中的决策记录。
            // 代价是终端 API 漂移时会在运行时抛 NoSuchMethodError 而非安装期拒绝。
            untilBuild = provider { null }
        }
    }
}

// 上报脚本必须以**文件**形式随插件安装存在：pi 的 -e 收的是路径，读不了 jar 内资源。
// 放进插件目录而不是运行时解压到别处，是为了守住「插件不写盘」这条承诺。
//
// 必须用 withType 而不是 named("prepareSandbox")：本插件注册了 5 个 PrepareSandboxTask
// 实例——prepareSandbox（buildPlugin 打包用）、prepareSandbox_runIdeBackend /
// _runIdeFrontend（runIde 的分体沙箱）、prepareTestSandbox（测试）、
// prepareTestIdePerformanceSandbox。named 只配到第一个。
//
// 打出来的 zip 因此仍然是对的，但 runIde 的沙箱与测试沙箱里都没有这个脚本：
// 开发时在沙箱里跑 pi，piReporterScript() 找不到文件就退回「不加 -e」，
// 会话照常起、只是标签页不跟随——正因为没有任何报错，这类缺失极难被发现。
// withType 覆盖全部实例，脚本在所有沙箱里都在。
tasks.withType<org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask> {
    from("src/main/js/pi-imux-reporter.js") { into("${project.name}/scripts") }
}
