package com.snoozy.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

// Which DM chats' Reels are allowed to play.
class FriendsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_friends)
        findViewById<TextView>(R.id.pageTitle).text = "💌  Friends' Reels"
        findViewById<View>(R.id.backButton).setOnClickListener { finish() }

        findViewById<View>(R.id.allowAll).setOnClickListener {
            FriendsStore.setAll(this, true)
            renderFriends()
        }
        findViewById<View>(R.id.blockAll).setOnClickListener {
            FriendsStore.setAll(this, false)
            renderFriends()
        }

        val copyIds = findViewById<View>(R.id.copyIds)
        copyIds.visibility = if (DebugCapture.ENABLED) View.VISIBLE else View.GONE
        copyIds.setOnClickListener {
            val dump = DebugCapture.read(this)
            getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("Snoozy screen IDs", dump))
            val msg = if (dump.isEmpty()) "Nothing captured yet. Open Instagram first." else "Copied!"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        renderFriends()
    }

    private fun renderFriends() {
        val list = findViewById<LinearLayout>(R.id.friendsList)
        list.removeAllViews()
        val friends = FriendsStore.all(this)
        val inflater = LayoutInflater.from(this)
        for ((name, allowed) in friends) {
            val row = inflater.inflate(R.layout.item_friend, list, false)
            row.findViewById<TextView>(R.id.friendName).text = name
            val switch = row.findViewById<Switch>(R.id.friendSwitch)
            switch.isChecked = allowed
            switch.setOnCheckedChangeListener { _, checked -> FriendsStore.set(this, name, checked) }
            list.addView(row)
        }
        findViewById<View>(R.id.friendsEmpty).visibility = if (friends.isEmpty()) View.VISIBLE else View.GONE
    }
}
