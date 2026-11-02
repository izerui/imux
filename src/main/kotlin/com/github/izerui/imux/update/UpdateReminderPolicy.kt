package com.github.izerui.imux.update

import com.intellij.util.text.VersionComparatorUtil

/**
 * 远端有更新版本、用户没忽略过、本次运行也没提醒过时才提醒。
 *
 * [ignored] 是用户点"忽略此版本"记下的版本号，只挡这一个版本；[alreadyShown] 是本次 IDE 运行已提醒过的版本。
 */
internal fun shouldRemind(
    current: String,
    latest: String?,
    ignored: String?,
    alreadyShown: String?,
): Boolean =
    latest != null &&
        VersionComparatorUtil.compare(latest, current) > 0 &&
        latest != ignored &&
        latest != alreadyShown
