package com.android.settings.utils;

import android.os.SystemProperties;

public class DeviceUtils {

    public static boolean isCurrentlySupportedPixel() {
        boolean isPixelDevice = SystemProperties.get("ro.product.model").matches("Pixel [3-9][a-zA-Z ]*");
        return isPixelDevice;
    }
}