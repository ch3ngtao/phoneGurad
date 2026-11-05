package com.phoneguard

import android.app.Application
import android.util.Log
import com.facebook.react.PackageList
import com.facebook.react.ReactApplication
import com.facebook.react.ReactHost
import com.facebook.react.ReactNativeApplicationEntryPoint.loadReactNative
import com.facebook.react.defaults.DefaultReactHost.getDefaultReactHost
import kotlin.concurrent.thread

class MainApplication : Application(), ReactApplication {

  override val reactHost: ReactHost by lazy {
    getDefaultReactHost(
      context = applicationContext,
      packageList =
        PackageList(this).packages.apply {
          add(CallDetectionPackage())
        },
    )
  }

  override fun onCreate() {
    super.onCreate()
    loadReactNative(this)
    thread(name = "phoneguard-lookup", isDaemon = true) {
      try {
        PhoneLookupService.getInstance(assets)
      } catch (error: Exception) {
        Log.e("PhoneGuard", "warmup lookup failed", error)
      }
    }
  }
}
