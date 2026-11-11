# 崩溃日志里保留行号，方便定位问题
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# 读「哪些应用还在后台」是通过反射拿系统的 activity 服务，名字是字符串常量，不会被混淆；
# 这里显式保留一份，避免以后加了别的东西被优化掉
-keep class android.os.ServiceManager { *; }