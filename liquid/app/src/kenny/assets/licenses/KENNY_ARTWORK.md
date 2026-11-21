# KennyPvt icon artwork

The project owner supplied this image for the KennyPvt app. The original
640 × 640 JPEG is preserved at `xlox/artwork/kenny-logo.jpg`, outside the APK.
The repository does not claim authorship of, or an additional licence over,
the underlying illustration; rights remain with their respective holders.

`xlox/scripts/kenny_icons.py` only resizes the supplied image and applies
antialiased round / rounded-square masks. It creates density-specific legacy,
adaptive and Android system-splash resources exclusively under `src/kenny`.
The artwork is not AI-redrawn, recoloured, stretched, or used in the Anoy app.

Android 26+ launchers choose their own circle/squircle outline. Android 21–25
has separate round and rounded-square fallbacks. The startup badge stays
inside the Android 12+ mask-safe area and is shared with the first app frame.
