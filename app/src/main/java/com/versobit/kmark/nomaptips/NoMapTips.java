/*
 * Copyright (C) 2014-2015 Kevin Mark
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
import android.content.pm.PackageManager;
import android.provider.Settings;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import static de.robv.android.xposed.XposedBridge.hookAllMethods;
import static de.robv.android.xposed.XposedBridge.hookMethod;
import static de.robv.android.xposed.XposedBridge.log;

import static de.robv.android.xposed.XposedHelpers.callMethod;
import static de.robv.android.xposed.XposedHelpers.callStaticMethod;
import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;
import static de.robv.android.xposed.XposedHelpers.findClass;
import static de.robv.android.xposed.XposedHelpers.getStaticObjectField;

public final class NoMapTips implements IXposedHookLoadPackage {

    private static final String TAG = NoMapTips.class.getSimpleName();

    private static final String MAPS_PKG_NAME = "com.google.android.apps.maps";

    private static final int MAPS_VERSION_900 = 900029103;
    private static final int MAPS_VERSION_910 = 901010000;
    private static final int MAPS_VERSION_951 = 905100000;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpp) throws Throwable {
        if(!MAPS_PKG_NAME.equals(lpp.packageName)) {
            return;
        }

        // Find Maps version or die trying
        Object activityThread = callStaticMethod(
                findClass("android.app.ActivityThread", null), "currentActivityThread"
        );
        Context systemCtx = (Context)callMethod(activityThread, "getSystemContext");
        int mapsVersion;
        try {
            mapsVersion = systemCtx.getPackageManager().getPackageInfo(MAPS_PKG_NAME, 0).versionCode;
        } catch (PackageManager.NameNotFoundException ex) {
            log(TAG + ": Could not find Google Maps package...");
            return;
        }

        if(mapsVersion < MAPS_VERSION_951) {
            legacyMapsHook(lpp.classLoader, mapsVersion);
        } else {
            newMapsHook(lpp.classLoader, mapsVersion);
        }
    }

    // "Tip" dialogs are back!
    private static void newMapsHook(ClassLoader loader, int mapsVersion) {
        // Finding the proper class is easy once you know what to look for. Just search for
        // "com.google.android.gms.location.settings.CHECK_SETTINGS" in your decompile
        String gmmMyLocSettingsChecker = "com.google.android.apps.gmm.mylocation.";
        // grep for android.settings.LOCATION_SOURCE_SETTINGS
        String gmmMyLocSettingsDialog = "com.google.android.apps.gmm.mylocation.";
        // Enum class returned from method in above class
        String gmmMyLocSettingsEnum = "com.google.android.apps.gmm.mylocation.";

        if(mapsVersion >= MAPS_VERSION_951) {
            gmmMyLocSettingsChecker += "k";
            gmmMyLocSettingsDialog += "n";
            gmmMyLocSettingsEnum += "b.d";
        }

        // Using hookAll so I don't have to worry about updating the fourth PG'd argument
        hookAllMethods(findClass(gmmMyLocSettingsChecker, loader), "a", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                param.args[2] = true;
            }
        });

        // The "default" result from the settings dialog
        final Object enumField = getStaticObjectField(
                findClass(gmmMyLocSettingsEnum, loader),
                "a"
        );

        // Avoid having to keep track of yet another class by searching for the correct method
        for(Method m : findClass(gmmMyLocSettingsDialog, loader).getDeclaredMethods()) {
            // Method is named "a" and is static
            if(Modifier.isStatic(m.getModifiers()) && "a".equals(m.getName())) {
                Class[] paramTypes = m.getParameterTypes();
                // Method has two params and the second is an interface
                if(paramTypes.length == 2 && paramTypes[1].isInterface()) {
                    // Found it
                    hookMethod(m, new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                            // Return the default response immediately, cutting out the checks
                            return enumField;
                        }
                    });
                    // Stop searching
                    break;
                }
            }
        }
    }

    // Continue to offer support for Maps 8, 9.0, 9.1, and 9.2
    private static void legacyMapsHook(ClassLoader loader, int mapsVersion) {
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
        Class someInterface = findClass(gmmUtilSomeSimpleInterface, loader);
        findAndHookMethod(gmmUtilDialogClass, loader, gmmUtilDialogMethod, boolean.class,
                someInterface, int.class, CharSequence.class, int.class, Intent.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        Intent buttonIntent = (Intent) param.args[5];
                        if (Settings.ACTION_WIFI_SETTINGS.equals(buttonIntent.getAction())
                                || Settings.ACTION_LOCATION_SOURCE_SETTINGS.equals(buttonIntent.getAction())) {
                            // Prevent call to method when WiFi is off or location settings are non-optimal
                            param.setResult(null);
                        }
                    }
                }
        );
    }
}
