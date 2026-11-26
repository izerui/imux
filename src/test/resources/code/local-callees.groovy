// 在装有待测 imux 与 JavaScript 插件的隔离 IDE 中运行：idea ideScript <本文件>。
// 独立临时工程使断言走真正的 PSI、引用解析、结构视图和 CodeNavigator，不依赖 test-framework。
import com.intellij.ide.impl.ProjectUtil
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.DumbService
import java.nio.file.Files

def dir = Files.createTempDirectory('imux-callees-project').toRealPath()
Files.createDirectories(dir.resolve('.idea'))
Files.writeString(dir.resolve('.idea/modules.xml'), '<project version="4"><component name="ProjectModuleManager"><modules><module fileurl="file://$PROJECT_DIR$/fixture.iml" filepath="$PROJECT_DIR$/fixture.iml" /></modules></component></project>')
Files.writeString(dir.resolve('fixture.iml'), '<module type="JAVA_MODULE" version="4"><component name="NewModuleRootManager"><content url="file://$MODULE_DIR$"><sourceFolder url="file://$MODULE_DIR$" isTestSource="false" /></content><orderEntry type="sourceFolder" forTests="false" /></component></module>')
Files.writeString(dir.resolve('calls.js'), '''function reached() {}
function leaked() {}
function inlineBody() {}
function outer() {
  function unused() { leaked(); }
  function inner() { reached(); }
  const value = () => inlineBody();
  inner();
}
''')
Files.writeString(dir.resolve('Calls.kt'), '''fun reached() {}
fun leaked() {}
fun inlineBody() {}
fun outer() {
    fun unused() { leaked() }
    fun inner() { reached() }
    val value = { inlineBody() }
    inner()
}
''')
com.intellij.ide.trustedProjects.TrustedProjects.setProjectTrusted(dir, true)
def project = ProjectUtil.openOrImport(dir)
DumbService.getInstance(project).waitForSmartMode()
def loader = PluginManagerCore.getPlugin(PluginId.getId('com.github.izerui.imux')).pluginClassLoader
def type = { loader.loadClass('com.github.izerui.imux.code.' + it) }
def run = { file, depth ->
    ReadAction.compute {
        def query = type('CodeQuery').getConstructor(String, Integer.TYPE, Integer, String, Boolean.TYPE, Integer.TYPE).newInstance(file, 4, null, 'outer', false, 200)
        def result = type('CodeNavigator').constructors.first().newInstance(project).callHierarchy(query, type('CallDirection').CALLEES, depth)
        assert !result.failed : result.value
        result.value.tree.callees.collectEntries { [(it.name.asString): it] }
    }
}
['calls.js', 'Calls.kt'].each { file ->
    def direct = run(file, 1)
    assert direct.keySet() == ['inner', 'inlineBody'] as Set : "$file: $direct"
    assert direct.inner.sites*.usage*.asString == ['call']
    assert !direct.inner.has('callees')
    def deep = run(file, 2)
    assert deep.inner.callees*.name*.asString == ['reached']
    println "PASS local-callees $file"
}
