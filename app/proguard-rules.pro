# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# TaskTool is the app_process entry point TaskActions names by STRING (TaskActions.ENTRY):
# nothing references the class, so minification would strip it and every screen action would
# answer "ERR the tool printed no result line". Minify is off today; the rule is for the day
# it is on.
-keep class com.grardb.thorcuts.platform.tasks.TaskTool {
    public static void main(java.lang.String[]);
}
