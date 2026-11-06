# Core loader / native bridge (pre-existing).
-keep class com.xlox.BoxApplication {*;}
-keep class com.xlox.utils.Prefs {*;}
-keep class com.xlox.Overlay {
    public <methods>;
}

-keep public class com.xlox.Overlay
-keep class com.xlox.LogAct { *; }
-keep class com.xlox.MAct { *; }
-keep class com.xlox.FacebookActivity { *; }
-keep class com.xlox.utils.Downtwo { *; }
-keep class com.xlox.utils.AppManager { *; }

# XML Views + design-system helpers. AAPT already keeps custom view class
# names, but keeping the whole package also protects the constructors,
# attribute-set constructors and callback methods that R8 can otherwise merge
# or rename on the release variant (minifyEnabled + shrinkResources).
-keep class com.xlox.ui.designsystem.** { *; }
-keep class com.xlox.ui.ThemeMode { *; }

# Custom views inflated by name from layouts (belt and braces for AAPT rules).
-keep class com.xlox.ui.designsystem.DsSurface { public <init>(...); }
-keep class com.xlox.ui.designsystem.PrimaryButton { public <init>(...); }
-keep class com.xlox.ui.designsystem.StateView { public <init>(...); }
-keep class com.xlox.ui.designsystem.ThemeToggleView { public <init>(...); }
-keep class com.xlox.ui.designsystem.LiveDotView { public <init>(...); }

# Vector drawables referenced through @drawable are kept by resource shrinking;
# keep the compat splash / insets entry points explicitly.
-keep class androidx.core.splashscreen.** { *; }
-keep class androidx.core.view.WindowInsetsControllerCompat { *; }
