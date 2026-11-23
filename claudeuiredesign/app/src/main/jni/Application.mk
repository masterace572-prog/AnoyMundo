APP_ABI := arm64-v8a
APP_PLATFORM := android-21
APP_STL := c++_static
APP_OPTIM := release
APP_THIN_ARCHIVE := true
APP_PIE         := true

# Official ndk-build switch for 16 KB page-size support (honoured by NDK r27+,
# harmless on the pinned r24 toolchain, where the explicit linker flags in
# Android.mk do the work). See developer.android.com/guide/practices/page-sizes.
APP_SUPPORT_FLEXIBLE_PAGE_SIZES := true


