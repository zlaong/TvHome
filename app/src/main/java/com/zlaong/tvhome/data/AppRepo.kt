package com.zlaong.tvhome.data

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import androidx.core.graphics.drawable.toBitmap

/** 电视上已安装、可启动的应用 */
data class InstalledApp(
    val label: String,
    val packageName: String,
    val icon: Drawable?,
    /** 电视应用自带的 320x180 海报图，影视 APP 基本都有；没有则为 null */
    val banner: Drawable?,
    val intent: Intent,
    /** 是否为影视类 APP */
    val isVideo: Boolean = false,
    /** 用户自己装的（非系统预装），长按菜单里才给「卸载」 */
    val removable: Boolean = false,
) {
    /** 第一次用到时转成位图并缓存，抽屉每次展开不再重复转换 */
    val bannerBitmap: Bitmap? by lazy {
        banner?.let { runCatching { it.toBitmap(320, 180) }.getOrNull() }
    }
    val iconBitmap: Bitmap? by lazy {
        icon?.let { runCatching { it.toBitmap(144, 144) }.getOrNull() }
    }
}

object AppRepo {

    private const val SELF = "com.zlaong.tvhome"

    /**
     * 常见影视 APP 包名 → 排序权重（越小越靠前）。
     * 包名优先，识别不到再退回“名字里带影视关键词”。
     */
    private val VIDEO_PACKAGES = mapOf(
        "com.gitvdemo.video" to 0,                     // 云视听极光（腾讯）
        "com.ktcp.video" to 2,                         // 酷喵（优酷）
        "com.ktcp.tvvideo" to 2,
        "com.qiyi.video.tv" to 1,                      // 银河奇异果（爱奇艺）
        "com.iqiyi.video" to 1,
        "com.cibn.tv" to 2,                            // CIBN 酷喵（优酷）
        "com.tvvideo.youku" to 2,
        "com.hunantv.market" to 3,                     // 芒果 TV
        "com.mgtv.tv" to 3,
        "tv.danmaku.bili" to 4,                        // 云视听小电视（B站）
        "com.xiaodianshi.tv" to 4,
        "com.xiaodianshi.tv.yst" to 4,
        "com.google.android.youtube.tv" to 5,
        "com.netflix.ninja" to 6,
        "com.disney.disneyplus" to 7,
        "com.amazon.amazonvideo.livingroom" to 8,
        "com.cctv.yangshipin.tv" to 9,
        "com.miguvideo.tv" to 10,
    )

    /** 名字里带“TV/电视”但不是影视 App 的，直接排除（遥控服务最容易误伤） */
    /** 完全不出现在桌面上的：系统服务类，用户不会主动去点 */
    private val HIDDEN = setOf(
        "com.google.android.tv.remote.service",
    )

    private val NEVER_VIDEO = setOf(
        "com.google.android.tv.remote.service",
        "com.google.android.tvlauncher",
        "com.oversea.aslauncher",
    )

    private val VIDEO_KEYWORDS = listOf(
        "影视", "视频", "影院", "极光", "奇异果", "酷喵", "芒果", "哔哩", "B站", "哔哩哔哩",
        "爱奇艺", "优酷", "腾讯", "央视频", "咪咕", "Netflix", "YouTube", "Disney",
        "小电视", "云视听",
    )

    fun loadApps(context: Context): List<InstalledApp> {
        val pm = context.packageManager
        val leanback = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0
        )
        val normal = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        )

        val seen = HashSet<String>()
        val result = ArrayList<InstalledApp>()

        (leanback + normal).forEach { info ->
            val activityInfo = info.activityInfo ?: return@forEach
            val pkg = activityInfo.packageName ?: return@forEach
            if (pkg == SELF || pkg in HIDDEN || !seen.add(pkg)) return@forEach
            val label = info.loadLabel(pm)?.toString()?.ifBlank { pkg } ?: pkg
            val icon = runCatching { info.loadIcon(pm) }.getOrNull()
            // 优先用入口 Activity 自己的横幅，没有再退回整个应用的横幅
            val banner = runCatching { activityInfo.loadBanner(pm) }.getOrNull()
                ?: runCatching { pm.getApplicationBanner(pkg) }.getOrNull()
            val intent = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setClassName(pkg, activityInfo.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            result.add(
                InstalledApp(
                    label = label,
                    packageName = pkg,
                    icon = icon,
                    banner = banner,
                    intent = intent,
                    isVideo = isVideoApp(pkg, label),
                    removable = (activityInfo.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0,
                )
            )
        }

        // 调试包把扫描结果打到 logcat，方便在真机上确认分类对不对
        if (com.zlaong.tvhome.BuildConfig.DEBUG) {
            result.forEach {
                android.util.Log.d("TvHomeApps", "${it.label} | ${it.packageName} | video=${it.isVideo} | banner=${it.banner != null}")
            }
        }

        // 影视 APP 按常用顺序排（腾讯→爱奇艺→优酷→芒果→B站→国外），其余按名字排
        return result.sortedWith(
            compareBy(
                { if (it.isVideo) 0 else 1 },
                { VIDEO_PACKAGES[it.packageName] ?: 99 },
                { it.label },
            )
        )
    }

    /** 列表加载完在后台线程把图都转好，避免首次滚动卡顿 */
    fun warmBitmaps(apps: List<InstalledApp>) {
        apps.forEach { it.bannerBitmap; it.iconBitmap }
    }

    private fun isVideoApp(pkg: String, label: String): Boolean {
        if (pkg in NEVER_VIDEO) return false
        if (VIDEO_PACKAGES.containsKey(pkg)) return true
        // 索尼自家的系统 App 不当影视，避免把“媒体播放器/帮助”塞进第一屏
        if (pkg.startsWith("com.sony.")) return false
        return VIDEO_KEYWORDS.any { label.contains(it, ignoreCase = true) }
    }
}