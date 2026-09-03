import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode

plugins {
    // 必须与 IDEA 2026.2 自身的 Kotlin 版本对齐：平台 jar 的 kotlin_module
    // 元数据是 2.3.0 版本，用 2.1.x 编译会报「incompatible version of Kotlin」。
    id("org.jetbrains.kotlin.jvm") version "2.3.21"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "com.github.izerui"
version = "0.3.18"

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
        // 用最新 262.10315 平台编译并做 verifier 基线，同时保证产物能装到整个 262 系列。
        //
        // 262 生命周期内 JetBrains 改了 Experimental detachTab 的 JVM 签名：
        //   262.8665 / 262.9437: fun detachTab(tab): TerminalView
        //   262.10315+        : fun detachTab(tab)            // 返回值改成 Unit
        // 业务代码因此不能产生直接调用 detachTab 的字节码，而是在一个受控位置按参数反射调用；
        // 具体原因和生命周期约束见 TerminalHost.detachTabAcross262。
        //
        // 使用平台 installer，runIde 才会拿到当前系统需要的原生组件。通用仓库 ZIP
        // 不包含 platform-daemon-plugin/jetbrainsd_mac_aarch64.tar.gz，IDE 虽能启动，
        // 但 JetBrains OS Integration 会因 bundled daemon 缺失连续重试并报 SEVERE。
        create("IU", "262.10315.19")
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
            // detachTab 的返回类型漂移由 TerminalHost 中的窄反射桥接隔离，产物支持整个 262。
            sinceBuild = "262"
            // 0.3.18 只针对 262 编译和验证。设置上界可避免 Marketplace 把插件
            // 自动拿到 263 EAP 做安装回归；263 的 Terminal/UI 行为需单独验证后再支持。
            untilBuild = "262.*"
        }

        // dev.sh 在打包前让只读 pi 根据最近一次发布以来的 Git 变化生成当前版本日志。
        // 通过 Gradle Provider 接入，文件内容会成为 patchPluginXml 的任务输入；直接在脚本里
        // 改 build/tmp 下的 plugin.xml 会绕过增量构建，命中缓存时反而把旧日志装进 ZIP。
        changeNotes =
            providers.gradleProperty("changeNotesFile").flatMap { path ->
                providers.fileContents(layout.projectDirectory.file(path)).asText
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

// 本项目有一批「源码级」测试：ImuxLspUiSourceTest、PluginXmlRegistrationTest、
// ImuxBundleTest、SessionTabRestoreSourceTest 等等，都在运行期用
// `File("src/main/kotlin/…").readText()` 直接读源文件做结构断言。
//
// 而 `:test` 的默认输入只有 **classpath**（编译产物）。于是任何字节码等价的源码改动
// ——删一个尾逗号、改一句注释、重排格式——都不会改变 classpath 的哈希，Gradle 判定
// `:test` UP-TO-DATE / FROM-CACHE 直接跳过。实测：删掉 ImuxLspConfigurable.kt 里
// `ShellBinaryProbe(),` 的尾逗号再跑 `./gradlew test`，输出是 `> Task :test FROM-CACHE`,
// 测试**根本没有对着改动后的源码跑过**。这类「假绿」比测试没写还危险：
// 它让「我改了源码、测试还是绿的」看起来像一条通过的验证。
//
// 把这三个源码目录显式声明成 `:test` 的输入，源文件一变（哪怕只变注释）就重跑。
// 代价是注释改动也会触发一次测试，相对于假绿是划算的。
tasks.test {
    listOf("src/main/kotlin", "src/main/resources", "src/main/js").forEach { dir ->
        inputs
            .dir(dir)
            .withPropertyName("sourceReadAtRuntime-${dir.replace('/', '-')}")
            .withPathSensitivity(PathSensitivity.RELATIVE)
    }
    // **构建脚本自己也是运行期读的源码之一。** TerminalIntegrationSourceTest 打开
    // build.gradle.kts 核对上面那条 `from(…)` 的脚本路径与仓库里的文件对不对得上，
    // 而 `:test` 的输入里原本没有构建脚本：把 `pi-imux-reporter.js` 写错一个字母，
    // Gradle 判 `:test` UP-TO-DATE 直接跳过，那条断言**根本没跑过**——变异验证实测确认。
    // 与上面那段说的是同一类「假绿」，只是漏了这一个文件。
    inputs
        .file("build.gradle.kts")
        .withPropertyName("buildScriptReadAtRuntime")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
