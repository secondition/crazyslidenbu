package com.example.nbu

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.nbu.util.UserManager

class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) UserManager(context).removeUnboundAccounts()
    }
}
