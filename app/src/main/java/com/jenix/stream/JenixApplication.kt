package com.jenix.stream

import androidx.multidex.MultiDexApplication

/**
 * Application class - required for MultiDex support.
 * FFmpegKit has >64k methods so MultiDex is mandatory.
 */
class JenixApplication : MultiDexApplication()
