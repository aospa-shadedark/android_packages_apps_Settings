package com.android.settings.utils;

import android.os.SystemProperties;

public class DeviceUtils {

    public static boolean isCurrentlySupportedPixel() {
        boolean isPixelDevice = SystemProperties.get("ro.product.model").matches("Pixel (3|4|5|6|7|8|9|10)[a-zA-Z ]*");
        return isPixelDevice;
    }
}