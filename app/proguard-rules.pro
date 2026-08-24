# Mammon ProGuard/R8 rules.
# nfs-client logs through the slf4j 1.x facade, whose optional StaticLoggerBinder
# is absent on Android; R8 only warns about the missing class, so silence it.
-dontwarn org.slf4j.impl.StaticLoggerBinder
