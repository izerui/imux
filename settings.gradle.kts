// 插件解析不走 dependencies 的 repositories，需单独配镜像。
// 本机网络下 plugins.gradle.org 时通时断，把阿里镜像放在前面兜底。
pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/public")
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "imux"
