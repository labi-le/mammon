# Mammon ProGuard/R8 rules.

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

# The FUSE daemon's entry point is named on an app_process command line built by
# RootMount, so nothing in the dex references it and R8 would shrink it away; keeping
# only main() lets everything it reaches stay renameable.
-keep class app.mammon.FuseDaemonKt { public static void main(java.lang.String[]); }
