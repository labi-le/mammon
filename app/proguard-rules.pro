# Mammon ProGuard/R8 rules.
# nfs-client logs through the slf4j 1.x facade, whose optional StaticLoggerBinder
# is absent on Android; R8 only warns about the missing class, so silence it.
-dontwarn org.slf4j.impl.StaticLoggerBinder

# oncrpc4j carries an RPCSEC_GSS filter and a Subject-building AUTH_SYS credential
# mammon never reaches: it authenticates with its own AuthSys. Neither org.ietf.jgss
# nor com.sun.security.auth exists on Android, and R8 only warns about them.
-dontwarn org.ietf.jgss.**
-dontwarn com.sun.security.auth.**

# Grizzly's static initializer reads version.properties through
# Grizzly.class.getResourceAsStream, a package-relative lookup: renaming the class
# moves the search to the obfuscated package and the load fails with an NPE the
# first time oncrpc4j builds a transport.
-keepnames class org.glassfish.grizzly.Grizzly
