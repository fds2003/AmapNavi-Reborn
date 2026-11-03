# AmapNavi-Reborn 混淆规则（release 构建默认关闭混淆，保留规则以备启用）
-keep class tech.touka.amapnavi.core.model.** { *; }
-keep class tech.touka.amapnavi.core.event.** { *; }
-keep class tech.touka.amapnavi.domain.** { *; }
-keep class tech.touka.amapnavi.App { *; }
-keep class tech.touka.amapnavi.service.KeepAliveService { *; }
-keep class tech.touka.amapnavi.receiver.BootReceiver { *; }
-keep class tech.touka.amapnavi.ui.main.MainActivity { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
