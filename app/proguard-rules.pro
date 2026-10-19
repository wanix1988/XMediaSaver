# XMediaSaver R8 规则

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keepattributes Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations, AnnotationDefault

# org.json（系统自带，仅 shrink 保持引用）
-dontwarn org.json.**

# kotlinx.coroutines
-dontwarn kotlinx.coroutines.**

# 保留实体数据类（按名反射/日志可读性）
-keep class com.wenix.xmediasaver.resolver.** { *; }
-keep class com.wenix.xmediasaver.download.SavedFile { *; }
-keep class com.wenix.xmediasaver.history.HistoryStore$Entry { *; }
