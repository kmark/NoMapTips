/*
 * Copyright (C) 2014 Kevin Mark
 *
 * This file is part of NoMapTips.
 *
 * NoMapTips is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NoMapTips is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with NoMapTips.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.versobit.kmark.nomaptips;

import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class NoMapTips implements IXposedHookLoadPackage {

    private static final String MAPS_PKG_NAME = "com.google.android.apps.maps";

    private static final int MAPS_VERSION_900 = 900029103;
    private static final int MAPS_VERSION_910 = 901010000;

    private static final String ACTIVITY_THREAD_CLASS = "android.app.ActivityThread";
    private static final String ACTIVITY_THREAD_CURRENTACTHREAD = "currentActivityThread";
    private static final String ACTIVITY_THREAD_GETSYSCTX = "getSystemContext";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) throws Throwable {
        if(!loadPackageParam.packageName.equals(MAPS_PKG_NAME)) {
            return;
        }

        Object activityThread = XposedHelpers.callStaticMethod(
                XposedHelpers.findClass(ACTIVITY_THREAD_CLASS, null),
                ACTIVITY_THREAD_CURRENTACTHREAD);
        Context systemCtx = (Context)XposedHelpers.callMethod(activityThread, ACTIVITY_THREAD_GETSYSCTX);
        int mapsVersion = systemCtx.getPackageManager().getPackageInfo(MAPS_PKG_NAME, 0).versionCode;

        String gmmUtilSomeSimpleInterface;
        String gmmUtilDialogClass;
        String gmmUtilDialogMethod = "a";

        if(mapsVersion >= MAPS_VERSION_910) {
            gmmUtilSomeSimpleInterface = "com.google.android.apps.gmm.util.g";
            // public final a(ZLcom/google/android/apps/gmm/util/g;ILjava/lang/CharSequence;ILandroid/content/Intent;)V
            gmmUtilDialogClass = "com.google.android.apps.gmm.util.b";
        }
        else if(mapsVersion >= MAPS_VERSION_900) {
            gmmUtilSomeSimpleInterface = "com.google.android.apps.gmm.util.n";
            // public final a(ZLcom/google/android/apps/gmm/util/n;ILjava/lang/CharSequence;ILandroid/content/Intent;)V
            gmmUtilDialogClass = "com.google.android.apps.gmm.util.i";
        } else {
            // Maps 8 (and possibly before)
            gmmUtilSomeSimpleInterface = "com.google.android.apps.gmm.util.p";
            // public final a(ZLcom/google/android/apps/gmm/util/p;ILjava/lang/CharSequence;ILandroid/content/Intent;)V
            gmmUtilDialogClass = "com.google.android.apps.gmm.util.k";
        }

        // We're hooking onto a utility method that creates and displays dialogs (like tips).
        // It's passed an Intent for a button's action. We can read this Intent to figure out what's
        // going on. Much more reliable than attempting to read resource values (like the dialog
        // title) which could change at any time or be dependent on language. False-positive rate
        // should be pretty low.
        Class someInterface = XposedHelpers.findClass(gmmUtilSomeSimpleInterface, loadPackageParam.classLoader);
        XposedHelpers.findAndHookMethod(gmmUtilDialogClass, loadPackageParam.classLoader,
                gmmUtilDialogMethod, boolean.class, someInterface, int.class, CharSequence.class,
                int.class, Intent.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                Intent buttonIntent = (Intent)param.args[5];
                if(Settings.ACTION_WIFI_SETTINGS.equals(buttonIntent.getAction())
                        || Settings.ACTION_LOCATION_SOURCE_SETTINGS.equals(buttonIntent.getAction())) {
                    // Prevent call to method when WiFi is off or location settings are non-optimal
                    param.setResult(null);
                }
            }
        });
    }
}
