package com.life798.mini.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.life798.mini.R

/**
 * 打开外部链接。
 *
 * Android 11 的包可见性会让「没有浏览器」变成抛异常而不是静默失败，
 * 所以必须兜住，否则点一下就崩。
 */
internal fun Context.openLink(url: String) {
    if (url.isBlank()) return
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, R.string.link_open_failed, Toast.LENGTH_SHORT).show()
    } catch (_: SecurityException) {
        Toast.makeText(this, R.string.link_open_failed, Toast.LENGTH_SHORT).show()
    }
}
