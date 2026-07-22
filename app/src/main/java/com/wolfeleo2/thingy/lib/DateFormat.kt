package com.wolfeleo2.thingy.lib

import android.text.format.DateUtils

fun formatItemDate(millis: Long): String =
    DateUtils.getRelativeTimeSpanString(millis, System.currentTimeMillis(), DateUtils.DAY_IN_MILLIS).toString()
