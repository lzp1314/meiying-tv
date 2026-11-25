# 空壳工程暂无需要 keep 的反射调用。
# 混淆开启后，Media3 与 ML Kit 的规则由各自库的 consumer rules 提供。
-keepattributes *Annotation*
-dontwarn kotlinx.coroutines.**
